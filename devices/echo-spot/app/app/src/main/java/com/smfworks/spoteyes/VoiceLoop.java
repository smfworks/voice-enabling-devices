package com.smfworks.spoteyes;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Tap starts a live xAI Grok realtime voice session (beep on); tap again ends it (beep off).
 * The previous STT, Charlie chat, and Orion TTS path is kept in the file as runLegacyStt().
 * Real work is handed to Charlie in Grok Bot when charlie-url and charlie-key are on the device.
 * The spoken reply is read from the host computer on the home LAN, or from ntfy when that port is closed.
 * The xAI refresh token never comes onto the device.
 */
final class VoiceLoop implements Runnable {
    private static final String TAG = "SpotEyes";
    private static final String CHAT_URL = "https://api.x.ai/v1/chat/completions";
    private static final String TTS_URL = "https://api.x.ai/v1/tts";
    private static final String STT_URL = "https://api.x.ai/v1/stt";
    private static final String MODEL = "grok-4-1-fast-non-reasoning";
    private static final String STT_MODEL = "grok-voice-transcribe-2.0";
    private static final String VOICE = "orion";
    private static final int RATE = 16000;
    private static final int FRAME = 320;

    private final Context context;
    private final EyesView eyes;
    private volatile Bot bot = Bot.at(0);
    private final AudioManager.OnAudioFocusChangeListener focus = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
        }
    };
    private final Object gate = new Object();
    private volatile boolean stop;
    private volatile long faceHoldUntil;
    private long nextMicLog;
    private Thread thread;
    private AudioRecord mic;
    private boolean greeted;
    private String lastSpoken = "";
    private volatile boolean tapListen;
    private volatile boolean tapEnd;
    private volatile boolean busy;
    private volatile boolean rtTapOn;
    private volatile boolean rtTapOff;
    private RealtimeSession rt;

    VoiceLoop(Context context, EyesView eyes) {
        this.context = context.getApplicationContext();
        this.eyes = eyes;
    }

    void setBot(Bot bot) {
        if (bot != null) {
            this.bot = bot;
            Log.i(TAG, "bot " + bot.id + " voice " + bot.voice);
        }
    }

    boolean isRealtimeOpen() {
        synchronized (gate) {
            return rt != null && rt.isOpen();
        }
    }

    private String voiceId() {
        return bot.voice;
    }

    void start() {
        enableTls12();
        thread = new Thread(this, "spot-voice");
        thread.start();
    }

    void shutdown() {
        stop = true;
        closeRealtime("shutdown");
        Thread running = thread;
        if (running != null) {
            running.interrupt();
        }
        releaseMic();
    }

    void preview(String state) {
        int mode = EyesView.IDLE;
        if ("listening".equals(state)) {
            mode = EyesView.LISTEN;
        } else if ("thinking".equals(state)) {
            mode = EyesView.THINK;
        } else if ("talking".equals(state)) {
            mode = EyesView.TALK;
        }
        faceHoldUntil = SystemClock.uptimeMillis() + 8000;
        eyes.setMode(mode);
        if ("talking".equals(state)) {
            eyes.setLevel(0.8f);
        } else if ("listening".equals(state)) {
            eyes.setLevel(0.45f);
        } else {
            eyes.setLevel(0f);
        }
    }


    /** First tap starts a Grok realtime session; second tap ends it. */
    void onTap() {
        synchronized (gate) {
            if (rt != null) {
                rtTapOff = true;
                gate.notifyAll();
                Log.i(TAG, "tap realtime off");
                return;
            }
            if (busy) {
                return;
            }
            rtTapOn = true;
            rtTapOff = false;
            face(EyesView.LISTEN);
            eyes.setLevel(0.35f);
            gate.notifyAll();
            Log.i(TAG, "tap realtime on");
        }
    }

    @Override
    public void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        raiseVolume();
        if (!greeted) {
            greeted = true;
            hello();
        }
        byte[] frame = new byte[FRAME * 2];
        while (!stop) {
            // Hold the mic even while idle so Alexa cannot reclaim it.
            if (!ensureMic()) {
                sleep(400);
            }
            boolean start;
            boolean end;
            synchronized (gate) {
                start = rtTapOn;
                end = rtTapOff;
                rtTapOn = false;
                if (!start && !end && (rt == null || !rt.isOpen())) {
                    try {
                        gate.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
            }
            if (rt != null && (!rt.isOpen() || rt.shouldAutoEnd() || end)) {
                String why;
                if (end) {
                    why = "tap";
                } else if (rt.shouldAutoEnd()) {
                    why = rt.endReason();
                } else {
                    why = (rt.endReason() != null) ? rt.endReason() : "server closed";
                }
                closeRealtime(why);
                rtTapOff = false;
                continue;
            }
            if (start) {
                openRealtime();
            }
            if (rt != null && rt.isOpen()) {
                if (rt.shouldAutoEnd() || rtTapOff) {
                    String why = rtTapOff ? "tap" : rt.endReason();
                    rtTapOff = false;
                    closeRealtime(why);
                    continue;
                }
                if (!ensureMic()) {
                    sleep(400);
                    continue;
                }
                int n;
                try {
                    n = mic.read(frame, 0, frame.length);
                } catch (Exception e) {
                    Log.w(TAG, "mic read " + safe(e.getMessage()));
                    releaseMic();
                    sleep(400);
                    continue;
                }
                if (n > 0 && !rt.isSpeaking()) {
                    rt.onMic(frame, n);
                    float level = rms(frame, n);
                    eyes.setLevel(Math.min(1f, level / 3600f));
                }
            }
        }
        closeRealtime("shutdown");
        releaseMic();
    }

    private void openRealtime() {
        String tok = token();
        if (tok == null) {
            Log.w(TAG, "no voice login file");
            Beeper.error();
            face(EyesView.IDLE);
            eyes.setLevel(0f);
            return;
        }
        Beeper.on();
        Log.i(TAG, "beep on");
        face(EyesView.LISTEN);
        eyes.setLevel(0.35f);
        try {
            AudioManager am = (AudioManager) context.getSystemService("audio");
            if (am != null) {
                am.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            rt = new RealtimeSession(new HostImpl());
            rt.connect(tok);
        } catch (TlsWebSocket.AuthException e) {
            Log.w(TAG, "realtime token expired or rejected (len=" + tok.length() + ")");
            closeRealtime("token expired");
        } catch (Exception e) {
            Log.w(TAG, "realtime connect " + safe(e.toString()));
            closeRealtime("connect failed");
        }
    }

    private void closeRealtime(String why) {
        RealtimeSession s;
        synchronized (gate) {
            s = rt;
            rt = null;
            rtTapOn = false;
            rtTapOff = false;
            busy = false;
            gate.notifyAll();
        }
        if (s == null) {
            return;
        }
        s.close(why);
        Beeper.off();
        Log.i(TAG, "beep off (" + why + ")");
        face(EyesView.IDLE);
        eyes.setLevel(0f);
        AudioManager am = (AudioManager) context.getSystemService("audio");
        if (am != null) {
            am.abandonAudioFocus(focus);
        }
    }

    String realtimePersona() {
        String when = new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US)
                .format(new Date());
        String text = bot.role
                + "Keep every reply to one to three short spoken sentences. Plain conversational speech, "
                + "no lists, markdown, links, or emoji. "
                + "Do not name the user's employer or workplace. "
                + "Do not invent facts about the user's calendar, email, notes, tasks or bots. "
                + "Current local time: " + when + ". "
                + "If you do not know a private fact, say you do not have it in front of you. ";
        if (hookReady()) {
            String tool = handoffToolName();
            String who = bot.isCharlie() ? "Charlie" : bot.name;
            text += "When the user asks for a real task (a status report, checking or changing their calendar or email, "
                    + "sending anything, notes or memory, his other bots, or private or current facts you cannot know), "
                    + "call " + tool + " with his request and do not speak. Say nothing before the tool returns. "
                    + "Do not say you are checking with the team, passing that to the team, waiting on the team, or that "
                    + "you will let him know when you have it. Do not promise a follow-up. The device speaks " + who
                    + "'s answer. If you speak at all before the tool result, the only allowed words are: One moment.";
        }
        return text;
    }

    String handoffToolName() {
        return bot.isCharlie() ? "handoff_to_charlie" : "handoff";
    }

    final class HostImpl implements RealtimeSession.Host {
        @Override
        public String token() {
            return VoiceLoop.this.token();
        }

        @Override
        public String persona() {
            return VoiceLoop.this.realtimePersona();
        }

        @Override
        public String voice() {
            return VoiceLoop.this.voiceId();
        }

        @Override
        public boolean handoffReady() {
            return VoiceLoop.this.hookReady();
        }

        @Override
        public String handoffToolName() {
            return VoiceLoop.this.handoffToolName();
        }

        @Override
        public String charlieHandoff(String request) {
            return VoiceLoop.this.charlieHandoff(request);
        }

        @Override
        public void face(int mode) {
            VoiceLoop.this.face(mode);
        }

        @Override
        public void setLevel(float level) {
            eyes.setLevel(level);
        }

        @Override
        public void speakFallback(String text) {
            try {
                lastSpoken = text;
                play(speakFile(text));
            } catch (Exception e) {
                Log.w(TAG, "tts fallback " + safe(e.toString()));
            }
        }

        @Override
        public String safe(String msg) {
            return VoiceLoop.safe(msg);
        }
    }

    /** Previous 1.1.6 tap-to-talk STT path; kept in the code, unused by tap. */
    private void runLegacyStt() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        raiseVolume();
        byte[] frame = new byte[FRAME * 2];
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        float noise = 800f;
        int seen = 0;
        long nextLog = 0;
        long cooldownUntil = 0;
        long listenStart = 0;
        long lastVoice = 0;
        boolean heardVoice = false;
        if (!greeted) {
            greeted = true;
            hello();
            cooldownUntil = SystemClock.uptimeMillis() + 1600;
        }
        while (!stop) {
            if (!ensureMic()) {
                sleep(1000);
                continue;
            }
            int n;
            try {
                n = mic.read(frame, 0, frame.length);
            } catch (Exception ex) {
                Log.w(TAG, "mic read " + safe(ex.getMessage()));
                releaseMic();
                sleep(500);
                continue;
            }
            if (n <= 0) {
                continue;
            }
            float rms = rms(frame, n);
            seen++;
            if (seen < 40) {
                noise = seen == 1 ? rms : noise * 0.9f + rms * 0.1f;
                continue;
            }
            if (!tapListen && rms < noise * 2.2f) {
                noise = noise * 0.985f + rms * 0.015f;
            }
            long now = SystemClock.uptimeMillis();
            if (now < cooldownUntil || busy) {
                eyes.setLevel(0f);
                if (!tapListen) {
                    face(EyesView.IDLE);
                }
                continue;
            }
            if (!tapListen) {
                eyes.setLevel(0f);
                face(EyesView.IDLE);
                continue;
            }
            // Recording after one tap; ends on its own after you stop talking.
            if (said.size() == 0) {
                listenStart = now;
                lastVoice = now;
                heardVoice = false;
            }
            said.write(frame, 0, n);
            float thresh = Math.max(1200f, noise * 6f);
            float voiceThresh = Math.max(900f, noise * 3f);
            if (rms > voiceThresh) {
                lastVoice = now;
                heardVoice = true;
            }
            eyes.setLevel(Math.min(1f, rms / (thresh * 3f)));
            face(EyesView.LISTEN);
            if (now > nextLog) {
                Log.i(TAG, "mic rms=" + Math.round(rms) + " noise=" + Math.round(noise));
                nextLog = now + 5000;
            }
            boolean tooLong = said.size() > RATE * 2 * 20;
            boolean doneTalking = heardVoice && now - lastVoice > 1100;
            boolean nothingSaid = !heardVoice && now - listenStart > 6000;
            if (nothingSaid) {
                Log.i(TAG, "listen timeout");
                said.reset();
                synchronized (gate) {
                    tapListen = false;
                    tapEnd = false;
                }
                eyes.setLevel(0f);
                face(EyesView.IDLE);
                continue;
            }
            if (!tapEnd && !tooLong && !doneTalking) {
                continue;
            }
            if (doneTalking) {
                Log.i(TAG, "auto end");
            }
            byte[] pcm = said.toByteArray();
            said.reset();
            synchronized (gate) {
                tapListen = false;
                tapEnd = false;
                busy = true;
            }
            eyes.setLevel(0f);
            try {
                if (pcm.length > RATE * 2 * 2 / 5) {
                    // Keep mic held across the turn so Alexa cannot reclaim it.
                    turn(pcm);
                } else {
                    Log.i(TAG, "tap too short");
                    face(EyesView.IDLE);
                }
            } catch (Exception ex) {
                Log.w(TAG, "turn " + safe(ex.toString()));
                face(EyesView.IDLE);
            } finally {
                synchronized (gate) {
                    busy = false;
                    gate.notifyAll();
                }
                cooldownUntil = SystemClock.uptimeMillis() + 1600;
            }
        }
        releaseMic();
    }


    private void hello() {
        try {
            if (token() == null) {
                Log.w(TAG, "no voice login file");
                return;
            }
            face(EyesView.THINK);
            lastSpoken = bot.name + " here.";
            File mp3 = speakFile(lastSpoken);
            play(mp3);
        } catch (Exception ex) {
            Log.w(TAG, "hello " + safe(ex.toString()));
        } finally {
            face(EyesView.IDLE);
        }
    }

    private void turn(byte[] pcm) throws Exception {
        face(EyesView.THINK);
        String heard = transcribe(pcm);
        if (heard == null || heard.trim().length() == 0) {
            Log.i(TAG, "heard nothing");
            face(EyesView.IDLE);
            return;
        }
        if (echoOfSpeaker(heard)) {
            Log.i(TAG, "ignored speaker echo");
            face(EyesView.IDLE);
            return;
        }
        String said = heard.trim();
        Log.i(TAG, "heard " + said.length() + " chars");
        String reply = ask(said);
        if (reply == null || reply.trim().length() == 0) {
            face(EyesView.IDLE);
            return;
        }
        reply = reply.trim();
        if (isHandoff(reply)) {
            Log.i(TAG, "handoff to Charlie");
            String fromCharlie = charlieHandoff(said);
            reply = fromCharlie == null ? "I don't have that update yet." : fromCharlie;
        }
        Log.i(TAG, "reply " + reply.length() + " chars");
        remember(said, reply);
        lastSpoken = reply;
        File mp3 = speakFile(lastSpoken);
        play(mp3);
        face(EyesView.IDLE);
    }

    private String transcribe(byte[] pcm) throws Exception {
        byte[] wav = wav(pcm);
        String boundary = "----spoteyes" + SystemClock.uptimeMillis();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", STT_MODEL);
        field(body, boundary, "language", "en");
        field(body, boundary, "format", "true");
        fileField(body, boundary, wav);
        end(body, boundary);
        String json = post(STT_URL, "multipart/form-data; boundary=" + boundary, body.toByteArray(), false);
        JSONObject obj = new JSONObject(json);
        String text = obj.optString("text", "");
        if (text.length() == 0) {
            text = obj.optString("transcript", "");
        }
        return text;
    }

    private String ask(String heard) throws Exception {
        JSONArray messages = new JSONArray();
        String when = new java.text.SimpleDateFormat("EEEE, MMMM d, yyyy, h:mm a", Locale.US).format(new Date());
        messages.put(new JSONObject()
                .put("role", "system")
                .put("content", persona(when)));
        JSONArray prior = loadHistory();
        for (int i = 0; i < prior.length(); i++) {
            messages.put(prior.getJSONObject(i));
        }
        messages.put(new JSONObject().put("role", "user").put("content", heard));
        JSONObject payload = new JSONObject();
        payload.put("model", MODEL);
        payload.put("messages", messages);
        payload.put("max_tokens", 120);
        payload.put("temperature", 0.4);
        byte[] raw = payload.toString().getBytes(StandardCharsets.UTF_8);
        String json = post(CHAT_URL, "application/json; charset=utf-8", raw, true);
        JSONObject choice = new JSONObject(json).getJSONArray("choices").getJSONObject(0);
        String reply = textOf(choice.getJSONObject("message"));
        return reply;
    }

    private String persona(String when) {
        String text = "You are Charlie, the user's chief of staff, speaking through a small round speaker. "
                + "Answer in one to three short spoken sentences. Plain words only. "
                + "No lists, markdown, links, or emoji. "
                + "Do not name the user's employer or workplace. "
                + "Do not invent his calendar, email, notes, or what the other bots did. "
                + "Current local time: " + when + ". ";
        if (hookReady()) {
            text += "If he asks you to do or check something real, a task, a status, his calendar, email, "
                    + "notes, other bots, or a private fact, reply with exactly [HANDOFF] and no other words.";
        } else {
            text += "If you do not know a private fact, say you do not have it in front of you.";
        }
        return text;
    }

    private boolean hookReady() {
        return hookUrl() != null && hookKey() != null;
    }

    private String hookUrl() {
        return httpsUrl(secret(bot.hookFile("url")));
    }

    private String hookKey() {
        return secret(bot.hookFile("key"));
    }

    private boolean isHandoff(String reply) {
        String lead = reply.trim();
        if (lead.startsWith("[HANDOFF]")) {
            return true;
        }
        if (lead.length() > 220) {
            return false;
        }
        String lower = lead.toLowerCase(Locale.US);
        return lower.contains("one moment")
                || lower.contains("with the team")
                || lower.contains("let you know")
                || lower.contains("hang tight");
    }

    private void remember(String heard, String reply) throws Exception {
        JSONArray prior = loadHistory();
        prior.put(new JSONObject().put("role", "user").put("content", clip(heard, 800)));
        prior.put(new JSONObject().put("role", "assistant").put("content", clip(reply, 800)));
        while (prior.length() > 8) {
            prior.remove(0);
        }
        saveHistory(prior);
    }

    /** Posts the turn to Charlie and waits for the spoken reply on ntfy. */
    private String charlieHandoff(String heard) {
        String url = hookUrl();
        String key = hookKey();
        if (url == null || key == null) {
            Log.w(TAG, bot.id + " hook missing");
            return null;
        }
        String session = sessionId();
        int turn = nextTurn();
        String topic = topicFor(session, turn);
        if (!sessionOk(session) || topic == null) {
            return null;
        }
        long since = System.currentTimeMillis() / 1000L - 2L;
        try {
            JSONObject body = new JSONObject();
            body.put("session_id", session);
            body.put("turn", turn);
            body.put("text", clip(heard, 800));
            body.put("ended", false);
            body.put("brain", "spot");
            body.put("fast_reply", "");
            body.put("handoff", true);
            body.put("reply_topic", topic);
            body.put("bot", bot.id);
            int code = postStatus(url, body.toString().getBytes(StandardCharsets.UTF_8), key, 20000);
            Log.i(TAG, "charlie webhook " + code);
            if (code < 200 || code >= 300) {
                return null;
            }
        } catch (Exception ex) {
            Log.w(TAG, "charlie webhook " + safe(ex.toString()));
            return null;
        }
        boolean lan = lanUp();
        Log.i(TAG, lan ? "charlie reply lan" : "charlie reply ntfy");
        long deadline = SystemClock.uptimeMillis() + 115000L;
        while (SystemClock.uptimeMillis() < deadline && !stop) {
            try {
                String text = lan ? pollLan(session, turn) : pollNtfy(topic, since);
                if (text != null && text.trim().length() > 0) {
                    Log.i(TAG, "charlie reply " + text.trim().length() + " chars");
                    return clip(text.trim(), 500);
                }
            } catch (Exception ex) {
                Log.w(TAG, "charlie reply poll " + safe(ex.toString()));
                lan = false;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** Home-LAN reply forwarder. Host comes from the app-private file files/reply-host. */
    private boolean lanUp() {
        String origin = lanOrigin();
        if (origin == null) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(origin + "/health").openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            return conn.getResponseCode() == 200;
        } catch (Exception ex) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** LAN base URL of the reply forwarder, e.g. http://<HOST_IP>:8794 */
    private String lanOrigin() {
        String host = replyHost();
        if (host == null) {
            return null;
        }
        return "http://" + host + ":8794";
    }

    /**
     * Hostname or IPv4 of the machine running spot-reply-forward.py.
     * Write it to the app-private file files/reply-host (plain text, no newline).
     */
    private String replyHost() {
        File file = new File(context.getFilesDir(), "reply-host");
        if (!file.isFile() || file.length() < 1 || file.length() > 253) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                String text = new String(readAll(in), StandardCharsets.UTF_8).trim();
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1).trim();
                }
                if (text.length() < 1 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                        || text.indexOf(' ') >= 0 || text.indexOf('/') >= 0) {
                    return null;
                }
                return text;
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String pollLan(String session, int turn) throws Exception {
        String origin = lanOrigin();
        if (origin == null) {
            return null;
        }
        String url;
        if (bot.isCharlie()) {
            url = origin + "/reply/" + session + "/" + turn;
        } else {
            url = origin + "/" + bot.id + "/reply/" + session + "/" + turn;
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(4000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "spot-eyes/1.1");
            int code = conn.getResponseCode();
            if (code == 404) {
                return null;
            }
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = new String(readAll(stream), StandardCharsets.UTF_8).trim();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("lan HTTP " + code);
            }
            return raw;
        } finally {
            conn.disconnect();
        }
    }

    private String topicFor(String session, int turn) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] raw = digest.digest((session + "-" + turn).getBytes(StandardCharsets.UTF_8));
            StringBuilder topic = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                topic.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            String text = topic.toString();
            return topicOk(text) ? text : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private String pollNtfy(String topic, long since) throws Exception {
        String url = "https://ntfy.sh/" + topic + "/json?poll=1&since=" + since;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "spot-eyes/1.1");
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String raw = new String(readAll(stream), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new IllegalStateException("reply poll HTTP " + code);
            }
            String found = null;
            int start = 0;
            while (start < raw.length()) {
                int end = raw.indexOf('\n', start);
                if (end < 0) {
                    end = raw.length();
                }
                String line = raw.substring(start, end).trim();
                start = end + 1;
                if (line.length() == 0 || line.charAt(0) != '{') {
                    continue;
                }
                JSONObject event = new JSONObject(line);
                if (!"message".equals(event.optString("event"))) {
                    continue;
                }
                String message = event.optString("message", "").trim();
                if (message.length() > 0) {
                    found = message;
                }
            }
            return found;
        } finally {
            conn.disconnect();
        }
    }

    private int postStatus(String url, byte[] body, String bearer, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(readMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("User-Agent", "spot-eyes/1.1");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            readAll(stream);
            return code;
        } finally {
            conn.disconnect();
        }
    }

    private String sessionFile() {
        return bot.isCharlie() ? "charlie-session" : bot.id + "-session";
    }

    private String turnFile() {
        return bot.isCharlie() ? "charlie-turn" : bot.id + "-turn";
    }

    private String sessionId() {
        String existing = secret(sessionFile());
        if (existing != null && existing.length() >= 8 && existing.length() <= 80) {
            return existing;
        }
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) {
            id.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        writeSecret(sessionFile(), id.toString());
        return id.toString();
    }

    private int nextTurn() {
        int turn = 1;
        // Turn files are a short integer (often 1 byte), so do not use secret()
        // which rejects files under 8 bytes. Charlie's file stays charlie-turn.
        String raw = readSmall(turnFile());
        if (raw != null) {
            try {
                turn = Integer.parseInt(raw.trim()) + 1;
            } catch (NumberFormatException ignored) {
                turn = 1;
            }
        }
        writeSecret(turnFile(), Integer.toString(turn));
        return turn;
    }

    /** Read a short file (turn counters). Does not log contents. */
    private String readSmall(String name) {
        File file = new File(context.getFilesDir(), name);
        if (!file.isFile() || file.length() < 1 || file.length() > 80) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                return new String(readAll(in), StandardCharsets.UTF_8).trim();
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean sessionOk(String session) {
        if (session == null || session.length() < 8 || session.length() > 80) {
            return false;
        }
        for (int i = 0; i < session.length(); i++) {
            char c = session.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static boolean topicOk(String topic) {
        if (topic.length() < 20 || topic.length() > 64) {
            return false;
        }
        for (int i = 0; i < topic.length(); i++) {
            char c = topic.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private String httpsUrl(String url) {
        if (url == null) {
            return null;
        }
        String text = url.trim();
        if (!text.startsWith("https://") || text.length() > 300 || text.indexOf(' ') >= 0) {
            return null;
        }
        return text;
    }

    private void writeSecret(String name, String value) {
        File file = new File(context.getFilesDir(), name);
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(value.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "save " + name + " failed");
        }
    }

    private File speakFile(String text) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("text", clip(text, 500));
        payload.put("voice_id", voiceId());
        payload.put("language", "en");
        payload.put("text_normalization", true);
        payload.put("output_format", new JSONObject()
                .put("codec", "mp3")
                .put("sample_rate", 24000)
                .put("bit_rate", 64000));
        byte[] audio = postBytes(TTS_URL, "application/json; charset=utf-8",
                payload.toString().getBytes(StandardCharsets.UTF_8), true);
        if (audio.length < 200 || !mp3(audio)) {
            throw new IllegalStateException("voice response was not mp3");
        }
        File out = new File(context.getCacheDir(), "say.mp3");
        FileOutputStream stream = new FileOutputStream(out);
        try {
            stream.write(audio);
        } finally {
            stream.close();
        }
        return out;
    }

    private void play(File mp3) throws Exception {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        final CountDownLatch done = new CountDownLatch(1);
        MediaPlayer player = new MediaPlayer();
        try {
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(mp3.getAbsolutePath());
            player.setOnCompletionListener(p -> done.countDown());
            player.setOnErrorListener((p, what, extra) -> {
                Log.w(TAG, "player " + what + "/" + extra);
                done.countDown();
                return true;
            });
            player.prepare();
            face(EyesView.TALK);
            player.start();
            done.await(45, TimeUnit.SECONDS);
        } finally {
            player.release();
            audio.abandonAudioFocus(focus);
        }
    }

    private String post(String url, String contentType, byte[] body, boolean json) throws Exception {
        byte[] bytes = postBytes(url, contentType, body, json);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private byte[] postBytes(String url, String contentType, byte[] body, boolean json) throws Exception {
        String bearer = token();
        if (bearer == null) {
            throw new IllegalStateException("no voice login file");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("User-Agent", "spot-eyes/1.0");
            conn.setFixedLengthStreamingMode(body.length);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] data = readAll(stream);
            if (code >= 400) {
                String detail = safe(new String(data, StandardCharsets.UTF_8));
                throw new IllegalStateException("voice HTTP " + code + " " + detail);
            }
            if (json && data.length == 0) {
                throw new IllegalStateException("empty voice response");
            }
            return data;
        } finally {
            conn.disconnect();
        }
    }

    private boolean echoOfSpeaker(String heard) {
        String spoken = normalize(lastSpoken);
        String said = normalize(heard);
        if (said.length() == 0 || spoken.length() == 0) {
            return false;
        }
        if (spoken.contains(said) || said.contains(spoken)) {
            return true;
        }
        int n = Math.min(12, Math.min(said.length(), spoken.length()));
        return n >= 8 && said.regionMatches(0, spoken, 0, n);
    }

    private static String normalize(String text) {
        StringBuilder out = new StringBuilder();
        String lower = text.toLowerCase(Locale.US);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == ' ') {
                out.append(c);
            }
        }
        return out.toString().trim();
    }

    private String secret(String name) {
        File file = new File(context.getFilesDir(), name);
        if (!file.isFile() || file.length() < 8 || file.length() > 2000) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                String text = new String(readAll(in), StandardCharsets.UTF_8).trim();
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1).trim();
                }
                if (text.length() < 8 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                    return null;
                }
                return text;
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String token() {
        File[] files = new File[] {
                new File(context.getFilesDir(), "token"),
                new File(Environment.getExternalStorageDirectory(), "spot-eyes/token"),
                new File("/sdcard/spot-eyes/token")
        };
        for (File file : files) {
            if (file == null || !file.isFile() || file.length() < 20 || file.length() > 20000) {
                continue;
            }
            try {
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] raw = readAll(in);
                    String text = new String(raw, StandardCharsets.UTF_8).trim();
                    if (text.startsWith("\uFEFF")) {
                        text = text.substring(1).trim();
                    }
                    if (text.length() >= 20) {
                        return text;
                    }
                } finally {
                    in.close();
                }
            } catch (Exception ignored) {
                // Try the next path. Do not log the file contents.
            }
        }
        return null;
    }

    private void face(int mode) {
        if (SystemClock.uptimeMillis() < faceHoldUntil) {
            return;
        }
        eyes.setMode(mode);
    }

    private boolean ensureMic() {
        synchronized (gate) {
            if (mic != null && mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                return true;
            }
            releaseMic();
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) {
                Log.w(TAG, "mic buffer " + min);
                return false;
            }
            int[] sources = new int[] {
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC,
                    MediaRecorder.AudioSource.DEFAULT,
                    MediaRecorder.AudioSource.CAMCORDER,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION
            };
            for (int source : sources) {
                try {
                    mic = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, FRAME * 8));
                } catch (Exception ex) {
                    Log.w(TAG, "mic open " + safe(ex.toString()));
                    mic = null;
                    continue;
                }
                if (mic.getState() == AudioRecord.STATE_INITIALIZED) {
                    break;
                }
                releaseMic();
            }
            if (mic == null || mic.getState() != AudioRecord.STATE_INITIALIZED) {
                long now = SystemClock.uptimeMillis();
                if (now > nextMicLog) {
                    Log.w(TAG, "mic not initialized");
                    nextMicLog = now + 15000;
                }
                releaseMic();
                return false;
            }
            mic.startRecording();
            return mic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
        }
    }

    private void releaseMic() {
        synchronized (gate) {
            if (mic == null) {
                return;
            }
            try {
                mic.stop();
            } catch (Exception ignored) {
            }
            try {
                mic.release();
            } catch (Exception ignored) {
            }
            mic = null;
        }
    }

    private void raiseVolume() {
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur < max / 3) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, max * 2 / 3, 0);
            }
        } catch (Exception ex) {
            Log.w(TAG, "volume " + safe(ex.getMessage()));
        }
    }

    private JSONArray loadHistory() {
        File file = new File(context.getFilesDir(), "transcript.json");
        if (!file.isFile()) {
            return new JSONArray();
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                JSONArray all = new JSONArray(new String(readAll(in), StandardCharsets.UTF_8));
                JSONArray kept = new JSONArray();
                int start = Math.max(0, all.length() - 8);
                for (int i = start; i < all.length(); i++) {
                    kept.put(all.getJSONObject(i));
                }
                return kept;
            } finally {
                in.close();
            }
        } catch (Exception ex) {
            return new JSONArray();
        }
    }

    private void saveHistory(JSONArray history) {
        File file = new File(context.getFilesDir(), "transcript.json");
        try {
            FileOutputStream out = new FileOutputStream(file);
            try {
                out.write(history.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "history " + safe(ex.getMessage()));
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(value.getBytes(StandardCharsets.UTF_8));
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void fileField(ByteArrayOutputStream body, String boundary, byte[] wav) throws Exception {
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
        body.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        body.write(wav);
        body.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void end(ByteArrayOutputStream body, String boundary) throws Exception {
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] wav(byte[] pcm) {
        int data = pcm.length;
        byte[] out = new byte[44 + data];
        out[0] = 'R';
        out[1] = 'I';
        out[2] = 'F';
        out[3] = 'F';
        le32(out, 4, 36 + data);
        out[8] = 'W';
        out[9] = 'A';
        out[10] = 'V';
        out[11] = 'E';
        out[12] = 'f';
        out[13] = 'm';
        out[14] = 't';
        out[15] = ' ';
        le32(out, 16, 16);
        le16(out, 20, 1);
        le16(out, 22, 1);
        le32(out, 24, RATE);
        le32(out, 28, RATE * 2);
        le16(out, 32, 2);
        le16(out, 34, 16);
        out[36] = 'd';
        out[37] = 'a';
        out[38] = 't';
        out[39] = 'a';
        le32(out, 40, data);
        System.arraycopy(pcm, 0, out, 44, data);
        return out;
    }

    private static void le16(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xff);
        out[at + 1] = (byte) ((value >> 8) & 0xff);
    }

    private static void le32(byte[] out, int at, int value) {
        out[at] = (byte) (value & 0xff);
        out[at + 1] = (byte) ((value >> 8) & 0xff);
        out[at + 2] = (byte) ((value >> 16) & 0xff);
        out[at + 3] = (byte) ((value >> 24) & 0xff);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > 2_000_000) {
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static float rms(byte[] frame, int n) {
        long sum = 0;
        int samples = n / 2;
        if (samples == 0) {
            return 0f;
        }
        for (int i = 0; i + 1 < n; i += 2) {
            int sample = (frame[i] & 0xff) | (frame[i + 1] << 8);
            sum += (long) sample * sample;
        }
        return (float) Math.sqrt(sum / (double) samples);
    }

    private static boolean mp3(byte[] audio) {
        return audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3'
                || (audio[0] & 0xff) == 0xff;
    }

    private static String textOf(JSONObject message) {
        Object content = message.opt("content");
        if (content instanceof String) {
            return ((String) content).trim();
        }
        if (content instanceof JSONArray) {
            StringBuilder builder = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                Object part = parts.opt(i);
                if (part instanceof JSONObject) {
                    builder.append(((JSONObject) part).optString("text", ""));
                } else if (part != null) {
                    builder.append(part);
                }
            }
            return builder.toString().trim();
        }
        return "";
    }

    private static String clip(String text, int max) {
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String safe(String text) {
        if (text == null) {
            return "";
        }
        String redacted = text.replaceAll("[A-Za-z0-9_\\-]{24,}", "[redacted]");
        return redacted.length() > 180 ? redacted.substring(0, 180) : redacted;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void enableTls12() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, null, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12Factory(context.getSocketFactory()));
        } catch (Exception ex) {
            Log.w(TAG, "tls " + safe(ex.getMessage()));
        }
    }

    private static final class Tls12Factory extends SSLSocketFactory {
        private final SSLSocketFactory base;

        Tls12Factory(SSLSocketFactory base) {
            this.base = base;
        }

        private java.net.Socket patch(java.net.Socket socket) {
            if (socket instanceof SSLSocket) {
                ((SSLSocket) socket).setEnabledProtocols(new String[] {"TLSv1.2"});
            }
            return socket;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return base.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return base.getSupportedCipherSuites();
        }

        @Override
        public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return patch(base.createSocket(s, host, port, autoClose));
        }

        @Override
        public java.net.Socket createSocket(String host, int port) throws java.io.IOException {
            return patch(base.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(String host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return patch(base.createSocket(host, port, local, localPort));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return patch(base.createSocket(host, port));
        }

        @Override
        public java.net.Socket createSocket(java.net.InetAddress host, int port, java.net.InetAddress local, int localPort) throws java.io.IOException {
            return patch(base.createSocket(host, port, local, localPort));
        }
    }
}
