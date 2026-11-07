# Blends the neural model (lm.pt from train_lm.py) with the 3-gram (Evaluate --lm-dump files):
# P = λ·P_neural + (1−λ)·P_3gram per word, top 3 before typing and after the first letter.
# λ is picked on even-numbered sentences and reported on odd ones, paired against each model alone.
import glob, math, os
import numpy as np, torch, torch.nn as nn

def find(name): return glob.glob(f"/kaggle/input/**/{name}", recursive=True)[0]
DUMP = os.path.dirname(find("unigram.tsv"))
ck = torch.load(find("lm.pt"), map_location="cpu")
itos = ck["itos"]; stoi = {w: i for i, w in enumerate(itos)}; ARCH = ck.get("arch", "lstm")
EMB = 256; HID = 1024; SEQ = 64; LAYERS = 4; HEADS = 4
dev = "cuda" if torch.cuda.is_available() else "cpu"

class LM(nn.Module):
    def __init__(s):
        super().__init__()
        s.emb = nn.Embedding(len(itos), EMB)
        s.lstm = nn.LSTM(EMB, HID, batch_first=True, proj_size=EMB)
        s.drop = nn.Dropout(0.1)
        s.bias = nn.Parameter(torch.zeros(len(itos)))
    def forward(s, x):
        h, _ = s.lstm(s.emb(x))
        return h @ s.emb.weight.T + s.bias

class TransformerLM(nn.Module):
    def __init__(s):
        super().__init__()
        s.emb = nn.Embedding(len(itos), EMB)
        s.pos = nn.Embedding(SEQ, EMB)
        layer = nn.TransformerEncoderLayer(EMB, HEADS, HID, dropout=0.1, batch_first=True, norm_first=True, activation="gelu")
        s.blocks = nn.TransformerEncoder(layer, LAYERS, enable_nested_tensor=False)
        s.norm = nn.LayerNorm(EMB)
        s.drop = nn.Dropout(0.1)
        s.bias = nn.Parameter(torch.zeros(len(itos)))
    def forward(s, x):
        x = x[:, -SEQ:]; n = x.shape[1]
        h = s.emb(x) + s.pos(torch.arange(n, device=x.device))
        h = s.norm(s.blocks(h, mask=nn.Transformer.generate_square_subsequent_mask(n, device=x.device), is_causal=True))
        return h @ s.emb.weight.T + s.bias

model = (TransformerLM if ARCH == "transformer" else LM)()
model.load_state_dict({k: v.float() for k, v in ck["state"].items()}); model.to(dev).eval()
print("arch", ARCH, "vocab", len(itos))

# 3-gram unigram per lower-case word (the larger of its spellings), and vocab-aligned arrays
uni = {}
for line in open(os.path.join(DUMP, "unigram.tsv"), encoding="utf-8"):
    w, lp = line.rstrip("\n").split("\t"); w = w.lower(); lp = float(lp)
    if lp > uni.get(w, -1e9): uni[w] = lp
nn_uni = np.array([uni.get(w, -np.inf) if i >= 3 else -np.inf for i, w in enumerate(itos)])
first = np.array([w[0] if i >= 3 else "" for i, w in enumerate(itos)])
# Words the 3-gram knows but the neural vocab doesn't: by first letter, the most frequent few
extra = {}
for w, lp in sorted(uni.items(), key=lambda kv: -kv[1]):
    if w not in stoi and len(extra.setdefault(w[0], [])) < 50: extra[w[0]].append((w, lp))
LAMBDAS = np.round(np.arange(0, 1.01, 0.1), 2)

def positions(name):
    """Yields (sentence index, target, 3-gram candidates {word: logp}, backoff) per word."""
    sent = 0
    for line in open(os.path.join(DUMP, f"{name}.lm"), encoding="utf-8"):
        line = line.rstrip("\n")
        if not line: sent += 1; continue
        parts = line.split("\t")
        cands = {}
        for p in parts[2:]:
            w, lp = p.rsplit(" ", 1); w = w.lower(); lp = float(lp)
            if lp > cands.get(w, -1e9): cands[w] = lp
        yield sent, parts[0], cands, float(parts[1])

