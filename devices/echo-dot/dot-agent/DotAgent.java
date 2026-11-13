// Charlie voice Dot agent. Runs as root via app_process on the Echo Dot.
// Listens on 127.0.0.1:<port>; the bridge on your Linux box reaches it with `adb forward` (old adbd has no working reverse).
// Frames: [type:1][len:4 BE][payload]. Dot->bridge: H hello, S start, E end, A pcm16 mono 16 kHz.
// Bridge->Dot: P play (4-byte BE sample rate + pcm16 mono), R resync (1 byte: 1 active, 0 idle).
// Streaming: O open (4-byte BE rate), C chunk (pcm16), F finish (drain, then D), X stop now (then D).
// While audio plays, the 12-LED ring pulses blue with the speech envelope (IS31FL3236 frame sysfs).
import android.media.*;
import java.io.*;
import java.net.*;

public class DotAgent {
  static final int RATE = 16000;
  static volatile boolean active = false, playing = false;
  static volatile DataOutputStream out;
  static final Object wlock = new Object();
  static final java.util.concurrent.LinkedBlockingQueue<byte[]> sq = new java.util.concurrent.LinkedBlockingQueue<byte[]>();
  static volatile AudioTrack st; static volatile int srate; static volatile boolean stopNow;
  // 12 RGB LEDs, RRGGBB per LED. Idle frame is whatever the ring showed at start (dim red while muted).
  static final String LIGHT = "/sys/devices/soc/11007000.i2c/i2c-0/0-003f/frame";
  static final Object llock = new Object();
  static final java.util.ArrayDeque<long[]> lq = new java.util.ArrayDeque<long[]>();
  static volatile String idleFrame = "280000280000280000280000280000280000280000280000280000280000280000280000";
  static boolean lightErr = false;

  static void log(String s) { System.out.println(System.currentTimeMillis() + " " + s); System.out.flush(); }

  static void send(char t, byte[] p, int n) {
    DataOutputStream o = out; if (o == null) return;
    synchronized (wlock) { try { o.writeByte(t); o.writeInt(n); if (n > 0) o.write(p, 0, n); o.flush(); } catch (IOException e) { out = null; } }
  }

  static void writeFrame(String hex) {
    FileOutputStream o = null;
    try {
      o = new FileOutputStream(LIGHT);
      o.write(hex.getBytes("US-ASCII"));
    } catch (Exception e) {
      if (!lightErr) { lightErr = true; log("light: " + e); }
    } finally { try { if (o != null) o.close(); } catch (Exception e) {} }
  }

  static String blueFrame(int level) {
    if (level < 0) level = 0;
    if (level > 255) level = 255;
    String b = Integer.toHexString(level);
    if (b.length() < 2) b = "0" + b;
    String one = "0000" + b;
    StringBuilder s = new StringBuilder(72);
    for (int i = 0; i < 12; i++) s.append(one);
    return s.toString();
  }

  static void readIdle() {
    FileInputStream in = null;
    try {
      in = new FileInputStream(LIGHT);
      byte[] buf = new byte[96];
      int n = 0, k;
      while (n < 72 && (k = in.read(buf, n, 72 - n)) > 0) n += k;
      if (n >= 72) idleFrame = new String(buf, 0, 72, "US-ASCII");
    } catch (Exception e) { log("light idle: " + e); }
    finally { try { if (in != null) in.close(); } catch (Exception e) {} }
  }

