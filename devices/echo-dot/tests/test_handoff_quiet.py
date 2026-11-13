"""Handoff stays quiet: no team holding line, verbatim Charlie text, or a short timeout."""
import os, sys, time
from types import SimpleNamespace

os.environ.setdefault("STUB_ECHO", "1")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "bridge"))
import bridge


def test_prompts_do_not_ask_for_the_team_line():
    banned = "Let me check with the team on that, one moment."
    assert banned not in bridge.CHARLIE_PERSONA
    assert "Let me pass that to the team" not in bridge.RT_PERSONA
    assert "[HANDOFF]" in bridge.CHARLIE_PERSONA
    assert "do not speak" in bridge.RT_PERSONA
    assert "One moment." in bridge.RT_PERSONA


def test_holding_line_detector():
    assert bridge._looks_like_holding("Let me pull that together for you, one moment.")
    assert bridge._looks_like_holding("Let me pass that to the team, one moment.")
    assert bridge._looks_like_holding("Still waiting on the team for that. I'll let you know as soon as it comes back.")
    assert not bridge._looks_like_holding("The fleet is quiet. Two routines finished this morning.")
    assert not bridge._looks_like_holding(bridge.NO_UPDATE)
    assert not bridge._looks_like_holding("x" * 221)


def test_streamspeak_does_not_speak_handoff():
    st = bridge.StreamSpeak("t", None, time.time())
    said = []
    st._say = lambda s: said.append(s)
    st.feed("[HANDOFF] Let me check with the team on that, one moment.")
    st.finish()
    assert st.handoff
    assert said == []
    assert "team" in st.reply()  # kept for the log, not spoken


def test_streamspeak_speaks_a_normal_reply():
    st = bridge.StreamSpeak("t", None, time.time())
    said = []
    st._say = lambda s: said.append(s)
    st.feed("The fleet is quiet. ")
    st.finish()
    assert said == ["The fleet is quiet."]
    assert not st.handoff


def _rt():
    rt = object.__new__(bridge.Realtime)
    rt.s = SimpleNamespace(id="sess", turn=0)
    rt.user_text = "Give me a status report."
    rt.bot_text = ""
    rt.ok = True
    rt.quiet_gen = rt.accept_gen = 0
    rt.turn_handed = False
    rt.delivering = False
    rt.resp_epoch = 3
    rt.partial = ""
    rt.streaming = False
    rt.pend = b""
    rt.last_out = 0
    rt.sent = []

    def send(obj):
        rt.sent.append(obj)
        item = obj.get("item", {}) if isinstance(obj, dict) else {}
        if item.get("type") == "force_message":
            rt.last_out = time.time()
        return True

    rt.send = send
    return rt


def _force_text(rt):
    for obj in rt.sent:
        item = obj.get("item", {})
        if item.get("type") == "force_message":
            return item["content"][0]["text"]
    return None


def test_quiet_gate_drops_model_audio():
    rt = _rt()
    rt.quiet_gen = 1
    rt._out(b"\x01\x02")
    assert rt.last_out == 0
    assert rt.streaming is False


def test_verbatim_answer_and_timeout(monkeypatch=None):
    spoken = {}

    def fake_post(body):
        spoken["body"] = body

    def fake_wait(sid, turn):
        return spoken.get("reply")

    bridge.post_webhook = fake_post
    bridge.wait_reply = fake_wait
    bridge.mark_handoff = lambda *a: None

    rt = _rt()
    spoken["reply"] = "Two routines finished. Nothing is blocked."
    rt.handoff({"call_id": "c1", "arguments": "{\"request\":\"status report\"}"}, 0, 3, False)
    assert spoken["body"]["handoff"] is True
    assert spoken["body"]["text"] == "status report"
    assert _force_text(rt) == "Two routines finished. Nothing is blocked."
    assert not any("team" in json_text(obj) for obj in rt.sent)

    rt = _rt()
    spoken["reply"] = None
    rt.handoff({"call_id": "c2", "arguments": "{}"}, 0, 3, False)
    assert _force_text(rt) == bridge.NO_UPDATE

    rt = _rt()
    def boom(body):
        spoken["body"] = body
        raise RuntimeError("HTTP Error 400: Bad Request")
    bridge.post_webhook = boom
    rt.handoff({"call_id": "c3", "arguments": "{\"request\":\"status report\"}"}, 0, 3, False)
    assert spoken["body"]["handoff"] is True
    assert _force_text(rt) == bridge.NO_UPDATE


def json_text(obj):
    import json
    return json.dumps(obj)


def test_event_hands_off_a_holding_line_without_a_tool():
    rt = _rt()
    started = []
    rt._start_handoff = lambda e, wait_for_done: started.append((e, wait_for_done))
    rt._note_holding_audio = lambda text: None
    rt._finish = lambda stop=False: None
    rt.post_turn = lambda *a: None
    rt.bot_text = "Let me pass that to the team, one moment."
    rt.event({"type": "response.done"})
    assert started == [(None, False)]
    rt2 = _rt()
    rt2._start_handoff = lambda e, wait_for_done: started.append(("normal", e))
    rt2._note_holding_audio = lambda text: None
    rt2._finish = lambda stop=False: None
    rt2.post_turn = lambda *a: None
    rt2.bot_text = "The fleet is quiet."
    rt2.event({"type": "response.done"})
    assert started == [(None, False)]


if __name__ == "__main__":
    test_prompts_do_not_ask_for_the_team_line()
    test_holding_line_detector()
    test_streamspeak_does_not_speak_handoff()
    test_streamspeak_speaks_a_normal_reply()
    test_quiet_gate_drops_model_audio()
    test_verbatim_answer_and_timeout()
    test_event_hands_off_a_holding_line_without_a_tool()
    print("ok")
