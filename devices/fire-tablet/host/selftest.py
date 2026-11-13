import sys, time, tempfile, subprocess
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import bridge
bridge.STATE = Path(tempfile.mkdtemp())
for s, h in [("sarah", "Liam, are you there?"), ("james", "hey Paula what time is it"), ("sarah", "how are you"), ("james", "Paulas cat")]:
    a, t = bridge.route(s, h); print("route", s, repr(h), "->", a, repr(t))
for agent, q in [("paula", "Hi Paula, give me a one-line hello."), ("liam", "Hi Liam, give me a one-line hello.")]:
    t0 = time.perf_counter(); reply = bridge.ask(agent, q); t1 = time.perf_counter()
    try:
        audio, mime = bridge.synthesize(reply, bridge.AGENTS[agent]["voice"], codec="wav"); kind = "wav-direct"
        Path(f"samples/{agent}.wav").write_bytes(audio)
    except Exception as e:
        audio, mime = bridge.synthesize(reply, bridge.AGENTS[agent]["voice"]); kind = f"mp3->wav ({str(e)[:60]})"
        tmp = Path(f"samples/{agent}.mp3"); tmp.write_bytes(audio)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(tmp), f"samples/{agent}.wav"], check=True); tmp.unlink()
    t2 = time.perf_counter()
    print(f"{agent}: reply {t1-t0:.2f}s tts {t2-t1:.2f}s total {t2-t0:.2f}s [{kind}] voice={bridge.AGENTS[agent]['voice']} -> {reply!r}")
