# Word-level next-word model (LSTM or small transformer, ARCH below), trained on exactly the lines
# the 3-gram pack counts (same hold-out rules as BuildLexicon), then scored per word on the same held-out sets.
import glob, gzip, os, re, time, math, random
from array import array
from collections import Counter
os.environ.setdefault("PYTORCH_CUDA_ALLOC_CONF", "expandable_segments:True")  # less fragmentation
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F

D = os.path.dirname(glob.glob("/kaggle/input/**/eval_tatoeba.txt", recursive=True)[0])
print(sorted(os.listdir(D)))
OUT = "/kaggle/working"
ARCH = "transformer"  # or "lstm"
V = int(os.environ.get("VOCAB", 50000)); EMB = 256; HID = 1024; SEQ = 64; BATCH = 256; EPOCHS = 3
LAYERS = 4; HEADS = 4; ACCUM = 2
HOLD_OUT = 50
dev = "cuda" if torch.cuda.is_available() else "cpu"
print("device", dev, torch.cuda.get_device_name(0) if dev == "cuda" else "")
t0 = time.time()
def log(*a): print(f"[{time.time()-t0:6.0f}s]", *a, flush=True)

TOKEN = re.compile(r"[^\W_]+(?:['’][^\W\d_]+)*|[.!?]|[,;:\"()]")
ANCHORS = set("hai hain nahi nhi kya ka ki ke ko se bhi tha thi ye yeh wo woh aur mein mai kar karo raha rahe rha rhe hu hoon tum aap mujhe kuch kyu kyun toh bhai yaar sab koi ek hota hoga gaya".split())

def tokens(text):
    out = ["<s>"]
    for m in TOKEN.finditer(text):
        t = m.group().replace("’", "'")
        if t in ".!?":
            if out[-1] != "<s>": out.append("<s>")
        elif t[0].isalnum():
            out.append(t.lower())
    if len(out) > 1 and out[-1] == "<s>": out.pop()
    return out

def opengz(name):
    # Kaggle may have unpacked the .gz on upload
    p = os.path.join(D, name)
    if os.path.exists(p): return gzip.open(p, "rt", encoding="utf-8", errors="replace")
    p = p[:-3]
    if os.path.isdir(p): p = glob.glob(os.path.join(p, "**", "*"), recursive=True)
    if isinstance(p, list): p = [f for f in p if os.path.isfile(f)][0]
    return open(p, encoding="utf-8", errors="replace")

def lines():
    with opengz("tatoeba.tsv.gz") as f:
        for line in f:
            line = line.rstrip("\n"); head = line.split("\t", 1)[0]
            if (int(head) if head.isdigit() else 0) % HOLD_OUT == 0: continue
            yield line.rsplit("\t", 1)[-1]
    for name in ("oanc", "soda", "dailydialog", "empathetic", "personachat", "bst"):
        with opengz(f"{name}.txt.gz") as f:
            for n, line in enumerate(f):
                if n >= 2_000_000: break
                if n % HOLD_OUT == 0: continue
                yield line
    hing = 0
    with opengz("hing3m.txt.gz") as f:
        for line in f:
            ws = [m.group().replace("’", "'") for m in TOKEN.finditer(line)]
            if sum(1 for w in ws if w[0].isalnum() and w.lower() in ANCHORS) < 2: continue
            hing += 1
            if hing % HOLD_OUT == 0: continue
            yield line

counts = Counter()
nlines = 0
for line in lines():
    counts.update(tokens(line)); nlines += 1
log("lines", nlines, "tokens", sum(counts.values()), "types", len(counts))
special = ["<pad>", "<unk>", "<s>"]
itos = special + [w for w, _ in counts.most_common() if w != "<s>"][:V - 3]
stoi = {w: i for i, w in enumerate(itos)}
cover = sum(counts[w] for w in itos[3:]) / (sum(counts.values()) - counts["<s>"])
log("vocab", len(itos), f"token coverage {100*cover:.2f}%")
del counts

ids = array("i")
for line in lines():
    ids.extend(stoi.get(t, 1) for t in tokens(line))
data = torch.from_numpy(np.frombuffer(ids, dtype=np.int32).astype(np.int64)); del ids
nwin = (len(data) - 1) // SEQ
log("train tokens", len(data), "windows", nwin)

class LM(nn.Module):
    def __init__(s):
        super().__init__()
        s.emb = nn.Embedding(len(itos), EMB)
        s.lstm = nn.LSTM(EMB, HID, batch_first=True, proj_size=EMB)
        s.drop = nn.Dropout(0.1)
        s.bias = nn.Parameter(torch.zeros(len(itos)))
    def forward(s, x):
        h, _ = s.lstm(s.drop(s.emb(x)))
        return s.drop(h) @ s.emb.weight.T + s.bias

