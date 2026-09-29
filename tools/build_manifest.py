"""Create a candidate DRIVE-group split. Avi must review duplicate routes/sessions."""
from __future__ import annotations
import argparse
import json
import random
from pathlib import Path
import os
import sys
if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from driftlock_ml.config import CONTRACT_FIELDS
from driftlock_ml.data import (BOOT_TIME_BASE, DATA_TIME_BASES, RECORDING_TIME_BASE,
                               CANONICAL_UNITS, reject_non_csv_payload,
                               validate_data_clock_metadata)
from driftlock_ml.utils import sha256


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir",required=True)
    p.add_argument("--out",required=True)
    p.add_argument("--source-type",required=True,choices=["real","synthetic"])
    p.add_argument("--data-time-base",choices=sorted(DATA_TIME_BASES),default=BOOT_TIME_BASE,
                   help="Offline source clock, independent of the model/runtime clock; requires reviewed per-drive evidence.")
    p.add_argument("--label-source",required=True)
    p.add_argument("--seed",type=int,default=42)
    p.add_argument("--frame",choices=["phone"],default="phone")
    p.add_argument("--gravity-removed",action="store_true")
    a=p.parse_args()
    if a.gravity_removed:
        raise ValueError("Conventions 1.1 require raw phone acceleration including gravity.")
    files=sorted(Path(a.data_dir).resolve().glob("*.csv"))
    if len(files)<4: raise ValueError("Need at least 4 independent drive groups for train/val/calibration/test; more is better.")
    random.Random(a.seed).shuffle(files)
    n=len(files)
    nval=max(1,int(.1*n));ncal=max(1,int(.1*n));ntest=max(1,int(.2*n))
    ntrain=n-nval-ncal-ntest
    if ntrain<1: raise ValueError("Insufficient independent groups.")
    split_names=["train"]*ntrain+["val"]*nval+["calibration"]*ncal+["test"]*ntest
    out=Path(a.out).resolve();out.parent.mkdir(parents=True,exist_ok=True)
    if out.exists(): raise FileExistsError(out)
    entries=[]
    for file, split in zip(files, split_names):
        reject_non_csv_payload(file)
        entry={"path":os.path.relpath(file,out.parent),"group_id":file.stem,
               "split":split,"device_id":"UNCONFIRMED","label_source":a.label_source,
               "label_semantics":"UNREVIEWED", "label_semantics_evidence":"UNREVIEWED",
               "time_base_evidence":"UNREVIEWED"}
        entry["data_time_base"]=a.data_time_base
        if a.data_time_base == RECORDING_TIME_BASE:
            entry.update(sensor_reference_clock="UNREVIEWED", sensor_reference_clock_evidence="UNREVIEWED")
        audit_path=file.with_suffix(".import.json")
        if audit_path.is_file():
            audit=json.loads(audit_path.read_text())
            if audit.get("output_sha256") != sha256(file):
                raise ValueError(f"Import audit hash does not match {file.name}; review the current canonical file.")
            validate_data_clock_metadata(audit, expected_time_base=a.data_time_base)
            for key in (*CONTRACT_FIELDS, *CANONICAL_UNITS, "frame", "acceleration_includes_gravity"):
                expected={**CONTRACT_FIELDS, **CANONICAL_UNITS, "frame":"phone",
                          "acceleration_includes_gravity":True}[key]
                if audit.get(key) != expected:
                    raise ValueError(f"Import audit contract mismatch for {file.name}: {key}.")
            for key in ("label_semantics", "label_semantics_evidence", "time_base_evidence", "label_validity_status"):
                entry[key]=audit.get(key,"UNREVIEWED")
            for key in ("sensor_reference_clock", "sensor_reference_clock_evidence"):
                if key in audit:
                    entry[key]=audit[key]
            entry["import_audit_sha256"]=sha256(audit_path)
        entries.append(entry)
    result={**CONTRACT_FIELDS, **CANONICAL_UNITS,
            "data_time_base":a.data_time_base,
            "source_type":a.source_type,"frame":"phone","acceleration_includes_gravity":True,
            "review_status":"REQUIRES_AVI_REVIEW_OF_GROUPS_ROUTES_LABELS_AXES",
            "review_evidence":"UNREVIEWED",
            "drives":entries}
    out.write_text(json.dumps(result,indent=2))
    print(f"Wrote {out}. train={ntrain},val={nval},calibration={ncal},test={ntest}.")
    print("REVIEW REQUIRED: parts of the same trip must share group_id/split; identical routes/devices are NOT automatically held out.")

if __name__=="__main__":main()
