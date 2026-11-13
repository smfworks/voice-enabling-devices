package com.smfworks.spoteyes;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One xAI grok-voice realtime session: mic in, streamed PCM out, server VAD.
 * Mic is muted while the assistant is speaking to avoid echo on the Spot.
 * A matching Charlie handoff tool is used when the model asks for a real task.
 */
final class RealtimeSession implements TlsWebSocket.Listener {
    static final String MODEL = "grok-voice-3.0";
    static final String VOICE = "orion";
    static final String HOST = "api.x.ai";
    static final String PATH = "/v1/realtime?model=" + MODEL;
    static final int IN_RATE = 16000;
    static final int PREFERRED_OUT_RATE = 48000;
    static final long SILENCE_MS = 3 * 60 * 1000L;
    static final long MAX_MS = 15 * 60 * 1000L;

    private static final String TAG = "SpotEyes";
    private static final int PREBUF_BYTES = (int) (PREFERRED_OUT_RATE * 0.12 * 2);
    /** Marker: assistant audio for this response is complete; drain then unmute mic. */
    private static final byte[] DRAIN = new byte[] {0x1a, 0x1a};

    interface Host {
        String token();
        String persona();
        String voice();
        boolean handoffReady();
        String handoffToolName();
        String charlieHandoff(String request);
        void face(int mode);
        void setLevel(float level);
        void speakFallback(String text);
        String safe(String msg);
    }

    private final Host host;
    private final Object gate = new Object();
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean speaking = new AtomicBoolean();
    private final AtomicBoolean handed = new AtomicBoolean();
    private final AtomicLong openedAt = new AtomicLong();
    private final AtomicLong lastUserSpeech = new AtomicLong();
    private final AtomicInteger outRate = new AtomicInteger(PREFERRED_OUT_RATE);
    private final LinkedBlockingQueue<byte[]> playQ = new LinkedBlockingQueue<byte[]>();
    private final StringBuilder botPartial = new StringBuilder();

    private TlsWebSocket ws;
    private Thread player;
    private AudioTrack track;
    private volatile String endReason;
    private volatile Exception connectError;
    private volatile String userText = "";
    private volatile String botText = "";
    private volatile String callId;
    private volatile String callArgs = "";
    private int playPending;

    RealtimeSession(Host host) {
        this.host = host;
    }

    boolean isOpen() {
        return opened.get() && !closing.get() && ws != null && ws.isOpen();
    }

    boolean isSpeaking() {
        return speaking.get();
    }

    String endReason() {
        return endReason;
    }

    boolean shouldAutoEnd() {
        if (closing.get()) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        long start = openedAt.get();
        if (start > 0 && now - start >= MAX_MS) {
            endReason = "max 15 min";
            return true;
        }
        if (handed.get()) {
            return false;
        }
        long last = lastUserSpeech.get();
        if (last > 0 && now - last >= SILENCE_MS) {
            endReason = "silence 3 min";
            return true;
        }
        return false;
    }

