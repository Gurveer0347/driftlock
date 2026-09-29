"""Download explicitly selected original S-dataset CSV files, not the entire archive.

Source: the authors' public onyekpeu/IO-VNBD repository. Network access required.
This is not a licence grant; review source terms before redistributing datasets.
"""
from __future__ import annotations
import argparse
from pathlib import Path
import urllib.request
from urllib.parse import quote

BASE="https://media.githubusercontent.com/media/onyekpeu/IO-VNBD/master/"
FOLDER="Unsynchronised V and S Dataset/Uncategorised IOVNB (V and S) Dataset/S-Dataset/"


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--files",nargs="+",required=True,help="Exact names from the authors' S-Dataset directory, e.g. S-A1.csv")
    p.add_argument("--out",default="data/raw")
    a=p.parse_args();out=Path(a.out);out.mkdir(parents=True,exist_ok=True)
    for name in a.files:
        if Path(name).name!=name or not name.startswith("S-") or not name.endswith(".csv"):
            raise ValueError(f"Not a valid S-dataset filename: {name}")
        dest=out/name
        if dest.exists():
            print(f"Exists, not overwriting: {dest}");continue
        request=urllib.request.Request(BASE+quote(FOLDER+name,safe="/"),headers={"User-Agent":"DRIFTLOCK-research-downloader/0.1"})
        tmp=dest.with_suffix(".download")
        try:
            with urllib.request.urlopen(request,timeout=60) as response, tmp.open("wb") as f:
                while chunk:=response.read(1024*1024): f.write(chunk)
            beginning=tmp.read_bytes()[:200]
            if beginning.startswith(b"version https://git-lfs.github.com") or b"<html" in beginning.lower():
                raise ValueError("Server returned a pointer or HTML, not real data.")
            tmp.replace(dest)
            print(f"Downloaded {dest} ({dest.stat().st_size:,} bytes)")
        except Exception:
            tmp.unlink(missing_ok=True)
            raise
    print("Next: inspect headers. Data download is NOT a reviewed training split.")

if __name__=="__main__":main()
