"""Conversation corpora to one utterance per line, gzipped: <name>.txt.gz in the data dir.

Usage: python3 convert_corpora.py <tools/data dir> [source ...]   (default: all; run by fetch-data.sh)
Sources and the raw files they read from the data dir:
  soda      soda/{train,valid,test}.parquet   (needs pyarrow)
  oanc      oanc.zip
  dailydialog  dailydialog.zip                (ConvLab's copy: data/dialogues.json)
  empathetic   empatheticdialogues.tar.gz
  personachat  personachat.tgz
  bst          blended_skill_talk.tar.gz
"""
import csv, gzip, io, json, re, sys, tarfile, zipfile
from pathlib import Path

data = Path(sys.argv[1])


def write(name, utterances):
    n = 0
    with gzip.open(data / f"{name}.txt.gz", "wt", encoding="utf-8", compresslevel=6) as out:
        for u in utterances:
            line = " ".join(u.split())
            if line:
                out.write(line + "\n"); n += 1
    print(f"{name}: {n:,} utterances")


def soda():
    # Every utterance of every dialogue, all three splits (the builder sets its own test lines aside)
    import pyarrow.parquet as pq
    for split in ("train", "valid", "test"):
        f = pq.ParquetFile(data / "soda" / f"{split}.parquet")
        for i in range(f.num_row_groups):
            for dialogue in f.read_row_group(i, columns=["dialogue"]).column("dialogue").to_pylist():
                yield from dialogue


# OANC spoken (Switchboard phone calls, Charlotte interviews): fillers and stutters out
FILLERS = re.compile(r"\b(?:um+|uh+|er+|ah+|hm+|mhm|mm+|uh-huh|huh)\b[,.]?", re.I)
STUTTER = re.compile(r"\b(\w+(?:'\w+)?)(?:\s+\1\b)+", re.I)


def oanc():
    with zipfile.ZipFile(data / "oanc.zip") as z:
        for name in z.namelist():
            if not name.endswith(".txt") or not ("/switchboard/" in name or "/charlotte/" in name):
                continue
            for raw in z.read(name).decode("utf-8", "replace").splitlines():
                line = FILLERS.sub(" ", raw.replace("--", " "))
                line = STUTTER.sub(r"\1", " ".join(line.split()))
                line = " ".join(line.split()).strip(" ,")
                if len(line.split()) >= 2:
                    yield line


def dailydialog():
    with zipfile.ZipFile(data / "dailydialog.zip") as z:
        for d in json.loads(z.read("data/dialogues.json")):
            for t in d["turns"]:
                yield t["utterance"]


def empathetic():
    with tarfile.open(data / "empatheticdialogues.tar.gz") as t:
        for split in ("train", "valid", "test"):
            f = io.TextIOWrapper(t.extractfile(f"empatheticdialogues/{split}.csv"), encoding="utf-8")
            # A few rows have stray commas past the last column; the utterance is the 6th field
            for row in csv.reader(f):
                if len(row) > 5 and row[0] != "conv_id":
                    yield row[5].replace("_comma_", ",")


# Tokenised text ("i do n't know , really") back to how it is typed
DETOKENIZE = [(re.compile(r" ([,.!?;:)])"), r"\1"), (re.compile(r"\( "), "("),
              (re.compile(r" (n't|'s|'re|'m|'ve|'ll|'d)\b"), r"\1")]


def detokenize(s):
    for pattern, repl in DETOKENIZE:
        s = pattern.sub(repl, s)
    return s


def personachat():
    with tarfile.open(data / "personachat.tgz") as t:
        for split in ("train", "valid", "test"):
            # The "none" files have no persona lines: each is "<n> <utterance>\t<reply>\t\t<candidates>"
            for line in io.TextIOWrapper(t.extractfile(f"personachat/{split}_none_original.txt"), encoding="utf-8"):
                parts = line.rstrip("\n").split("\t")
                if len(parts) >= 2:
                    yield detokenize(parts[0].split(" ", 1)[1])
                    yield detokenize(parts[1])


def bst():
    with tarfile.open(data / "blended_skill_talk.tar.gz") as t:
        for split in ("train", "valid", "test"):
            for d in json.load(t.extractfile(f"{split}.json")):
                yield d["free_turker_utterance"]
                yield d["guided_turker_utterance"]
                for _, text in d["dialog"]:
                    yield text


SOURCES = {f.__name__: f for f in (soda, oanc, dailydialog, empathetic, personachat, bst)}
for name in sys.argv[2:] or SOURCES:
    write(name, SOURCES[name]())