def top3(words, logp):
    if len(words) <= 3: return set(words)
    return {words[i] for i in np.argpartition(-logp, 3)[:3]}

def evaluate(name):
    """Hits per λ for each word: before[λ], after1[λ] (None under 3 letters), with sentence index."""
    rows = list(positions(name))
    sents = {}
    for s, t, c, b in rows: sents.setdefault(s, []).append(t)
    out = []
    with torch.no_grad():
        for s, words in sents.items():
            x = torch.tensor([[2] + [stoi.get(w.lower(), 1) for w in words[:-1]]], device=dev)
            lp = torch.log_softmax(model(x)[0].float(), -1).cpu().numpy()
            lp[:, :3] = -np.inf
            out.append(lp)
    res = []; k = 0
    for si, (s, words) in enumerate(sents.items()):
        lp_all = out[si]
        for i, t in enumerate(words):
            _, _, cands, backoff = rows[k]; k += 1
            t = t.lower(); lpn = lp_all[i]
            def score(mask_letter):
                # Pool: 3-gram candidates, neural top 300, and the 3-gram's top unigram words (by letter when filtered)
                sel = lpn if mask_letter is None else np.where(first == mask_letter, lpn, -np.inf)
                pool_ids = np.argpartition(-sel, 300)[:300]
                pool = {itos[j] for j in pool_ids if np.isfinite(sel[j])}
                pool |= {w for w in cands if mask_letter is None or w[:1] == mask_letter}
                g3 = nn_uni + backoff
                g3sel = g3 if mask_letter is None else np.where(first == mask_letter, g3, -np.inf)
                pool |= {itos[j] for j in np.argpartition(-g3sel, 300)[:300] if np.isfinite(g3sel[j])}
                for w, u in extra.get(mask_letter, []) if mask_letter else []: pool.add(w)
                ws = list(pool)
                pn = np.array([math.exp(lpn[stoi[w]]) if w in stoi else 0.0 for w in ws])
                p3 = np.array([math.exp(cands[w]) if w in cands else (math.exp(uni[w] + backoff) if w in uni else 0.0) for w in ws])
                return [t in top3(ws, np.log(l * pn + (1 - l) * p3 + 1e-30)) for l in LAMBDAS]
            res.append((s, score(None), score(t[0]) if len(t) >= 3 else None))
    return res

def mcnemar(a, b):
    oa = sum(x and not y for x, y in zip(a, b)); ob = sum(y and not x for x, y in zip(a, b))
    d = oa + ob; chi = max(abs(oa - ob) - 1, 0) ** 2 / d if d else 0
    return math.erfc(math.sqrt(chi / 2))

names = ["tatoeba", "oanc", "soda", "dailydialog", "empathetic", "personachat", "bst", "hinglish"]
results = {n: evaluate(n) for n in names}
# Check: λ=0 before typing should match the 3-gram's own predict() hits
print("3-gram alone, before typing, per set:", {n: round(100 * np.mean([r[1][0] for r in results[n]]), 1) for n in names})

for k, label in ((1, "before typing"), (2, "after 1st letter")):
    tune = np.mean([np.mean([r[k] for r in results[n] if r[0] % 2 == 0 and r[k] is not None], axis=0) for n in names], axis=0)
    best = int(np.argmax(tune)); lam = LAMBDAS[best]
    print(f"\n== {label}: λ tuned on even sentences = {lam}  (mean over sets by λ: {' '.join(f'{x:.3f}' for x in tune)})")
    print(f"{'set':12s} {'3-gram':>7s} {'neural':>7s} {'blend':>7s}  vs 3-gram       vs neural")
    for n in names:
        rows = [r[k] for r in results[n] if r[0] % 2 == 1 and r[k] is not None]
        g = [r[0] for r in rows]; nn_ = [r[-1] for r in rows]; bl = [r[best] for r in rows]
        N = len(rows)
        print(f"{n:12s} {100*sum(g)/N:6.1f}% {100*sum(nn_)/N:6.1f}% {100*sum(bl)/N:6.1f}%  {100*(sum(bl)-sum(g))/N:+5.1f} p={mcnemar(g, bl):.2g}  {100*(sum(bl)-sum(nn_))/N:+5.1f} p={mcnemar(nn_, bl):.2g}")
