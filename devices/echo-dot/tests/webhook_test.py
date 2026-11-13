import json,os,time,urllib.request,pathlib,datetime
cfg={}
for l in open(os.path.expanduser("~/charlie-voice/config.env")):
    l=l.strip()
    if l and not l.startswith("#") and "=" in l:
        k,v=l.split("=",1); cfg[k]=v.strip().strip('"').strip("'")
U=cfg["CHARLIE_WEBHOOK_URL"]; K=cfg.get("CHARLIE_WEBHOOK_KEY","")
def post(b):
    r=urllib.request.Request(U,data=json.dumps(b).encode(),method="POST",headers={"Content-Type":"application/json"})
    if K: r.add_header("Authorization","Bearer "+K)
    try:
        with urllib.request.urlopen(r,timeout=20) as x: return x.status
    except urllib.error.HTTPError as e: return e.code
    except Exception as e: return type(e).__name__
sid="test-"+datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
print("sid",sid,flush=True)
t0=time.time()
print("turn1 status",post({"session_id":sid,"turn":1,"text":"This is a connection test from the Echo Dot bridge. Please reply with a short hello.","ended":False}),flush=True)
inbox=pathlib.Path.home()/"charlie-voice/inbox"
got=None
while time.time()-t0<180:
    fs=list(inbox.glob(sid+"*"))
    if fs: got=fs[0]; break
    time.sleep(1)
if got:
    print("reply",got.name,"latency %.1fs"%(time.time()-t0),flush=True)
    txt=got.read_text().strip(); print("text:",txt[:300],flush=True)
    port=cfg.get("REPLY_PORT","8793")
    r=urllib.request.Request(f"http://127.0.0.1:{port}/say",data=json.dumps({"text":txt}).encode(),method="POST",headers={"Content-Type":"application/json"})
    try:
        with urllib.request.urlopen(r,timeout=60) as x: print("say",x.status,flush=True)
    except Exception as e: print("say err",e,flush=True)
else: print("no inbox reply in 180s",flush=True)
print("end status",post({"session_id":sid,"turn":2,"text":"","ended":True}),flush=True)