  // Schedule 20 ms slices. lagMs is how far the speaker is behind this write, so the ring
  // meets the sound instead of leading it.
  static void enqueueLight(byte[] p, int off, int n, int rate, int lagMs) {
    if (n < 2 || rate < 1000) return;
    int step = (rate / 50) * 2;
    if (step < 2) step = 2;
    if (lagMs < 0) lagMs = 0;
    if (lagMs > 400) lagMs = 400;
    long due = System.currentTimeMillis() + lagMs;
    synchronized (llock) {
      while (lq.size() > 100) lq.pollFirst();
      for (int i = 0; i < n; i += step) {
        int peak = 0;
        int end = off + Math.min(n, i + step);
        for (int j = off + i; j + 1 < end; j += 2) {
          int s = (p[j] & 255) | ((p[j + 1] & 255) << 8);
          if (s >= 32768) s -= 65536;
          if (s < 0) s = -s;
          if (s > peak) peak = s;
        }
        int level = peak <= 0 ? 0 : (int) (Math.sqrt(peak / 32767.0) * 255);
        if (level > 255) level = 255;
        long[] item = new long[2];
        item[0] = due; item[1] = level;
        lq.addLast(item);
        due += 20;
      }
    }
  }

  static void clearLight() { synchronized (llock) { lq.clear(); } }

  static int speakerLagMs() {
    AudioTrack a = st;
    if (a == null || srate < 1000) return 0;
    try {
      long head = a.getPlaybackHeadPosition() & 0xffffffffL;
      long behind = writtenSamples - head;
      if (behind < 0) behind = 0;
      if (behind > srate) behind = srate;
      return (int) (behind * 1000 / srate);
    } catch (Exception e) { return 0; }
  }

  static volatile long writtenSamples = 0;

  static void lightLoop() {
    readIdle();
    log("light ring ready");
    int shown = 0;
    boolean was = false;
    while (true) {
      try {
        if (!playing) {
          if (was) { clearLight(); writeFrame(idleFrame); shown = 0; was = false; }
          sleep(30);
          continue;
        }
        long now = System.currentTimeMillis();
        int goal = -1;
        synchronized (llock) {
          while (!lq.isEmpty() && lq.peekFirst()[0] <= now) goal = (int) lq.pollFirst()[1];
        }
        if (!was) {
          if (goal < 0) { sleep(20); continue; }   // keep the idle ring until the first sound is due
          was = true;
        }
        if (goal < 0) shown = shown <= 8 ? 0 : shown - Math.max(8, shown / 5);
        else if (goal >= shown) shown = goal;
        else {
          shown = shown - Math.max(6, (shown - goal) / 3);
          if (shown < goal) shown = goal;
        }
        writeFrame(blueFrame(shown));
        sleep(20);
      } catch (Exception e) { log("light loop: " + e); sleep(100); }
    }
  }

  static void setActive(boolean a, boolean notify) {
    if (active == a) return; active = a; log(a ? "ACTIVE" : "IDLE");
    if (notify) send(a ? 'S' : 'E', null, 0);
  }

