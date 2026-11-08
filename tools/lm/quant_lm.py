# Exports lm.pt (train_lm.py) to ONNX, quantizes it to int8, and checks the int8 model against the
# original on the held-out sets: same top-3 scoring as train_lm.py, paired word by word (McNemar).
import glob, math, os, subprocess, sys, time
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "onnx", "onnxruntime", "onnxscript"], check=True)
import numpy as np, torch, torch.nn as nn, torch.nn.functional as F, onnxruntime as ort
from onnxruntime.quantization import quantize_dynamic, QuantType

def find(name): return glob.glob(f"/kaggle/input/**/{name}", recursive=True)[0]
D = os.path.dirname(find("eval_tatoeba.txt"))
OUT = "/kaggle/working"
ck = torch.load(find("lm.pt"), map_location="cpu")
itos = ck["itos"]; stoi = {w: i for i, w in enumerate(itos)}
assert ck["arch"] == "transformer"
EMB = 256; HID = 1024; SEQ = 64; LAYERS = 4; HEADS = 4
dev = "cuda" if torch.cuda.is_available() else "cpu"  # the torch reference only; onnxruntime runs on CPU

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

model = TransformerLM()
model.load_state_dict({k: v.float() for k, v in ck["state"].items()}); model.eval()

class Export(nn.Module):
    """The same network with attention written out: nn.MultiheadAttention's export fixes the
    sequence length of the example input into its reshapes, so other lengths failed."""
    def __init__(s, m):
        super().__init__(); s.m = m
    def forward(s, x):
        m = s.m; n = x.shape[1]
        h = m.emb(x) + m.pos(torch.arange(n))
        for l in m.blocks.layers:
            y = l.norm1(h)
            q, k, v = (t.reshape(t.shape[0], -1, HEADS, EMB // HEADS).transpose(1, 2)
                       for t in F.linear(y, l.self_attn.in_proj_weight, l.self_attn.in_proj_bias).chunk(3, -1))
            o = F.scaled_dot_product_attention(q, k, v, is_causal=True).transpose(1, 2)
            h = h + l.self_attn.out_proj(o.reshape(o.shape[0], -1, EMB))
            h = h + l.linear2(F.gelu(l.linear1(l.norm2(h))))
        return m.norm(h) @ m.emb.weight.T + m.bias

exp = Export(model).eval()
with torch.no_grad():
    t = torch.randint(3, len(itos), (1, 11))
    d = (exp(t) - model(t)).abs().max().item(); print(f"hand-written vs original, max logit difference: {d:.2e}")
    assert d < 1e-3

fp32 = f"{OUT}/lm_fp32.onnx"; int8 = f"{OUT}/lm_int8.onnx"
torch.onnx.export(exp, (torch.tensor([[2, 5, 9]]),), fp32, input_names=["ids"], output_names=["logits"],
                  dynamic_axes={"ids": {1: "n"}, "logits": {1: "n"}}, opset_version=17, dynamo=False)
# Gather too, so the input word vectors are int8 as well as the output ones
quantize_dynamic(fp32, int8, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gather"])
for p in (fp32, int8): print(f"{os.path.basename(p)}: {os.path.getsize(p)/1e6:.1f} MB")
model.to(dev)  # only after export, which traced on CPU

opts = ort.SessionOptions(); opts.intra_op_num_threads = 1
sess = {name: ort.InferenceSession(p, opts, providers=["CPUExecutionProvider"]) for name, p in (("onnx fp32", fp32), ("int8", int8))}

# Same scoring as train_lm.py: top 3 before typing; top 3 among words with the typed first letter (3+ letters)
first = np.array([ord(w[0]) if i >= 3 else 0 for i, w in enumerate(itos)])
def top3(row): return set(np.argpartition(-row, 3)[:3].tolist())
def hits(logits, ws):
    logits = logits.copy(); logits[:, :3] = -1e9
    out = []
    for i, w in enumerate(ws):
        t = w.lower(); tid = stoi.get(t, -1)
        b = tid >= 0 and tid in top3(logits[i])
        a = None
        if len(t) >= 3: a = tid >= 0 and tid in top3(np.where(first == ord(t[0]), logits[i], -1e9))
        out.append((b, a))
    return out

def mcnemar(a, b):
    oa = sum(x and not y for x, y in zip(a, b)); ob = sum(y and not x for x, y in zip(a, b)); d = oa + ob
    return math.erfc(math.sqrt((max(abs(oa - ob) - 1, 0) ** 2 / d if d else 0) / 2))

print(f"{'set':12s} {'before: torch':>14s} {'int8':>6s}  diff  p       | {'after1: torch':>14s} {'int8':>6s}  diff  p")
sanity = []
for path in sorted(glob.glob(os.path.join(D, "eval_*.txt"))):
    name = os.path.basename(path)[5:-4]
    sents = [l for l in open(path, encoding="utf-8").read().splitlines() if l.strip()][:3000]
    ref, q = [], []
    with torch.no_grad():
        for k, s in enumerate(sents):
            ws = s.split(" ")
            ids = [[2] + [stoi.get(w.lower(), 1) for w in ws[:-1]]]
            lt = model(torch.tensor(ids, device=dev))[0].float().cpu().numpy()
            ref += hits(lt, ws)
            q += hits(sess["int8"].run(None, {"ids": np.array(ids, dtype=np.int64)})[0][0], ws)
            if k < 20:  # the fp32 export should match torch to rounding
                sanity.append(np.abs(sess["onnx fp32"].run(None, {"ids": np.array(ids, dtype=np.int64)})[0][0] - lt).max())
    rb = [r[0] for r in ref]; qb = [r[0] for r in q]
    idx = [i for i, r in enumerate(ref) if r[1] is not None]
    ra = [ref[i][1] for i in idx]; qa = [q[i][1] for i in idx]
    N, M = len(rb), len(idx)
    print(f"{name:12s} {100*sum(rb)/N:13.1f}% {100*sum(qb)/N:5.1f}% {100*(sum(qb)-sum(rb))/N:+5.2f} {mcnemar(rb, qb):.2g} | "
          f"{100*sum(ra)/M:13.1f}% {100*sum(qa)/M:5.1f}% {100*(sum(qa)-sum(ra))/M:+5.2f} {mcnemar(ra, qa):.2g}", flush=True)
print(f"onnx fp32 vs torch, max logit difference: {max(sanity):.2e}")

# One prediction with 16 words of context, one thread (re-runs the context: no saved state)
x = np.random.randint(3, len(itos), (1, 16)).astype(np.int64)
for name, s in sess.items():
    for i in range(220):
        if i == 20: tt = time.time()
        s.run(None, {"ids": x})
    print(f"{name}: {1000*(time.time()-tt)/200:.2f} ms per prediction on one CPU thread")
