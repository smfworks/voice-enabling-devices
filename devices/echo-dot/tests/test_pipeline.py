"""Offline pipeline test: raw-level Dot audio -> VAD -> whisper -> stub reply -> Piper TTS (no Dot needed)."""
import os, sys, time, wave
os.environ["STUB_ECHO"] = "1"
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "bridge"))
import numpy as np, bridge
played = []
bridge.dot.play_pcm = lambda pcm, rate, wait=True: played.append((len(pcm), rate))
w = wave.open(sys.argv[1]); a = np.frombuffer(w.readframes(w.getnframes()), np.int16)
raw = (a.astype(np.float32) / 12).astype(np.int16)          # back to raw Dot level
silence = (np.random.randn(16000) * 8).astype(np.int16)     # Dot noise floor
s = bridge.Session(); t0 = time.time()
for chunk in np.array_split(np.concatenate([silence, raw, silence, silence]), 60):
    s.feed(chunk)
s.end()
while not played and time.time() - t0 < 120: time.sleep(0.2)
time.sleep(1)
print("played", played, f"in {time.time()-t0:.1f}s")
