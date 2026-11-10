"""JFR execution-sample analysis for the bench server, with intermediary -> Mojang names.

  python jfrtool.py <file.jfr> top [n]                 self time
  python jfrtool.py <file.jfr> incl [n]                inclusive time
  python jfrtool.py <file.jfr> tree <root> [depth] [minPct]   call tree under the first frame containing <root>
  python jfrtool.py <file.jfr> callers <frame> [n]     direct callers of frames containing <frame>
  python jfrtool.py <file.jfr> under <root> [n]        self time restricted to stacks containing <root>

Percentages are of the server thread's samples (or of the root's samples for tree/under).
"""
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict

JFR = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\jfr.exe"
TINY = os.path.expanduser(r"~\.gradle\caches\fabric-loom\1.21.1\loom.mappings.1_21_1.layered+hash.40545-v2\mappings.tiny")
THREAD = "Server thread"


def load_mappings():
    cache = os.path.join(os.path.dirname(__file__), "mappings.json")
    if os.path.exists(cache):
        with open(cache, encoding="utf-8") as f:
            return json.load(f)
    names = {}
    with open(TINY, encoding="utf-8") as f:
        next(f)
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if parts[0] == "c":
                names[parts[2].rsplit("/", 1)[-1].split("$")[-1]] = parts[3].rsplit("/", 1)[-1].split("$")[-1]
                names["C:" + parts[2].replace("/", ".")] = parts[3].replace("/", ".")
            elif len(parts) > 1 and parts[1] in ("m", "f"):
                names[parts[4]] = parts[5]
    with open(cache, "w", encoding="utf-8") as f:
        json.dump(names, f)
    return names


TOKEN = re.compile(r"\b(?:class|method|field)_\d+\b")
FQCN = re.compile(r"\bnet\.minecraft\.[\w.$]*class_\d+[\w$]*")


def demap(frame, names):
    def fq(m):
        full = m.group(0)
        # map outer$inner chains piece by piece
        mapped = names.get("C:" + full)
        if mapped:
            return mapped.rsplit(".", 1)[-1]
        return TOKEN.sub(lambda t: names.get(t.group(0), t.group(0)), full.rsplit(".", 1)[-1])
    frame = FQCN.sub(fq, frame)
    return TOKEN.sub(lambda t: names.get(t.group(0), t.group(0)), frame)


def shorten(frame):
    # "a.b.C.method(args)  line: 12" -> "C.method"
    frame = frame.split("(")[0].strip()
    parts = frame.split(".")
    if len(parts) >= 2:
        return parts[-2] + "." + parts[-1]
    return frame


def parse(path):
    cache = path + ".stacks.json"
    if os.path.exists(cache) and os.path.getmtime(cache) > os.path.getmtime(path):
        with open(cache, encoding="utf-8") as f:
            return json.load(f)
    names = load_mappings()
    out = subprocess.run([JFR, "print", "--events", "jdk.ExecutionSample", "--stack-depth", "1024", path],
                         capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
    stacks = []
    memo = {}
    for event in out.split("jdk.ExecutionSample {")[1:]:
        m = re.search(r'sampledThread = "([^"]*)"', event)
        if not m or m.group(1) != THREAD:
            continue
        body = event.split("stackTrace = [", 1)
        if len(body) < 2:
            continue
        frames = []
        for line in body[1].split("]", 1)[0].splitlines():
            line = line.strip()
            if not line or line == "...":
                continue
            line = re.sub(r"\s+line:.*$", "", line)
            short = memo.get(line)
            if short is None:
                short = memo[line] = shorten(demap(line, names))
            frames.append(short)
        if frames:
            stacks.append(frames)   # leaf first
    with open(cache, "w", encoding="utf-8") as f:
        json.dump(stacks, f)
    return stacks


def pct(n, total):
    return "%6.2f%%" % (100.0 * n / max(1, total))


def main():
    path, cmd = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    stacks = parse(path)
    total = len(stacks)
    print("server thread samples:", total)
    if cmd == "top":
        n = int(args[0]) if args else 40
        c = Counter(s[0] for s in stacks)
        for f, k in c.most_common(n):
            print(pct(k, total), f)
    elif cmd == "incl":
        n = int(args[0]) if args else 60
        c = Counter()
        for s in stacks:
            c.update(set(s))
        for f, k in c.most_common(n):
            print(pct(k, total), f)
    elif cmd == "callers":
        key = args[0]
        n = int(args[1]) if len(args) > 1 else 20
        c = Counter()
        hits = 0
        for s in stacks:
            for i, f in enumerate(s):
                if key in f:
                    hits += 1
                    c[(f, s[i + 1] if i + 1 < len(s) else "<root>")] += 1
                    break
        print("samples with frame:", hits, pct(hits, total))
        for (f, caller), k in c.most_common(n):
            print(pct(k, total), f, "<-", caller)
    elif cmd == "under":
        key = args[0]
        n = int(args[1]) if len(args) > 1 else 40
        sub = [s for s in stacks if any(key in f for f in s)]
        print("samples under root:", len(sub), pct(len(sub), total))
        c = Counter(s[0] for s in sub)
        for f, k in c.most_common(n):
            print(pct(k, len(sub)), pct(k, total), f)
    elif cmd == "tree":
        key = args[0]
        depth = int(args[1]) if len(args) > 1 else 6
        min_pct = float(args[2]) if len(args) > 2 else 1.0
        root = {"n": 0, "kids": {}}
        hits = 0
        for s in stacks:
            idx = None
            for i in range(len(s) - 1, -1, -1):      # outermost match
                if key in s[i]:
                    idx = i
                    break
            if idx is None:
                continue
            hits += 1
            node = root
            node["n"] += 1
            for f in reversed(s[: idx + 1]):
                node = node["kids"].setdefault(f, {"n": 0, "kids": {}})
                node["n"] += 1
        print("samples under root:", hits, pct(hits, total))

        def show(node, d, indent):
            for f, kid in sorted(node["kids"].items(), key=lambda kv: -kv[1]["n"]):
                if 100.0 * kid["n"] / max(1, total) < min_pct:
                    continue
                print(pct(kid["n"], total), "  " * indent + f)
                if d > 1:
                    show(kid, d - 1, indent + 1)
        show(root, depth, 0)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
