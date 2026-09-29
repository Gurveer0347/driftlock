"""Make synthetic data ONLY to test software installation, training and export.

This toy signal intentionally encodes speed in vibration patterns. It does not
establish that those patterns generalize to real phones, roads or vehicles.
NEVER use its charts as SIH accuracy or feasibility evidence.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
import numpy as np
import pandas as pd
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from driftlock_ml.config import CONTRACT_FIELDS
from driftlock_ml.data import CANONICAL_UNITS
from driftlock_ml.rotations import GRAVITY_MPS2


def make_demo(root: Path, seconds=180, seed=9):
    root = Path(root)
    if root.exists() and any(root.iterdir()):
        raise FileExistsError(f"Synthetic outputs require a new empty directory: {root}")
    if seconds < 3:
        raise ValueError("At least three seconds are required for a synthetic full-window test.")
    root.mkdir(parents=True,exist_ok=True)
    rng=np.random.default_rng(seed)
    records=[]
    names=["train","train","train","val","calibration","test"]
    elapsed_s=np.arange(0,seconds,.1)
    for i,split in enumerate(names):
        t=elapsed_s
        v=9+7*np.sin(.045*t+i*.7)+2*np.sin(.21*t+.2*i)
        # A controlled early reverse segment exercises signed labels even in a
        # short smoke run; it is not inferred from an unsigned GNSS magnitude.
        v[:max(20, len(t)//5)] *= -1
        acceleration=np.gradient(v,.1)
        amplitude=.05+.05*np.abs(v)
        ax=amplitude*np.sin(2*np.pi*2.1*t)+rng.normal(0,.025,len(t))
        ay=acceleration+amplitude*np.cos(2*np.pi*1.3*t)+rng.normal(0,.04,len(t))
        az=GRAVITY_MPS2+amplitude*np.sin(2*np.pi*2.7*t)+rng.normal(0,.035,len(t))
        gx=.008*np.sin(.3*t)+rng.normal(0,.002,len(t))
        gy=.006*np.cos(.17*t)+rng.normal(0,.002,len(t))
        gz=.035*np.sin(.1*t)+rng.normal(0,.003,len(t))
        boot_origin_s=3600.+1000*i
        frame=pd.DataFrame({"timestamp_s":boot_origin_s+t,"ax":ax,"ay":ay,"az":az,"gx":gx,"gy":gy,"gz":gz,
                            "speed_mps":v,"label_valid":np.ones(len(t),dtype=int)})
        name=f"SYNTHETIC_drive_{i+1}.csv"
        frame.to_csv(root/name,index=False)
        records.append({"path":name,"split":split,"group_id":f"synthetic_group_{i+1}",
                        "device_id":"SIMULATED_NOT_A_PHONE","label_source":"synthetic_equation",
                        "label_semantics":"vehicle_forward_signed",
                        "label_semantics_evidence":"Controlled synthetic vehicle-x equation includes negative reverse speed.",
                        "time_base_evidence":f"Controlled synthetic boot clock starts at {boot_origin_s} s; no real device claim."})
    manifest={**CONTRACT_FIELDS, **CANONICAL_UNITS,
              "source_type":"synthetic","frame":"phone","acceleration_includes_gravity":True,
              "review_status":"SYNTHETIC_PIPELINE_TEST_ONLY","drives":records}
    path=root/"manifest.json"
    path.write_text(json.dumps(manifest,indent=2))
    return path

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--out",default="data/smoke")
    p.add_argument("--seconds",type=int,default=180)
    a=p.parse_args()
    print(make_demo(Path(a.out),a.seconds))
    print("SYNTHETIC SOFTWARE TEST ONLY. Not a DRIFTLOCK performance result.")
