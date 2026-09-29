"""Inspect real headers before writing a column mapping; never guess column numbers."""
import argparse
from pathlib import Path
import pandas as pd
p=argparse.ArgumentParser(description=__doc__)
p.add_argument("file")
p.add_argument("--separator", default=None)
p.add_argument("--skiprows", type=int, default=0)
a=p.parse_args()
if Path(a.file).read_bytes()[:100].startswith(b"version https://git-lfs.github.com"):
    raise SystemExit("This is a Git LFS POINTER, not CSV data. Download the real Raw/LFS file.")
df=pd.read_csv(a.file,sep=a.separator,engine="python",skiprows=a.skiprows,nrows=6,encoding="utf-8-sig")
print("EXACT HEADERS:")
for i,c in enumerate(df.columns): print(f"{i:02d}: {c!r}")
print("\nFIRST ROWS:\n",df.to_string(index=False))
print("\nUse these exact headers in configs/column_map.template.json. Confirm units/axes against the source.")
