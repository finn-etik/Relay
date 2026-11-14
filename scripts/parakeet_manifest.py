"""Writes REEL's Parakeet manifest: repo, pinned revision, and per-file size
+ SHA-256, taken from the Hugging Face API at build time. The app downloads
exactly these files at this revision and refuses anything that doesn't match."""
import hashlib
import json
import sys
import urllib.request

REPO = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
FILES = ["encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"]


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "field-ci"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def main(out):
    info = json.loads(get(f"https://huggingface.co/api/models/{REPO}"))
    rev = info["sha"]
    tree = json.loads(get(f"https://huggingface.co/api/models/{REPO}/tree/{rev}"))
    by_path = {e["path"]: e for e in tree}
    files = []
    for name in FILES:
        if name not in by_path:
            sys.exit(f"{name} missing from {REPO}@{rev}")
        entry = by_path[name]
        lfs = entry.get("lfs") or {}
        if lfs.get("oid") and lfs.get("size"):
            sha, size = lfs["oid"], int(lfs["size"])
        else:  # small, non-LFS file: hash it ourselves
            data = get(f"https://huggingface.co/{REPO}/resolve/{rev}/{name}")
            sha, size = hashlib.sha256(data).hexdigest(), len(data)
        files.append({"name": name, "size": size, "sha256": sha})
        print(f"{name}: {size} bytes, sha256 {sha}")
    with open(out, "w") as f:
        json.dump({"repo": REPO, "revision": rev, "files": files}, f, indent=2)


if __name__ == "__main__":
    main(sys.argv[1])
