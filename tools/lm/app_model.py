# Prepares the app's next-word model from the Kaggle outputs, without torch:
#   lm_int8.onnx (quant_lm.py) -> en-lm.onnx scoring only the last position (the next word), not every one
#   lm.pt (train_lm.py)        -> en-lm.vocab, one word per line in id order
# Usage: uv run --with onnx --with onnxruntime --with numpy python tools/lm/app_model.py lm_int8.onnx lm.pt OUT_DIR
import pickle, sys, zipfile
import numpy as np, onnx, onnxruntime as ort
from onnx import helper, numpy_helper

src, pt, out = sys.argv[1:4]

m = onnx.load(src)
g = m.graph
# The last LayerNorm's output feeds only the output projection: take its last row before that
norm = [n for n in g.node if n.op_type == "LayerNormalization"][-1]
x = norm.output[0]
for n in g.node:
    for i, name in enumerate(n.input):
        if name == x: n.input[i] = x + "_last"
g.initializer.extend([numpy_helper.from_array(np.array(a, dtype=np.int64), name) for a, name in
                      (([-1], "last_start"), ([2**62], "last_end"), ([1], "last_axis"))])
g.node.insert(list(g.node).index(norm) + 1, helper.make_node("Slice", [x, "last_start", "last_end", "last_axis"], [x + "_last"]))
g.output[0].type.tensor_type.shape.dim[1].dim_value = 1

# The word vectors are stored twice, as the input lookup table (uint8) and as the output layer's
# weights (int8, transposed). Keep the output copy only: the lookup reads its columns instead.
inits = {i.name: i for i in g.initializer}
def shape(name): return tuple(inits[name].dims) if name in inits else ()
# The lookup table is the big [vocab, dim] one; the output weights are the [dim, vocab] one
lookup = max((n for n in g.node if n.op_type == "Gather" and len(shape(n.input[0])) == 2), key=lambda n: shape(n.input[0])[0])
vocab_size, dim = shape(lookup.input[0])
deq = next(n for n in g.node if n.op_type == "DequantizeLinear" and n.input[0] == lookup.output[0])
out_w = next(n.input[1] for n in g.node if n.op_type == "MatMulInteger" and shape(n.input[1]) == (dim, vocab_size))
base = out_w[: -len("_quantized")]
assert base + "_scale" in inits and base + "_zero_point" in inits
cols = lookup.output[0] + "_cols"
# Gather on axis 1 of [dim, vocab] gives [dim, batch, n]; the network wants [batch, n, dim]
g.node.insert(list(g.node).index(lookup), helper.make_node("Gather", [out_w, lookup.input[1]], [cols], axis=1))
g.node.insert(list(g.node).index(lookup), helper.make_node("Transpose", [cols], [lookup.output[0]], perm=[1, 2, 0]))
for name in (lookup.input[0], deq.input[1], deq.input[2]): g.initializer.remove(inits[name])
deq.input[1], deq.input[2] = base + "_scale", base + "_zero_point"
g.node.remove(lookup)
onnx.checker.check_model(m)
onnx.save(m, f"{out}/en-lm.onnx")

# lm.pt is a torch zip; its pickle names torch classes for the weights, which we don't need
class Stub:
    def __init__(self, *a, **k): pass
    def __call__(self, *a, **k): return None
    def __setstate__(self, s): pass
class NoTorch(pickle.Unpickler):
    def find_class(self, module, name):
        return Stub() if module.startswith("torch") else super().find_class(module, name)
    def persistent_load(self, pid): return None
with zipfile.ZipFile(pt) as z:
    data = next(n for n in z.namelist() if n.endswith("data.pkl"))
    itos = NoTorch(z.open(data)).load()["itos"]
with open(f"{out}/en-lm.vocab", "w", encoding="utf-8") as f:
    f.write("\n".join(itos) + "\n")

# Check: the trimmed model predicts what the full one's last row does. Not to the digit: int8 sizes
# its rounding to the activations it sees, and one row's range differs from five rows'
ids = np.array([[2, 15, 300, 7, 42]], dtype=np.int64)
full = ort.InferenceSession(src).run(None, {"ids": ids})[0][0, -1]
last = ort.InferenceSession(f"{out}/en-lm.onnx").run(None, {"ids": ids})[0]
assert last.shape == (1, 1, len(itos)), last.shape
top_full, top = np.argsort(-full)[:10], np.argsort(-last[0, 0])[:10]
print(f"max score difference {np.abs(full - last[0, 0]).max():.3f}, top-10 overlap {len(set(top_full) & set(top))}/10")
assert len(set(top_full[:3]) & set(top)) == 3
print("vocab", len(itos), "| after", [itos[i] for i in ids[0]], "->", [itos[i] for i in top[:5]])
