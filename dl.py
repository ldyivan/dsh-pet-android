import json, os, sys, subprocess, time

ROOT = "/storage/emulated/0/AndroidPEProjects/dsh-pet"
DEST = os.path.join(ROOT, "src_repo")
BASE = "https://ghproxy.net/https://raw.githubusercontent.com/PC2005-cloud/dsh-pet/main/"

def load_tree():
    with open(os.path.join(ROOT, "tree.json")) as f:
        return json.load(f)["tree"]

def want(path):
    if path.startswith("assets/screenshots/"):
        return False
    if path.startswith("assets/preview/"):
        return False
    if path.startswith("video/"):
        return False
    return True

def fetch(path, retries=3):
    out = os.path.join(DEST, path)
    if os.path.exists(out) and os.path.getsize(out) > 0:
        return "skip"
    os.makedirs(os.path.dirname(out), exist_ok=True)
    for i in range(retries):
        p = subprocess.run(["curl", "-sS", "-m", "90", "-o", out,
                            BASE + path.replace(" ", "%20").replace("#", "%23")],
                           capture_output=True)
        if p.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 0:
            return "ok"
        time.sleep(1.5)
    if os.path.exists(out):
        os.remove(out)
    return "FAIL"

def main():
    tree = load_tree()
    blobs = [t for t in tree if t["type"] == "blob" and want(t["path"])]
    # sort small files first so source arrives fast
    blobs.sort(key=lambda t: t.get("size", 0))
    fails = []
    for i, t in enumerate(blobs):
        r = fetch(t["path"])
        if r == "FAIL":
            fails.append(t["path"])
        if i % 20 == 0:
            print(i, len(blobs), t["path"], flush=True)
    print("FAILED:", len(fails))
    for f in fails:
        print(" -", f)

if __name__ == "__main__":
    main()
