"""One utterance per line, gzipped: SODA dialogues and OANC spoken transcripts.

Usage: python convert_corpora.py <tools/data dir>   (needs pyarrow; run by fetch-data.sh)
"""
import gzip, re, sys, zipfile
from pathlib import Path

data = Path(sys.argv[1])

# SODA: every utterance of every dialogue, all three splits (the builder sets its own test lines aside)
import pyarrow.parquet as pq
n = 0
with gzip.open(data / "soda.txt.gz", "wt", encoding="utf-8", compresslevel=6) as out:
    for split in ("train", "valid", "test"):
        f = pq.ParquetFile(data / "soda" / f"{split}.parquet")
        for i in range(f.num_row_groups):
            for dialogue in f.read_row_group(i, columns=["dialogue"]).column("dialogue").to_pylist():
                for utterance in dialogue:
                    line = " ".join(utterance.split())
                    if line:
                        out.write(line + "\n"); n += 1
print(f"soda: {n:,} utterances")

# OANC spoken (Switchboard phone calls, Charlotte interviews): fillers and stutters out
FILLERS = re.compile(r"\b(?:um+|uh+|er+|ah+|hm+|mhm|mm+|uh-huh|huh)\b[,.]?", re.I)
STUTTER = re.compile(r"\b(\w+(?:'\w+)?)(?:\s+\1\b)+", re.I)
n = 0
with zipfile.ZipFile(data / "oanc.zip") as z, gzip.open(data / "oanc.txt.gz", "wt", encoding="utf-8") as out:
    for name in z.namelist():
        if not name.endswith(".txt") or not ("/switchboard/" in name or "/charlotte/" in name):
            continue
        for raw in z.read(name).decode("utf-8", "replace").splitlines():
            line = FILLERS.sub(" ", raw.replace("--", " "))
            line = STUTTER.sub(r"\1", " ".join(line.split()))
            line = " ".join(line.split()).strip(" ,")
            if len(line.split()) >= 2:
                out.write(line + "\n"); n += 1
print(f"oanc: {n:,} utterances")