  public static void main(String[] args) throws Exception {
    final int port = args.length > 0 ? Integer.parseInt(args[0]) : 8790;
    new Thread(new Runnable() { public void run() { lightLoop(); } }).start();
    // Mute key watcher: each KEY_MUTE DOWN toggles conversation state.
    new Thread(new Runnable() { public void run() {
      while (true) {
        try {
          // Read input_event records directly (getevent's stdout is block-buffered when piped).
          // 32-bit process on this kernel: 16-byte records {sec, usec, type:u16, code:u16, value:s32}.
          FileInputStream in = new FileInputStream("/dev/input/event1");
          byte[] ev = new byte[16];
          while (true) {
            int got = 0;
            while (got < 16) { int k = in.read(ev, got, 16 - got); if (k < 0) throw new IOException("eof"); got += k; }
            int type = (ev[8] & 255) | ((ev[9] & 255) << 8);
            int code = (ev[10] & 255) | ((ev[11] & 255) << 8);
            int value = (ev[12] & 255) | ((ev[13] & 255) << 8) | ((ev[14] & 255) << 16) | (ev[15] << 24);
            if (type == 1 && code == 113 && value == 1) { log("KEY_MUTE"); setActive(!active, true); }
          }
        } catch (Exception e) { log("input: " + e); }
        sleep(1000);
      }
    }}).start();
    // Recorder: streams audio while active and not playing back.
    new Thread(new Runnable() { public void run() {
      int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      byte[] buf = new byte[3200];
      AudioRecord rec = null;
      while (true) {
        if (active && out != null) {
          if (rec == null) {
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 4);
            rec.startRecording(); log("rec start");
          }
          int k = rec.read(buf, 0, buf.length);
          if (k > 0 && !playing) send('A', buf, k);
        } else {
          if (rec != null) { rec.stop(); rec.release(); rec = null; log("rec stop"); }
          sleep(50);
        }
      }
    }}).start();
    // Streaming player: O/C/F frames are queued so the socket reader never blocks.
    new Thread(new Runnable() { public void run() {
      long written = 0;
      while (true) {
        try {
          byte[] p = sq.take();
          if (st == null && p.length == 4) {            // O: open
            int rate = ((p[0] & 255) << 24) | ((p[1] & 255) << 16) | ((p[2] & 255) << 8) | (p[3] & 255);
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            st = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 2, rate / 5 * 2), AudioTrack.MODE_STREAM);
            srate = rate; written = 0; writtenSamples = 0; stopNow = false; clearLight(); playing = true; st.play(); log("stream open " + rate);
          } else if (p.length == 0) {                   // F or X: drain (unless stopped) and close
            AudioTrack a = st;
            if (a != null) {
              long end = System.currentTimeMillis() + written * 1000 / Math.max(srate, 1) + 2000;
              while (!stopNow && (a.getPlaybackHeadPosition() & 0xffffffffL) < written && System.currentTimeMillis() < end) sleep(20);
              try { a.stop(); a.release(); } catch (Exception e) {}
              st = null; log("stream done " + (stopNow ? "(stopped)" : ""));
              sleep(150); playing = false; send('D', null, 0);
            }
          } else if (st != null && !stopNow) {          // C: pcm
            int lag = speakerLagMs();
            st.write(p, 0, p.length); written += p.length / 2; writtenSamples = written;
            enqueueLight(p, 0, p.length, srate, lag);
          }
        } catch (Exception e) { log("stream: " + e); st = null; clearLight(); playing = false; }
      }
    }}).start();
    // Connection loop.
    ServerSocket ss = new ServerSocket();
    ss.setReuseAddress(true);
    ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
    while (true) {
      try {
        Socket s = ss.accept(); s.setTcpNoDelay(true);
        out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        log("connected");
        byte[] hello = ("{\"active\":" + active + "}").getBytes("UTF-8");
        send('H', hello, hello.length);
        while (true) {
          int t = in.readUnsignedByte(); int n = in.readInt();
          byte[] p = new byte[n]; in.readFully(p);
          if (t == 'R') { setActive(p.length > 0 && p[0] == 1, false); }
          else if (t == 'P') { play(p); }
          else if (t == 'O') { sq.clear(); sq.add(p); }
          else if (t == 'C') { sq.add(p); }
          else if (t == 'F') { sq.add(new byte[0]); }
          else if (t == 'X') { sq.clear(); clearLight(); stopNow = true; AudioTrack a = st; if (a != null) { try { a.pause(); a.flush(); } catch (Exception e) {} } sq.add(new byte[0]); }
        }
      } catch (Exception e) { log("conn: " + e); out = null; }
      sleep(200);
    }
  }

  static void play(byte[] p) {
    int rate = ((p[0] & 255) << 24) | ((p[1] & 255) << 16) | ((p[2] & 255) << 8) | (p[3] & 255);
    int n = p.length - 4;
    playing = true;
    try {
      AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(n, 4096), AudioTrack.MODE_STATIC);
      t.write(p, 4, n); t.play();
      enqueueLight(p, 4, n, rate, 0);
      sleep((long) (n / 2) * 1000 / rate + 300);
      t.stop(); t.release();
    } catch (Exception e) { log("play: " + e); }
    playing = false;
    send('D', null, 0);
  }

  static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
}
