package com.smfworks.spoteyes;

import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal TLS 1.2 WebSocket client for Android API 22.
 * Client frames are masked. Supports text, binary, ping, pong and close.
 */
final class TlsWebSocket {
    interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data, int off, int len);
        void onClose(int code, String reason);
        void onError(Exception e);
    }

    private static final String TAG = "SpotEyes";
    private static final int CONNECT_MS = 8000;
    private static final int READ_IDLE_MS = 0; // blocking

    private final Object writeLock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final Listener listener;
    private volatile SSLSocket socket;
    private volatile OutputStream out;
    private volatile InputStream in;
    private volatile boolean open;
    private volatile boolean closing;
    private Thread reader;

    TlsWebSocket(Listener listener) {
        this.listener = listener;
    }

    boolean isOpen() {
        return open && !closing && socket != null && socket.isConnected();
    }

    void connect(String host, int port, String path, Map<String, String> extraHeaders)
            throws IOException {
        SSLSocket sock;
        try {
            sock = openTls(host, port);
        } catch (IOException e) {
            throw new IOException("TLS handshake failed: " + rootCause(e), e);
        } catch (Exception e) {
            throw new IOException("TLS connect failed: " + rootCause(e), e);
        }

        InputStream input = sock.getInputStream();
        OutputStream output = sock.getOutputStream();
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                // Never log these; they may include Authorization.
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        req.append("\r\n");
        output.write(req.toString().getBytes("UTF-8"));
        output.flush();

        String statusLine = readLine(input);
        if (statusLine == null) {
            try { sock.close(); } catch (IOException ignored) {}
            throw new IOException("empty websocket handshake");
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String line;
        while ((line = readLine(input)) != null && line.length() > 0) {
            int c = line.indexOf(':');
            if (c > 0) {
                headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }
        }
        int status = parseStatus(statusLine);
        if (status != 101) {
            try { sock.close(); } catch (IOException ignored) {}
            if (status == 401 || status == 403) {
                throw new AuthException("realtime auth failed, HTTP " + status);
            }
            throw new IOException("websocket handshake HTTP " + status);
        }
        String accept = headers.get("sec-websocket-accept");
        if (accept == null || !accept.equals(expectedAccept(key))) {
            try { sock.close(); } catch (IOException ignored) {}
            throw new IOException("websocket accept mismatch");
        }

        sock.setSoTimeout(READ_IDLE_MS);
        this.socket = sock;
        this.in = input;
        this.out = output;
        this.open = true;
        this.closing = false;
        this.reader = new Thread(new Runnable() {
            @Override public void run() { readLoop(); }
        }, "spot-ws");
        this.reader.start();
        listener.onOpen();
    }

    void sendText(String text) throws IOException {
        byte[] data = text.getBytes("UTF-8");
        sendFrame(0x1, data, 0, data.length);
    }

    void sendBinary(byte[] data, int off, int len) throws IOException {
        sendFrame(0x2, data, off, len);
    }

    void close(int code, String reason) {
        if (closing) {
            return;
        }
        closing = true;
        try {
            byte[] msg = (reason != null) ? reason.getBytes("UTF-8") : new byte[0];
            byte[] payload = new byte[2 + Math.min(msg.length, 120)];
            payload[0] = (byte) ((code >> 8) & 0xff);
            payload[1] = (byte) (code & 0xff);
            System.arraycopy(msg, 0, payload, 2, payload.length - 2);
            sendFrame(0x8, payload, 0, payload.length);
        } catch (Exception ignored) {
        }
        open = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void readLoop() {
        ByteArrayOutputStream fragment = new ByteArrayOutputStream();
        int fragmentOp = -1;
        try {
            while (open && !closing) {
                int b0 = in.read();
                if (b0 < 0) {
                    break;
                }
                int b1 = in.read();
                if (b1 < 0) {
                    throw new EOFException();
                }
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0f;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) {
                    len = ((readByte() & 0xff) << 8) | (readByte() & 0xff);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        len = (len << 8) | (readByte() & 0xff);
                    }
                }
                if (len > 8_000_000L) {
                    throw new IOException("websocket frame too large");
                }
                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask, 0, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload, 0, payload.length);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }
                if (op == 0x0) {
                    fragment.write(payload);
                    if (fin) {
                        deliver(fragmentOp, fragment.toByteArray());
                        fragment.reset();
                        fragmentOp = -1;
                    }
                } else if (op == 0x8) {
                    int code = 1000;
                    String reason = "";
                    if (payload.length >= 2) {
                        code = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
                        if (payload.length > 2) {
                            reason = new String(payload, 2, payload.length - 2, "UTF-8");
                        }
                    }
                    open = false;
                    listener.onClose(code, reason);
                    return;
                } else if (op == 0x9) {
                    sendFrame(0xA, payload, 0, payload.length);
                } else if (op == 0xA) {
                    // pong
                } else if (!fin) {
                    fragment.reset();
                    fragment.write(payload);
                    fragmentOp = op;
                } else {
                    deliver(op, payload);
                }
            }
            if (!closing) {
                listener.onClose(1006, "eof");
            }
        } catch (Exception e) {
            if (!closing) {
                listener.onError(e);
            }
        } finally {
            open = false;
            try {
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void deliver(int op, byte[] payload) {
        if (op == 0x1) {
            try {
                listener.onText(new String(payload, "UTF-8"));
            } catch (Exception e) {
                listener.onError(e);
            }
        } else if (op == 0x2) {
            listener.onBinary(payload, 0, payload.length);
        }
    }

    private void sendFrame(int opcode, byte[] data, int off, int len) throws IOException {
        if (out == null) {
            throw new IOException("websocket not open");
        }
        int header = 2 + 4;
        if (len > 65535) {
            header += 8;
        } else if (len > 125) {
            header += 2;
        }
        byte[] frame = new byte[header + len];
        frame[0] = (byte) (0x80 | (opcode & 0x0f));
        int idx = 2;
        if (len <= 125) {
            frame[1] = (byte) (0x80 | len);
        } else if (len <= 65535) {
            frame[1] = (byte) (0x80 | 126);
            frame[2] = (byte) ((len >> 8) & 0xff);
            frame[3] = (byte) (len & 0xff);
            idx = 4;
        } else {
            frame[1] = (byte) (0x80 | 127);
            long length = len & 0xffffffffL;
            for (int i = 7; i >= 0; i--) {
                frame[2 + (7 - i)] = (byte) ((length >> (8 * i)) & 0xff);
            }
            idx = 10;
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        System.arraycopy(mask, 0, frame, idx, 4);
        int dataAt = idx + 4;
        for (int i = 0; i < len; i++) {
            frame[dataAt + i] = (byte) (data[off + i] ^ mask[i & 3]);
        }
        synchronized (writeLock) {
            if (out == null) {
                throw new IOException("websocket not open");
            }
            out.write(frame);
            out.flush();
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private void readFully(byte[] buf, int off, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int n = in.read(buf, off + got, len - got);
            if (n < 0) {
                throw new EOFException();
            }
            got += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (sb.length() == 0) {
                    return null;
                }
                break;
            }
            if (b == '\n' && prev == '\r') {
                sb.setLength(sb.length() - 1);
                break;
            }
            sb.append((char) b);
            prev = b;
            if (sb.length() > 8192) {
                throw new IOException("handshake line too long");
            }
        }
        return sb.toString();
    }

    private static int parseStatus(String statusLine) {
        // HTTP/1.1 101 Switching Protocols
        int sp = statusLine.indexOf(' ');
        if (sp < 0) {
            return 0;
        }
        int sp2 = statusLine.indexOf(' ', sp + 1);
        String code = (sp2 > sp) ? statusLine.substring(sp + 1, sp2) : statusLine.substring(sp + 1);
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String expectedAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8"));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }



    /**
     * Fire OS 5 / API 22: do not enable every supported suite (that can produce
     * a SSLv2-style ClientHello the server rejects), and set SNI via setHostname.
     * api.x.ai's TLS 1.2 cipher is ECDHE-ECDSA-AES128-GCM-SHA256; add it if present.
     */
    private SSLSocket openTls(String host, int port) throws IOException {
        SSLSocketFactory factory = HttpsURLConnection.getDefaultSSLSocketFactory();
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(host, port), CONNECT_MS);
        SSLSocket sock = (SSLSocket) factory.createSocket(plain, host, port, true);
        prepareSocket(sock, host);
        try {
            sock.startHandshake();
            HostnameVerifier hv = HttpsURLConnection.getDefaultHostnameVerifier();
            if (!hv.verify(host, sock.getSession())) {
                try { sock.close(); } catch (IOException ignored) {}
                throw new IOException("TLS hostname mismatch for " + host);
            }
            Log.i(TAG, "tls ok " + sock.getSession().getProtocol() + " " + sock.getSession().getCipherSuite());
            return sock;
        } catch (IOException e) {
            try { sock.close(); } catch (IOException ignored) {}
            throw e;
        }
    }

    private static void prepareSocket(SSLSocket sock, String host) {
        sock.setEnabledProtocols(new String[] {"TLSv1.2"});
        try {
            Method setHostname = sock.getClass().getMethod("setHostname", String.class);
            setHostname.invoke(sock, host);
        } catch (Exception ignored) {
        }
        try {
            Method tickets = sock.getClass().getMethod("setUseSessionTickets", boolean.class);
            tickets.invoke(sock, true);
        } catch (Exception ignored) {
        }
        String[] supported = sock.getSupportedCipherSuites();
        java.util.Set<String> have = new java.util.HashSet<String>();
        if (supported != null) {
            for (String s : supported) {
                have.add(s);
            }
        }
        String[] preferred = new String[] {
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
                "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
                "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_RSA_WITH_AES_128_CBC_SHA"
        };
        List<String> enabled = new ArrayList<String>();
        for (String c : preferred) {
            if (have.contains(c)) {
                enabled.add(c);
            }
        }
        try {
            for (String c : sock.getEnabledCipherSuites()) {
                if (c != null && c.startsWith("TLS_") && !c.contains("NULL")
                        && !c.contains("EXPORT") && !c.contains("RC4")
                        && !c.contains("_DES_") && !c.contains("MD5")
                        && !enabled.contains(c)) {
                    enabled.add(c);
                }
            }
        } catch (Exception ignored) {
        }
        if (!enabled.isEmpty()) {
            sock.setEnabledCipherSuites(enabled.toArray(new String[0]));
        }
        Log.i(TAG, "tls ciphers " + enabled.size()
                + " ecdhe_ecdsa_gcm=" + enabled.contains("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"));
        try {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(CONNECT_MS);
        } catch (Exception ignored) {
        }
    }

    private static String rootCause(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String msg = c.getMessage();
        String name = c.getClass().getSimpleName();
        if (msg == null || msg.length() == 0) {
            return name;
        }
        if (msg.length() > 180) {
            msg = msg.substring(0, 180);
        }
        return name + ": " + msg;
    }

    static final class AuthException extends IOException {
        AuthException(String msg) {
            super(msg);
        }
    }
}
