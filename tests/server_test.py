#!/usr/bin/env python3
"""End-to-end tests for the Mac server, driven by a simulated phone.

Runs a throwaway instance of the app on a private port, so it can run while the
real app is up. Only touches the clipboard temporarily (restored at the end).
"""
import json, os, shutil, subprocess, sys, tempfile, threading, time
import urllib.request, urllib.error

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "mac-app/build/PhoneClip.app/Contents/MacOS/PhoneClip")
HELPER = os.path.expanduser("~/Library/Application Support/PhoneClip/bin/phoneclip-clip")
PORT = 47488
BASE = f"http://127.0.0.1:{PORT}"
DOWNLOADS = os.path.expanduser("~/Downloads/PhoneClip")
TRANSCRIPTS = os.path.expanduser("~/Documents/PhoneClip/Transcripts")

passed, failed = [], []

def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(("PASS  " if cond else "FAIL  ") + name + (("  — " + str(detail)) if detail and not cond else ""))

def req(method, path, token, body=None, ctype="application/json", timeout=10):
    r = urllib.request.Request(BASE + path, method=method, data=body,
                               headers={"X-PhoneClip-Token": token, "Content-Type": ctype})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def wait_for(pred, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.2)
    return False

def main():
    tmp = tempfile.mkdtemp(prefix="pc-test-")
    proc = subprocess.Popen([APP, "--dir", tmp, "--port", str(PORT), "--allow-multi"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        cfg = os.path.join(tmp, "config.json")
        if not wait_for(lambda: os.path.exists(cfg), 15):
            print("FAIL  app did not start"); return 1
        token = json.load(open(cfg))["token"]

        # --- clipboard snapshot (restored at the end)
        original = {"kind": "", "text": ""}
        if os.path.exists(HELPER):
            subprocess.run([HELPER, "read", "/tmp/pc-test-save.png"],
                           capture_output=True)
            if os.path.exists("/tmp/pc-test-save.png"):
                original["kind"] = "image"
        if not original["kind"]:
            original["text"] = subprocess.run(["pbpaste"], capture_output=True, text=True).stdout

        # --- fake phones
        stop = threading.Event()
        seen = {"kinds": [], "responses": [], "texts": [], "cmds": []}
        lock = threading.Lock()
        def phone(idx):
            rev = -1
            while not stop.is_set():
                try:
                    st, body = req("GET", f"/v1/clip?rev={rev}&wait=5", token, timeout=8)
                    if st == 204 or not body:
                        continue
                    obj = json.loads(body)
                    with lock:
                        seen["responses"].append(obj)
                        if obj.get("text"):
                            seen["texts"].append(obj["text"])
                    rev = obj.get("rev", rev)
                    if obj.get("reset"):
                        rev = -1
                    cmd = obj.get("cmd")
                    if not cmd:
                        continue
                    with lock:
                        seen["kinds"].append(obj.get("kind"))
                        seen["cmds"].append(idx)
                    if cmd["op"] == "list":
                        res = {"ok": True, "path": cmd["path"],
                               "entries": [{"name": "fake.txt", "dir": False,
                                            "size": 9, "mtime": 0}]}
                        req("POST", f"/v1/sidecar/result?id={cmd['id']}", token,
                            json.dumps(res).encode())
                    elif cmd["op"] == "get":
                        payload = b"fake-body"
                        req("POST", "/v1/file?name=fake-download.txt&silent=1", token,
                            payload, ctype="text/plain")
                        res = {"ok": True, "name": "fake-download.txt", "size": len(payload)}
                        req("POST", f"/v1/sidecar/result?id={cmd['id']}", token,
                            json.dumps(res).encode())
                except Exception:
                    time.sleep(0.3)
        for idx in range(2):
            threading.Thread(target=phone, args=(idx,), daemon=True).start()

        st, body = req("GET", "/v1/ping", token)
        check("ping", st == 200 and b'"ok"' in body, body)

        st, _ = req("POST", "/v1/clip?kind=text", token, b"server-test", ctype="text/plain")
        got = subprocess.run(["pbpaste"], capture_output=True, text=True).stdout
        check("phone text -> clipboard", st == 200 and got == "server-test", got)

        st, body = req("GET", "/v1/clip?rev=-1", token, timeout=5)
        check("clip state served", st == 200 and b"server-test" in body, body)

        st, body = req("GET", "/v1/clip?rev=99999&wait=0", token, timeout=5)
        check("stale rev gets a reset", st == 200 and b'"reset"' in body, body)

        subprocess.run(["osascript", "-e", 'set the clipboard to "fresh-change"'],
                       capture_output=True)
        deadline = time.time() + 6
        fresh = None
        while time.time() < deadline:
            for r in list(seen["responses"]):
                if r.get("text") == "fresh-change":
                    fresh = r
            if fresh:
                break
            time.sleep(0.2)
        check("long-poll delivers a fresh clipboard change", fresh is not None, seen["responses"][-3:])

        st, body = req("GET", "/v1/sidecar/probe?path=/sdcard/Test", token, timeout=20)
        ok = st == 200 and b"fake.txt" in body
        check("sidecar list round-trip", ok, body)
        check("command response does not replay stale clipboard",
              bool(seen["kinds"]) and seen["kinds"][-1] == "", seen["kinds"][-3:])

        time.sleep(0.3)
        check("command delivered to exactly one poller",
              len(seen["cmds"]) == 1, seen["cmds"])

        subprocess.run(["osascript", "-e", 'set the clipboard to "both-phones"'],
                       capture_output=True)
        ok = wait_for(lambda: seen["texts"].count("both-phones") >= 2, 8)
        check("clipboard change reaches both pollers", ok, seen["texts"][-4:])

        target = os.path.join(DOWNLOADS, "fake-download.txt")
        if os.path.exists(target):
            os.remove(target)
        st, body = req("GET", "/v1/sidecar/get?path=/sdcard/Test/fake.txt", token, timeout=20)
        got = open(target, "rb").read() if os.path.exists(target) else b""
        check("sidecar download saves the file", st == 200 and got == b"fake-body", body)
        if os.path.exists(target):
            os.remove(target)

        os.makedirs(TRANSCRIPTS, exist_ok=True)
        out = os.path.join(TRANSCRIPTS, "server-test.txt")
        if os.path.exists(out):
            os.remove(out)
        st, _ = req("POST", "/v1/file?name=server-test.txt&silent=1&dir=transcripts",
                    token, b"transcript-body", ctype="text/plain")
        check("transcript bucket writes to Documents",
              st == 200 and os.path.exists(out), out)
        if os.path.exists(out):
            os.remove(out)

        st, _ = req("POST", "/v1/file?name=a&batch=b&i=0&n=100000", token, b"x",
                    ctype="text/plain")
        check("oversized batch rejected", st == 400, st)

        st, body = req("GET", "/v1/clip?rev=-1", token, timeout=5)
        current_rev = json.loads(body).get("rev", 0) if st == 200 else 0
        held = []
        def hold():
            try:
                held.append(req("GET", f"/v1/clip?rev={current_rev}&wait=3", token, timeout=8)[0])
            except Exception:
                held.append(0)
        threads = [threading.Thread(target=hold) for _ in range(32)]
        for th in threads:
            th.start()
        time.sleep(0.6)
        st, _ = req("GET", f"/v1/clip?rev={current_rev}&wait=1", token, timeout=5)
        check("poller cap returns 429", st == 429, st)
        for th in threads:
            th.join()

        big = os.path.join(DOWNLOADS, "server-test-big.bin")
        if os.path.exists(big):
            os.remove(big)
        payload = b"x" * (70 << 20)
        st, _ = req("POST", "/v1/file?name=server-test-big.bin&silent=1", token,
                    payload, ctype="application/octet-stream", timeout=60)
        size = os.path.getsize(big) if os.path.exists(big) else 0
        check("70 MB upload accepted", st == 200 and size == len(payload), (st, size))
        if os.path.exists(big):
            os.remove(big)

        stop.set()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        shutil.rmtree(tmp, ignore_errors=True)
        if original["kind"] == "image" and os.path.exists("/tmp/pc-test-save.png"):
            subprocess.run([HELPER, "write-image", "/tmp/pc-test-save.png"],
                           capture_output=True)
            os.remove("/tmp/pc-test-save.png")
        else:
            subprocess.run(["pbcopy"], input=original["text"].encode(), capture_output=True)

    print(f"\nRESULT: {len(passed)} passed, {len(failed)} failed")
    return 0 if not failed else 1

if __name__ == "__main__":
    sys.exit(main())
