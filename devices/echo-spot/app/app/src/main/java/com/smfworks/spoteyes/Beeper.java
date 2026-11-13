package com.smfworks.spoteyes;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/** Short rising (session on) and falling (session off) PCM beeps. */
final class Beeper {
    private static final String TAG = "SpotEyes";
    private static final int RATE = 16000;

    static void on() {
        play(new int[] {700, 0, 1100}, new int[] {80, 40, 140});
    }

    static void off() {
        play(new int[] {1100, 0, 700}, new int[] {80, 40, 140});
    }

    static void error() {
        play(new int[] {400, 0, 400}, new int[] {120, 80, 120});
    }

    private static void play(int[] freqs, int[] dursMs) {
        int samples = 0;
        for (int d : dursMs) {
            samples += Math.max(1, RATE * d / 1000);
        }
        byte[] pcm = new byte[samples * 2];
        int at = 0;
        for (int i = 0; i < freqs.length; i++) {
            int n = Math.max(1, RATE * dursMs[i] / 1000);
            int freq = freqs[i];
            for (int s = 0; s < n; s++) {
                double env = 1.0;
                // short fade to avoid clicks
                int fade = Math.min(80, n / 4);
                if (s < fade) {
                    env = s / (double) fade;
                } else if (s > n - fade) {
                    env = (n - s) / (double) fade;
                }
                double val = (freq <= 0) ? 0 : Math.sin(2 * Math.PI * freq * s / RATE);
                short q = (short) (val * env * 12000);
                pcm[at++] = (byte) (q & 0xff);
                pcm[at++] = (byte) ((q >> 8) & 0xff);
            }
        }
        int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.w(TAG, "beep: no audio track");
            return;
        }
        int buf = Math.max(min, pcm.length);
        AudioTrack track = null;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    buf, AudioTrack.MODE_STATIC);
            track.write(pcm, 0, pcm.length);
            track.setNotificationMarkerPosition(samples);
            track.play();
            long wait = 80;
            for (int d : dursMs) {
                wait += d;
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            Log.w(TAG, "beep failed");
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
            }
        }
    }
}
