#!/usr/bin/env bash
set -euo pipefail
# Run from the project root after installing requirements.txt.
# Every input/result in this flow is SYNTHETIC and must NOT be used as SIH evidence.
python -m pytest -q
STAMP="$(date +%Y%m%d_%H%M%S)"
RUN="runs/smoke_${STAMP}"
DATA="data/smoke_${STAMP}"
python tools/make_demo_data.py --out "$DATA"
python -m driftlock_ml.train --manifest "$DATA/manifest.json" --out "$RUN" --epochs 4 --device cpu
python -m driftlock_ml.calibrate --manifest "$DATA/manifest.json" --checkpoint "$RUN/best.pt" --out "$RUN/calibrated.pt" --device cpu
python -m driftlock_ml.evaluate --manifest "$DATA/manifest.json" --checkpoint "$RUN/calibrated.pt" --out "$RUN/test" --device cpu
python -m driftlock_ml.blackout_eval --predictions "$RUN/test/predictions.csv" --out "$RUN/blackout"
python -m driftlock_ml.export --checkpoint "$RUN/calibrated.pt" --out "$RUN/handoff" --format portable
python -m driftlock_ml.infer --checkpoint "$RUN/calibrated.pt" --input-npy "$RUN/handoff/golden_input.npy" --timestamps-npy "$RUN/handoff/golden_timestamps_s.npy"
python -I "$RUN/handoff/portable_inference.py" --bundle "$RUN/handoff" --input-npy "$RUN/handoff/golden_input.npy" --timestamps-npy "$RUN/handoff/golden_timestamps_s.npy"
echo "Software smoke test complete: $RUN. This is NOT real navigation performance."
