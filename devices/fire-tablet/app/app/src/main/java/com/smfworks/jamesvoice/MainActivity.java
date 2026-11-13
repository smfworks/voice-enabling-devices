package com.smfworks.jamesvoice;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Base64;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Push-to-talk client for the Fire HD 8. The tablet has a microphone and
 * speakers but no speech-recognition service, so audio goes to a host
 * bridge. Tries http://127.0.0.1:8766 first (USB adb reverse, short timeout),
 * then http://<HOST_IP>:8766 over Wi-Fi. The LAN host is read from the
 * app-private file files/bridge-host (plain text, no newline). Paula (slot
 * "sarah") speaks with Ara; Liam (slot "james") with Rex. Pico is only the
 * fallback when that audio is missing. Swipe left for Liam and right for
 * Paula. who= stays sarah/james; the host bridge maps them.
 */
public class MainActivity extends Activity {
    private static final String LOOPBACK_TALK = "http://127.0.0.1:8766/talk";
    private static final int RATE = 16000;

    private String who = "sarah";
    private TextView whoName;
    private ImageView portrait;
    private TextView status;
    private TextView heard;
    private TextView reply;
    private Button talk;
    private VoiceBars bars;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;
    private MediaPlayer player;
    private Visualizer visualizer;
    private AudioRecord recorder;
    private volatile boolean recording;
    private volatile boolean discardRecording;
    private Thread recordThread;
    private GestureDetector gestures;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        whoName = (TextView) findViewById(R.id.who);
        portrait = (ImageView) findViewById(R.id.portrait);
        status = (TextView) findViewById(R.id.status);
        heard = (TextView) findViewById(R.id.heard);
        reply = (TextView) findViewById(R.id.reply);
        talk = (Button) findViewById(R.id.talk);
        bars = (VoiceBars) findViewById(R.id.bars);
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int result) {
                if (result != TextToSpeech.SUCCESS) {
                    setStatus("Speaker is not available");
                    return;
                }
                int lang = tts.setLanguage(Locale.US);
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.ENGLISH);
                }
                ttsReady = true;
                if (pendingSpeech != null) {
                    String queued = pendingSpeech;
                    pendingSpeech = null;
                    speak(queued);
                }
            }
        }, "com.svox.pico");
        talk.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    startRecording();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    stopAndSend();
                    return true;
                }
                return false;
            }
        });
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) < 120 || Math.abs(dx) < Math.abs(dy) || Math.abs(velocityX) < 200) {
                    return false;
                }
                if (dx < 0 && "sarah".equals(who)) {
                    setWho("james");
                    return true;
                }
                if (dx > 0 && "james".equals(who)) {
                    setWho("sarah");
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private void setWho(String next) {
        if (next.equals(who)) return;
        if (recording) {
            discardRecording = true;
            stopAndSend();
        }
        who = next;
        if (tts != null) tts.stop();
        stopVisualizer();
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        if (bars != null) bars.setMode(VoiceBars.IDLE);
        whoName.setText("james".equals(who) ? R.string.james : R.string.sarah);
        portrait.setImageResource("james".equals(who) ? R.drawable.liam : R.drawable.paula);
        heard.setText("");
        reply.setText("");
        talk.setText(R.string.hold);
        setStatus("Hold the button and talk");
    }

    private String name() {
        return "james".equals(who) ? "Liam" : "Paula";
    }

    private void startRecording() {
        if (recording) return;
        if (tts != null) tts.stop();
        stopVisualizer();
        if (bars != null) bars.setMode(VoiceBars.LISTENING);
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            setStatus("This tablet did not open the microphone");
            return;
        }
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("Microphone is not available");
            recorder.release();
            recorder = null;
            return;
        }
        recording = true;
        talk.setText(R.string.listening);
        setStatus("Listening… release to send");
        heard.setText("");
        recorder.startRecording();
        recordThread = new Thread(new Runnable() {
            @Override
            public void run() {
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                byte[] buf = new byte[min];
                while (recording) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n > 0) pcm.write(buf, 0, n);
                }
                final byte[] audio = pcm.toByteArray();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (discardRecording) {
                            discardRecording = false;
                            return;
                        }
                        if (audio.length < RATE) {
                            setStatus("Hold a little longer, then talk");
                            talk.setText(R.string.hold);
                            return;
                        }
                        send(audio);
                    }
                });
            }
        });
        recordThread.start();
    }

    private void stopAndSend() {
        recording = false;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (IllegalStateException ignored) {
            }
            recorder.release();
            recorder = null;
        }
        talk.setText(R.string.hold);
    }


    /** LAN hostname or IPv4 of the machine running bridge.py, from files/bridge-host. */
    private String bridgeHost() {
        File file = new File(getFilesDir(), "bridge-host");
        if (!file.isFile() || file.length() < 1 || file.length() > 253) {
            return null;
        }
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                byte[] raw = new byte[(int) file.length()];
                int n = 0;
                while (n < raw.length) {
                    int r = in.read(raw, n, raw.length - n);
                    if (r < 0) {
                        break;
                    }
                    n += r;
                }
                String text = new String(raw, 0, n, "UTF-8").trim();
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

    private String[] bridgeUrls() {
        java.util.ArrayList<String> urls = new java.util.ArrayList<String>();
        urls.add(LOOPBACK_TALK);
        String host = bridgeHost();
        if (host != null) {
            urls.add("http://" + host + ":8766/talk");
        }
        return urls.toArray(new String[0]);
    }

    private HttpURLConnection openBridge(String query, byte[] wav) throws Exception {
        Exception last = null;
        String[] bridges = bridgeUrls();
        for (int i = 0; i < bridges.length; i++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(bridges[i] + query).openConnection();
                conn.setConnectTimeout(i == 0 ? 1000 : 4000);
                conn.setReadTimeout(240000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "audio/wav");
                OutputStream out = conn.getOutputStream();
                out.write(wav);
                out.close();
                return conn;
            } catch (Exception e) {
                last = e;
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (last != null) throw last;
        throw new java.io.IOException("Can't reach the host. Check Wi-Fi.");
    }

    private void send(final byte[] pcm) {
        if (bars != null) bars.setMode(VoiceBars.IDLE);
        final String asked = who;
        setStatus("Hearing you, then asking " + name() + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] wav = wav(pcm);
                    HttpURLConnection conn = openBridge("?who=" + asked, wav);
                    int code = conn.getResponseCode();
                    InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while (in != null && (n = in.read(buf)) >= 0) body.write(buf, 0, n);
                    final JSONObject json = new JSONObject(body.toString("UTF-8"));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!asked.equals(who)) return;
                            show(json);
                        }
                    });
                } catch (Exception e) {
                    final String message = e.getMessage() == null ? e.toString() : e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!asked.equals(who)) return;
                            setStatus("Can't reach the host. Check Wi-Fi.");
                            reply.setText(message);
                        }
                    });
                }
            }
        }).start();
    }

    private void show(JSONObject json) {
        String said = json.optString("heard", "");
        String answer = json.optString("reply", "");
        String error = json.optString("error", "");
        if (said.length() > 0) heard.setText("You: " + said);
        if (error.length() > 0 && answer.length() == 0) {
            setStatus(error);
            return;
        }
        reply.setText(answer);
        if (error.length() == 0 && answer.length() == 0) {
            setStatus(name() + " didn't hear words. Try again.");
            if (bars != null) bars.setMode(VoiceBars.IDLE);
            return;
        }
        String audio = json.optString("audio", "");
        if (audio.length() > 0) {
            setStatus(name());
            playEncoded(audio, answer);
            return;
        }
        setStatus(answer.length() == 0 ? error : name());
        if (answer.length() > 0) speak(answer);
    }

    private void playEncoded(final String audio, final String fallbackText) {
        try {
            byte[] bytes = Base64.decode(audio, Base64.DEFAULT);
            File file = new File(getCacheDir(), "reply.mp3");
            java.io.FileOutputStream out = new java.io.FileOutputStream(file);
            out.write(bytes);
            out.close();
            if (!playFile(file.getAbsolutePath())) speak(fallbackText);
        } catch (Exception e) {
            speak(fallbackText);
        }
    }

    private void speak(final String text) {
        if (!ttsReady || tts == null) {
            pendingSpeech = text;
            return;
        }
        final File file = new File(getCacheDir(), "reply.wav");
        if (file.exists() && !file.delete()) {
            setStatus("Could not play the reply");
            return;
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onError(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("Could not play the reply");
                    }
                });
            }

            @Override
            public void onDone(String utteranceId) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        playFile(file.getAbsolutePath());
                    }
                });
            }
        });
        if (tts.synthesizeToFile(text, null, file, "reply") == TextToSpeech.ERROR) {
            setStatus("Could not play the reply");
        }
    }

    private boolean playFile(String path) {
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        stopVisualizer();
        if (player != null) {
            player.release();
            player = null;
        }
        final MediaPlayer next = new MediaPlayer();
        player = next;
        try {
            next.setAudioStreamType(AudioManager.STREAM_MUSIC);
            next.setDataSource(path);
            next.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    stopVisualizer();
                    if (bars != null) bars.setMode(VoiceBars.IDLE);
                    setStatus("Hold the button and talk");
                }
            });
            next.prepare();
            if (bars != null) bars.setMode(VoiceBars.SPEAKING);
            startVisualizer(next.getAudioSessionId());
            next.start();
            return true;
        } catch (Exception e) {
            stopVisualizer();
            if (bars != null) bars.setMode(VoiceBars.IDLE);
            setStatus("Could not play the reply");
            return false;
        }
    }

    private void startVisualizer(int sessionId) {
        stopVisualizer();
        try {
            Visualizer next = new Visualizer(sessionId);
            int[] range = Visualizer.getCaptureSizeRange();
            next.setCaptureSize(range[0]);
            next.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    if (bars != null) bars.setWaveform(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            next.setEnabled(true);
            visualizer = next;
        } catch (RuntimeException ignored) {
            // The bars fall back to a generated motion while speech plays.
        }
    }

    private void stopVisualizer() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (RuntimeException ignored) {
            }
            visualizer = null;
        }
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private static byte[] wav(byte[] pcm) {
        int data = pcm.length;
        byte[] out = new byte[44 + data];
        out[0] = 'R'; out[1] = 'I'; out[2] = 'F'; out[3] = 'F';
        writeInt(out, 4, 36 + data);
        out[8] = 'W'; out[9] = 'A'; out[10] = 'V'; out[11] = 'E';
        out[12] = 'f'; out[13] = 'm'; out[14] = 't'; out[15] = ' ';
        writeInt(out, 16, 16);
        writeShort(out, 20, 1);
        writeShort(out, 22, 1);
        writeInt(out, 24, RATE);
        writeInt(out, 28, RATE * 2);
        writeShort(out, 32, 2);
        writeShort(out, 34, 16);
        out[36] = 'd'; out[37] = 'a'; out[38] = 't'; out[39] = 'a';
        writeInt(out, 40, data);
        System.arraycopy(pcm, 0, out, 44, data);
        return out;
    }

    private static void writeInt(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void writeShort(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    @Override
    protected void onDestroy() {
        recording = false;
        if (recorder != null) recorder.release();
        stopVisualizer();
        if (player != null) player.release();
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }
}
