# Next-word top-3 of FUTO Swipe's context LM (chosen_planarian) on our held-out sets, mirroring
# swipe-library's ContextLMScorer (context_lm.cpp, vocab_hash.hpp). Writes per-word hits.
import subprocess, sys, glob, os, time
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "executorch"], check=True)
import numpy as np, torch
from executorch.runtime import Runtime

D = os.path.dirname(glob.glob("/kaggle/input/**/context_lm.pte", recursive=True)[0])
M = (1 << 64) - 1

def wymix(a, b):
    r = (a * b) & ((1 << 128) - 1)
    return (r & M) ^ (r >> 64)
def r8(p, i): return int.from_bytes(p[i:i+8], "little")
def r4(p, i): return int.from_bytes(p[i:i+4], "little")
def r3(p, k): return (p[0] << 16) | (p[k >> 1] << 8) | p[k - 1]
def wyhash(p, seed=0):
    s0, s1, s2, s3 = 0xa0761d6478bd642f, 0xe7037ed1a0b428db, 0x8ebc6af09c88c6e3, 0x589965cc75374cc3
    n = len(p); seed ^= wymix(seed ^ s0, s1)
    if n <= 16:
        if n >= 4:
            a = ((r4(p, 0) << 32) | r4(p, (n >> 3) << 2)) & M
            b = ((r4(p, n - 4) << 32) | r4(p, n - 4 - ((n >> 3) << 2))) & M
        elif n > 0: a, b = r3(p, n), 0
        else: a = b = 0
    elif n <= 48:
        i = 0
        while i + 16 <= n: seed = wymix(r8(p, i) ^ s1, r8(p, i + 8) ^ seed); i += 16
        a, b = r8(p, n - 16), r8(p, n - 8)
    else:
        i = 0; see1 = see2 = seed
        while i + 48 <= n:
            seed = wymix(r8(p, i) ^ s1, r8(p, i + 8) ^ seed)
            see1 = wymix(r8(p, i + 16) ^ s2, r8(p, i + 24) ^ see1)
            see2 = wymix(r8(p, i + 32) ^ s3, r8(p, i + 40) ^ see2); i += 48
        while i + 16 <= n: seed = wymix(r8(p, i) ^ s1, r8(p, i + 8) ^ seed); i += 16
        seed ^= see1 ^ see2; a, b = r8(p, n - 16), r8(p, n - 8)
    return wymix(s1 ^ n, wymix(a ^ s1, b ^ seed))

prog = Runtime.get().load_program(os.path.join(D, "context_lm.pte"))
fwd = prog.load_method("forward")
emb, bias, hemb, hbias = [t.numpy() if hasattr(t, "numpy") else np.asarray(t) for t in prog.load_method("get_embeddings").execute([])]
T, E = emb.shape; B = hemb.shape[0]; L = 16; K = 2
shift = 64 - (B.bit_length() - 1)
print("exact", T, "dim", E, "buckets", B)

id2word = ["<OOV>"]; w2id = {}; w2id_lower = {}
for i, w in enumerate(open(os.path.join(D, "vocab.txt"), encoding="utf-8").read().split("\n")):
    if not w and i >= 32767: continue
    w2id[w] = i + 1; id2word.append(w)
    if w.lower() != w: w2id_lower[w.lower()] = i + 1
first = np.array([ord(id2word[i][0].lower()) if i and id2word[i] else 0 for i in range(T)])
low = [id2word[i].lower() if i < len(id2word) else "" for i in range(T)]

def lookup(w):
    i = w2id.get(w) or w2id_lower.get(w.lower())
    if i is not None: return i, (0, 0)
    k = wyhash(w.encode("utf-8"))
    return T, (((0x9E3779B97F4A7C15 * k) & M) >> shift, ((0x517CC1B727220A95 * k) & M) >> shift)

def h_for(ctx):
    ctx = ctx[-L:]; ids = np.zeros((1, L), np.int64); hs = np.zeros((1, L, K), np.int64)
    for i, w in enumerate(ctx):
        ids[0, i], hs[0, i] = lookup(w)
    out = fwd.execute([torch.from_numpy(ids), torch.from_numpy(hs)])[0]
    return out.numpy()[0, max(0, len(ctx) - 1)]

def top3(scores, mask=None):
    s = scores.copy(); s[0] = -1e30
    if mask is not None: s[~mask] = -1e30
    return {low[i] for i in np.argpartition(-s, 3)[:3]}

t0 = time.time()
for path in sorted(glob.glob(os.path.join(D, "*.txt"))):
    name = os.path.basename(path)[:-4]
    if name in ("vocab", "en_tatoeba"): continue
    out = open(f"/kaggle/working/{name}.hits", "w")
    words = hit = long = hit1 = 0
    for sent in open(path, encoding="utf-8").read().splitlines()[:3000]:
        ctx = []; row = []
        for target in sent.split(" "):
            sc = emb @ h_for(ctx) + bias
            t = target.lower(); b = t in top3(sc); words += 1; hit += b
            a = "-"
            if len(target) >= 3:
                long += 1; a = t in top3(sc, first == ord(t[0])); hit1 += a; a = "1" if a else "0"
            row.append(("1" if b else "0") + a)
            ctx.append(target)
        out.write(" ".join(row) + "\n")
    print(f"{name:12s} before typing {100*hit/words:5.1f}%  after 1st letter {100*hit1/long:5.1f}%  ({words} words)  {time.time()-t0:.0f}s", flush=True)
