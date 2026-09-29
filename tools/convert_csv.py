"""Convert an explicitly mapped raw log to the canonical CSV. No automatic axis guesses."""
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
from driftlock_ml.data import (
    CANONICAL_UNITS, reject_non_csv_payload, validate_canonical_frame,
    validate_data_metadata, validate_label_metadata,
    data_time_base,
)
from driftlock_ml.rotations import GRAVITY_MPS2
from driftlock_ml.utils import sha256


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input",required=True)
    p.add_argument("--mapping",required=True)
    p.add_argument("--output",required=True)
    p.add_argument("--assume-valid-labels",action="store_true",
                   help="Exploratory runs ONLY: bypass validity column; logged as unreviewed.")
    a=p.parse_args()
    source=Path(a.input)
    reject_non_csv_payload(source)
    m=json.loads(Path(a.mapping).read_text())
    validate_data_metadata(m, canonical_units=False)
    validate_label_metadata(m)
    unit_factors={
        "time_unit":{"s":1.,"ms":.001,"us":1e-6,"ns":1e-9},
        "acceleration_unit":{"m/s2":1.,"g":GRAVITY_MPS2},
        "gyro_unit":{"rad/s":1.,"deg/s":np.pi/180},
        "speed_unit":{"m/s":1.,"km/h":1/3.6},
    }
    factors={}
    for unit_field, supported in unit_factors.items():
        unit=m.get(unit_field)
        if unit not in supported:
            raise ValueError(f"Unsupported source unit {unit_field}={unit!r}. "
                             f"Verify source documentation and choose one of {sorted(supported)}.")
        factors[unit_field]=supported[unit]
    dest=Path(a.output)
    audit_path=dest.with_suffix(".import.json")
    for path in (dest, audit_path):
        if path.exists():
            raise FileExistsError(path)
    frame=pd.read_csv(source,sep=m.get("separator"),engine="python",skiprows=m.get("skiprows",0),encoding="utf-8-sig")
    out={}
    for target in ["timestamp_s","ax","ay","az","gx","gy","gz","speed_mps"]:
        column=m["columns"].get(target)
        if column not in frame.columns:
            raise ValueError(f"Missing exact header for {target}: {column!r}. Run inspect_csv.py.")
        out[target]=pd.to_numeric(frame[column],errors="raise").to_numpy(dtype=float)
    out["timestamp_s"]*=factors["time_unit"]
    for k in ["ax","ay","az"]: out[k]*=factors["acceleration_unit"]
    for k in ["gx","gy","gz"]: out[k]*=factors["gyro_unit"]
    out["speed_mps"]*=factors["speed_unit"]
    valid_header=m["columns"].get("label_valid")
    if valid_header in frame.columns:
        valid=pd.to_numeric(frame[valid_header],errors="raise").to_numpy()
        if not np.isin(valid,[0,1]).all(): raise ValueError("label_valid must be reviewed binary 0/1 values.")
        validity="provided_validity_mask"
    elif a.assume_valid_labels:
        valid=np.ones(len(frame),dtype=int)
        validity="UNREVIEWED_LABELS_EXPLORATORY_ONLY"
    else:
        raise ValueError("Need label_valid mask from Avi excluding outages/stale fixes; unsigned GPS requires verified forward-only intervals. For exploratory import ONLY use --assume-valid-labels.")
    valid=(valid==1)&np.isfinite(out["speed_mps"])
    out["label_valid"]=valid.astype(int)
    canonical = pd.DataFrame(out)
    t, _, _, _ = validate_canonical_frame(canonical, m, name=source.name)
    dest.parent.mkdir(parents=True,exist_ok=True)
    canonical.to_csv(dest,index=False)
    audit={**CONTRACT_FIELDS, **CANONICAL_UNITS,
           "data_time_base":data_time_base(m),
           **{key:m[key] for key in ("sensor_reference_clock", "sensor_reference_clock_evidence") if key in m},
           "frame":"phone", "acceleration_includes_gravity":True,
           "label_semantics":m["label_semantics"],
           "label_semantics_evidence":m["label_semantics_evidence"],
           "time_base_evidence":m["time_base_evidence"],
           "review_status":"REQUIRES_AVI_REVIEW_OF_GROUPS_ROUTES_LABELS_AXES",
           "source":str(source),"source_sha256":sha256(source),
           "output_sha256":sha256(dest),"mapping_sha256":sha256(a.mapping),
           "mapping":m,"label_validity_status":validity,
           "rows":len(t),"median_interval_s":float(np.median(np.diff(t))),
           "warning":"Source evidence is supplied by the mapping author; numeric checks do not verify axes, source clock origin/alignment, reference quality or reverse sign. No timestamp origin was changed."}
    audit_path.write_text(json.dumps(audit,indent=2))
    print(f"Saved {dest}; valid labels {valid.sum()}/{len(valid)}; {validity}")

if __name__=="__main__":main()