    void connect(String token) throws IOException {
        int tokenLen = (token == null) ? 0 : token.length();
        if (token == null || token.length() == 0) {
            throw new TlsWebSocket.AuthException("no voice login file");
        }
        int chosen = pickOutRate();
        outRate.set(chosen);
        ws = new TlsWebSocket(this);
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Bearer " + token);
        Log.i(TAG, "realtime connecting (" + MODEL + ", voice " + host.voice()
                + ", token len=" + tokenLen + ", out=" + chosen + "Hz)");
        try {
            ws.connect(HOST, 443, PATH, headers);
        } catch (TlsWebSocket.AuthException e) {
            Log.w(TAG, "realtime token expired or rejected (len=" + tokenLen + ")");
            throw e;
        }
        synchronized (gate) {
            long deadline = SystemClock.uptimeMillis() + 8000;
            while (!ready.get() && !closing.get() && connectError == null
                    && SystemClock.uptimeMillis() < deadline) {
                try {
                    gate.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (connectError != null) {
            closing.set(true);
            if (connectError instanceof TlsWebSocket.AuthException) {
                Log.w(TAG, "realtime token expired or rejected (len=" + tokenLen + ")");
            }
            throw (connectError instanceof IOException)
                    ? (IOException) connectError
                    : new IOException(connectError);
        }
        if (!ready.get()) {
            closing.set(true);
            ws.close(1000, "timeout");
            throw new IOException("realtime session.updated not received");
        }
        openedAt.set(SystemClock.uptimeMillis());
        lastUserSpeech.set(SystemClock.uptimeMillis());
        startPlayer();
        Log.i(TAG, "realtime ready (" + MODEL + ", voice " + host.voice() + ")");
    }

    void onMic(byte[] pcm, int len) {
        if (!isOpen() || speaking.get() || handed.get()) {
            return;
        }
        try {
            ws.sendBinary(pcm, 0, len);
        } catch (IOException e) {
            if (!closing.get()) {
                Log.w(TAG, "realtime mic send " + host.safe(e.getMessage()));
                endReason = "send failed";
                closing.set(true);
            }
        }
    }

    void close(String reason) {
        if (!closing.compareAndSet(false, true)) {
            if (endReason == null) {
                endReason = reason;
            }
            return;
        }
        endReason = reason;
        Log.i(TAG, "realtime closing (" + reason + ")");
        speaking.set(false);
        if (ws != null) {
            ws.close(1000, "bye");
        }
        playQ.clear();
        playQ.offer(new byte[0]); // wake player
        stopPlayer();
        host.face(EyesView.IDLE);
        host.setLevel(0);
        if (openedAt.get() > 0) {
            long secs = (SystemClock.uptimeMillis() - openedAt.get()) / 1000;
            Log.i(TAG, "realtime closed after " + secs + "s (billed ~"
                    + String.format("%.1f", secs / 60.0) + " min, reason=" + reason + ")");
        }
    }

    @Override
    public void onOpen() {
        opened.set(true);
        try {
            ws.sendText(sessionUpdate().toString());
            Log.i(TAG, "realtime session.update sent");
        } catch (Exception e) {
            connectError = e;
            synchronized (gate) { gate.notifyAll(); }
        }
    }

    @Override
    public void onText(String text) {
        try {
            event(new JSONObject(text));
        } catch (Exception e) {
            Log.w(TAG, "realtime event " + host.safe(e.getMessage()));
        }
    }

    @Override
    public void onBinary(byte[] data, int off, int len) {
        if (closing.get() || handed.get()) {
            return;
        }
        byte[] copy = new byte[len];
        System.arraycopy(data, off, copy, 0, len);
        if (!speaking.get()) {
            speaking.set(true);
            host.face(EyesView.TALK);
        }
        lastAudioLevel(copy);
        playPending += len;
        playQ.offer(copy);
    }

    @Override
    public void onClose(int code, String reason) {
        Log.i(TAG, "realtime ws closed " + code + " " + host.safe(reason));
        if (!closing.get()) {
            endReason = "server closed";
            closing.set(true);
        }
        synchronized (gate) { gate.notifyAll(); }
    }

    @Override
    public void onError(Exception e) {
        String msg = (e == null) ? "unknown" : e.getMessage();
        if (msg != null && (msg.contains("401") || (e instanceof TlsWebSocket.AuthException)
                || looksLikeAuth(msg))) {
            Log.w(TAG, "realtime token expired or rejected mid-session");
            endReason = "token expired";
        } else {
            Log.w(TAG, "realtime error " + host.safe(String.valueOf(e)));
            if (endReason == null) {
                endReason = "error";
            }
        }
        closing.set(true);
        connectError = e;
        synchronized (gate) { gate.notifyAll(); }
    }

    private void event(JSONObject e) {
        String t = e.optString("type", "");
        if ("session.created".equals(t)) {
            Log.i(TAG, "session.created");
        } else if ("session.updated".equals(t)) {
            Log.i(TAG, "session.updated");
            ready.set(true);
            synchronized (gate) { gate.notifyAll(); }
        } else if ("input_audio_buffer.speech_started".equals(t)) {
            lastUserSpeech.set(SystemClock.uptimeMillis());
            host.face(EyesView.LISTEN);
            Log.i(TAG, "realtime speech started");
        } else if ("input_audio_buffer.speech_stopped".equals(t)) {
            lastUserSpeech.set(SystemClock.uptimeMillis());
            host.face(EyesView.THINK);
            Log.i(TAG, "realtime speech stopped");
        } else if ("conversation.item.input_audio_transcription.completed".equals(t)) {
            String txt = e.optString("transcript", "").trim();
            if (txt.length() > 0) {
                userText = txt;
                Log.i(TAG, "heard " + txt.length() + " chars");
            }
        } else if ("response.output_audio_transcript.delta".equals(t)) {
            botPartial.append(e.optString("delta", ""));
        } else if ("response.output_audio_transcript.done".equals(t)
                || "response.audio_transcript.done".equals(t)) {
            String txt = e.optString("transcript", "").trim();
            if (txt.length() == 0) {
                txt = botPartial.toString().trim();
            }
            botPartial.setLength(0);
            if (txt.length() > 0) {
                botText = txt;
                Log.i(TAG, "reply " + txt.length() + " chars");
            }
        } else if ("response.output_item.added".equals(t)) {
            JSONObject item = e.optJSONObject("item");
            if (item != null && isHandoffTool(item.optString("name"))) {
                Log.i(TAG, "handoff to Charlie (tool started)");
            }
        } else if ("response.function_call_arguments.delta".equals(t)
                && isHandoffTool(e.optString("name"))) {
            callArgs = callArgs + e.optString("delta", "");
            if (e.has("call_id")) {
                callId = e.optString("call_id");
            }
        } else if ("response.function_call_arguments.done".equals(t)
                && isHandoffTool(e.optString("name"))) {
            if (e.has("call_id")) {
                callId = e.optString("call_id");
            }
            if (e.has("arguments")) {
                callArgs = e.optString("arguments", callArgs);
            }
            startHandoff();
        } else if ("response.done".equals(t)) {
            playQ.offer(DRAIN);
        } else if ("error".equals(t)) {
            JSONObject err = e.optJSONObject("error");
            String code = (err != null) ? err.optString("code", "") : "";
            String type = (err != null) ? err.optString("type", "") : "";
            String msg = (err != null) ? err.optString("message", "") : e.toString();
            if (looksLikeAuth(code + " " + type + " " + msg)) {
                Log.w(TAG, "realtime token expired or rejected (error " + code + ")");
                endReason = "token expired";
                closing.set(true);
            } else {
                Log.w(TAG, "realtime error " + type + " " + code + " " + host.safe(msg));
            }
        }
    }

    private void startHandoff() {
        if (!handed.compareAndSet(false, true)) {
            return;
        }
        Log.i(TAG, "handoff to Charlie");
        speaking.set(false);
        playQ.clear();
        final String req = requestFromArgs(callArgs, userText);
        final String id = callId;
        new Thread(new Runnable() {
            @Override public void run() {
                String reply = null;
                try {
                    reply = host.charlieHandoff(req);
                } catch (Exception e) {
                    Log.w(TAG, "charlie webhook " + host.safe(e.toString()));
                }
                if (reply == null || reply.trim().length() == 0) {
                    reply = "I don't have that update yet.";
                }
                speakVerbatim(id, reply.trim());
                handed.set(false);
                callArgs = "";
                callId = null;
                lastUserSpeech.set(SystemClock.uptimeMillis());
            }
        }, "spot-handoff").start();
    }

    private void speakVerbatim(String id, String text) {
        if (!isOpen()) {
            host.speakFallback(text);
            return;
        }
        try {
            if (id != null && id.length() > 0) {
                JSONObject out = new JSONObject();
                out.put("type", "conversation.item.create");
                JSONObject item = new JSONObject();
                item.put("type", "function_call_output");
                item.put("call_id", id);
                item.put("output", new JSONObject().put("charlie_reply", text).toString());
                out.put("item", item);
                ws.sendText(out.toString());
            }
            JSONObject force = new JSONObject();
            force.put("type", "conversation.item.create");
            JSONObject item = new JSONObject();
            item.put("type", "force_message");
            item.put("role", "assistant");
            item.put("interruptible", true);
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "output_text").put("text", text));
            item.put("content", content);
            force.put("item", item);
            long t0 = SystemClock.uptimeMillis();
            speaking.set(false);
            ws.sendText(force.toString());
            // Wait briefly for streamed audio; fall back to existing TTS if none.
            long waitUntil = t0 + 6000;
            while (SystemClock.uptimeMillis() < waitUntil && isOpen() && !speaking.get()) {
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!speaking.get()) {
                Log.w(TAG, "verbatim handoff produced no live audio; using TTS");
                host.speakFallback(text);
            } else {
                Log.i(TAG, "handoff answer spoken");
            }
        } catch (Exception e) {
            Log.w(TAG, "handoff speak " + host.safe(e.toString()));
            host.speakFallback(text);
        }
    }

    private boolean isHandoffTool(String name) {
        String want = host.handoffToolName();
        return want != null && want.equals(name);
    }

    private JSONObject sessionUpdate() throws Exception {
        JSONObject session = new JSONObject();
        session.put("voice", host.voice());
        session.put("instructions", host.persona());
        session.put("reasoning", new JSONObject().put("effort", "none"));
        session.put("turn_detection", new JSONObject().put("type", "server_vad"));
        if (host.handoffReady()) {
            JSONArray tools = new JSONArray();
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("name", host.handoffToolName());
            tool.put("description",
                    "Pass the user's request to the full assistant and return the answer. Call this with no spoken words.");
            JSONObject params = new JSONObject();
            params.put("type", "object");
            JSONObject props = new JSONObject();
            props.put("request", new JSONObject()
                    .put("type", "string")
                    .put("description", "The user's request, restated clearly."));
            params.put("properties", props);
            params.put("required", new JSONArray().put("request"));
            tool.put("parameters", params);
            tools.put(tool);
            session.put("tools", tools);
        }
        JSONObject audio = new JSONObject();
        JSONObject input = new JSONObject();
        input.put("format", new JSONObject().put("type", "audio/pcm").put("rate", IN_RATE));
        input.put("transport", "binary");
        input.put("transcription", new JSONObject().put("model", "grok-transcribe"));
        audio.put("input", input);
        JSONObject output = new JSONObject();
        output.put("format", new JSONObject().put("type", "audio/pcm").put("rate", outRate.get()));
        output.put("transport", "binary");
        audio.put("output", output);
        session.put("audio", audio);
        JSONObject msg = new JSONObject();
        msg.put("type", "session.update");
        msg.put("session", session);
        return msg;
    }

    private void startPlayer() {
        player = new Thread(new Runnable() {
            @Override public void run() { playLoop(); }
        }, "spot-rt-out");
        player.start();
    }

    private void playLoop() {
        int rate = outRate.get();
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(TAG, "realtime: AudioTrack unsupported at " + rate + "Hz");
            endReason = "audio out failed";
            closing.set(true);
            return;
        }
        int buf = Math.max(min, rate * 2 / 4);
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buf, AudioTrack.MODE_STREAM);
            track.play();
        } catch (Exception e) {
            Log.w(TAG, "realtime AudioTrack " + host.safe(e.getMessage()));
            endReason = "audio out failed";
            closing.set(true);
            return;
        }
        int pre = (int) (rate * 0.12 * 2);
        int buffered = 0;
        boolean started = false;
        java.io.ByteArrayOutputStream pend = new java.io.ByteArrayOutputStream();
        try {
            while (!closing.get()) {
                byte[] chunk;
                try {
                    chunk = playQ.poll(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (chunk == null) {
                    continue;
                }
                if (chunk == DRAIN) {
                    byte[] rest = pend.toByteArray();
                    pend.reset();
                    buffered = 0;
                    if (rest.length > 0) {
                        writeAll(rest);
                    }
                    started = false;
                    speaking.set(false);
                    host.face(EyesView.LISTEN);
                    host.setLevel(0.25f);
                    continue;
                }
                if (chunk.length == 0 && closing.get()) {
                    break;
                }
                if (chunk.length == 0) {
                    continue;
                }
                if (!started) {
                    pend.write(chunk, 0, chunk.length);
                    buffered += chunk.length;
                    if (buffered < pre) {
                        continue;
                    }
                    byte[] first = pend.toByteArray();
                    pend.reset();
                    buffered = 0;
                    started = true;
                    writeAll(first);
                } else {
                    writeAll(chunk);
                }
            }
            // drain
            byte[] rest = pend.toByteArray();
            if (rest.length > 0) {
                writeAll(rest);
            }
        } finally {
            stopTrack();
            speaking.set(false);
        }
    }

    private void writeAll(byte[] data) {
        AudioTrack t = track;
        if (t == null || data == null || data.length == 0) {
            return;
        }
        int off = 0;
        while (off < data.length && !closing.get()) {
            int n = t.write(data, off, data.length - off);
            if (n <= 0) {
                break;
            }
            off += n;
        }
    }

    private void stopPlayer() {
        playQ.offer(new byte[0]);
        Thread p = player;
        if (p != null) {
            try {
                p.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        stopTrack();
    }

    private void stopTrack() {
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try { t.pause(); } catch (Exception ignored) {}
            try { t.flush(); } catch (Exception ignored) {}
            try { t.stop(); } catch (Exception ignored) {}
            try { t.release(); } catch (Exception ignored) {}
        }
    }

    private void lastAudioLevel(byte[] pcm) {
        if (pcm.length < 4) {
            return;
        }
        long sum = 0;
        int n = pcm.length / 2;
        for (int i = 0; i < pcm.length - 1; i += 2) {
            int s = (pcm[i] & 0xff) | (pcm[i + 1] << 8);
            short v = (short) s;
            sum += (long) v * v;
        }
        double rms = Math.sqrt(sum / (double) Math.max(1, n));
        host.setLevel((float) Math.min(1.0, rms / 6000.0));
    }

    private static int pickOutRate() {
        int[] rates = new int[] {PREFERRED_OUT_RATE, 24000, 16000};
        for (int r : rates) {
            int min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (min > 0) {
                return r;
            }
        }
        return 16000;
    }

    private static boolean looksLikeAuth(String s) {
        if (s == null) {
            return false;
        }
        String l = s.toLowerCase();
        return l.contains("invalid_api_key") || l.contains("unauthorized")
                || l.contains("invalid auth") || l.contains("expired")
                || l.contains("401") || l.contains("forbidden")
                || l.contains("authentication");
    }

    private static String requestFromArgs(String args, String fallback) {
        if (args != null && args.length() > 0) {
            try {
                String r = new JSONObject(args).optString("request", "").trim();
                if (r.length() > 0) {
                    return r;
                }
            } catch (Exception ignored) {
            }
        }
        return (fallback != null && fallback.length() > 0) ? fallback : "";
    }
}