class TransformerLM(nn.Module):
    """Pre-norm causal transformer, same tied word vectors and output bias as the LSTM."""
    def __init__(s):
        super().__init__()
        s.emb = nn.Embedding(len(itos), EMB)
        s.pos = nn.Embedding(SEQ, EMB)
        layer = nn.TransformerEncoderLayer(EMB, HEADS, HID, dropout=0.1, batch_first=True, norm_first=True, activation="gelu")
        s.blocks = nn.TransformerEncoder(layer, LAYERS, enable_nested_tensor=False)
        s.norm = nn.LayerNorm(EMB)
        s.drop = nn.Dropout(0.1)
        s.bias = nn.Parameter(torch.zeros(len(itos)))
        # The default N(0, 1) word vectors, scored against a layer-normed output, start with logits
        # in the tens and the loss diverged; small ones as in GPT-2
        nn.init.normal_(s.emb.weight, std=0.02); nn.init.normal_(s.pos.weight, std=0.02)
    def forward(s, x):
        x = x[:, -SEQ:]
        n = x.shape[1]
        h = s.drop(s.emb(x) + s.pos(torch.arange(n, device=x.device)))
        mask = nn.Transformer.generate_square_subsequent_mask(n, device=x.device)
        h = s.norm(s.blocks(h, mask=mask, is_causal=True))
        return s.drop(h) @ s.emb.weight.T + s.bias

Model = TransformerLM if ARCH == "transformer" else LM
model = Model().to(dev)
log("arch", ARCH)
log("params", sum(p.numel() for p in model.parameters()))
LR = 1e-3 if ARCH == "transformer" else 2e-3
opt = torch.optim.AdamW(model.parameters(), lr=LR, weight_decay=0.01)
steps = EPOCHS * (nwin // BATCH)
sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=LR, total_steps=steps, pct_start=0.03)
scaler = torch.amp.GradScaler()
step = 0; recent = []
for epoch in range(EPOCHS):
    model.train()
    perm = torch.randperm(nwin)
    for b in range(nwin // BATCH):
        starts = perm[b*BATCH:(b+1)*BATCH] * SEQ
        idx = starts[:, None] + torch.arange(SEQ + 1)
        chunk = data[idx].to(dev, non_blocking=True)
        opt.zero_grad(set_to_none=True)
        # Half-batches with summed gradients: the same update, at half the peak memory (the
        # transformer ran out of memory on a T4 with the whole batch at once)
        loss = 0.0
        for part in chunk.chunk(ACCUM):
            with torch.autocast(dev, dtype=torch.float16):
                logits = model(part[:, :-1])
                l = F.cross_entropy(logits.float().view(-1, len(itos)), part[:, 1:].reshape(-1), ignore_index=0) / ACCUM
            scaler.scale(l).backward()
            loss += l.detach()
            del logits, l
        scaler.unscale_(opt); nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        scaler.step(opt); scaler.update(); sched.step(); step += 1
        recent.append(loss.item())  # shortcut: syncs the GPU each step; costs a little speed
        if step % 500 == 0:
            avg = sum(recent) / len(recent); recent = []
            log(f"epoch {epoch} step {step}/{steps} loss {avg:.3f} ppl {math.exp(avg):.0f} (mean of last 500)")
    torch.save({"arch": ARCH, "itos": itos, "state": {k: v.half() for k, v in model.state_dict().items()}}, f"{OUT}/lm.pt")

# Per-word hits in Evaluate --hits format: before typing (1/0), after first letter (1/0, "-" under 3 letters)
model.eval()
first = torch.tensor([ord(w[0]) if i >= 3 else 0 for i, w in enumerate(itos)], device=dev)
for path in sorted(glob.glob(os.path.join(D, "eval_*.txt"))):
    name = os.path.basename(path)[5:-4]
    sents = [l for l in open(path, encoding="utf-8").read().splitlines() if l.strip()][:3000]
    hit = words = hit1 = long = 0
    with open(f"{OUT}/{name}.hits", "w") as out, torch.no_grad():
        for s in sents:
            ws = s.split(" ")
            x = torch.tensor([[2] + [stoi.get(w.lower(), 1) for w in ws[:-1]]], device=dev)
            logits = model(x)[0].float(); logits[:, :3] = -1e9
            row = []
            for i, w in enumerate(ws):
                t = w.lower(); tid = stoi.get(t, -1)
                b = tid >= 0 and tid in logits[i].topk(3).indices.tolist()
                a = "-"
                if len(t) >= 3:
                    m = logits[i].masked_fill(first != ord(t[0]), -1e9)
                    ok = tid >= 0 and tid in m.topk(3).indices.tolist(); hit1 += ok; long += 1; a = "1" if ok else "0"
                hit += b; words += 1; row.append(("1" if b else "0") + a)
            out.write(" ".join(row) + "\n")
    log(f"{name:12s} before typing {100*hit/words:5.1f}%  after 1st letter {100*hit1/long:5.1f}%  ({words} words)")

# One word step on one CPU thread, as a rough phone proxy (a phone core is slower)
torch.set_num_threads(1)
cpu = Model(); cpu.load_state_dict(model.state_dict()); cpu.eval()
with torch.no_grad():
    x = torch.tensor([[5]]); h = None
    ctx = torch.randint(3, len(itos), (1, 16))
    for i in range(220):
        if i == 20: tt = time.time()
        if ARCH == "lstm":
            o, h = cpu.lstm(cpu.emb(x), h); (o @ cpu.emb.weight.T + cpu.bias).topk(3)
        else:
            # shortcut: re-runs all 16 context words (no saved state), so an upper bound on the per-word cost
            cpu(ctx)[0, -1].topk(3)
log(f"cpu per word {1000*(time.time()-tt)/200:.2f} ms")
