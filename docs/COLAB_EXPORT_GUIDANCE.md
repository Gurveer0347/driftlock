# Optional mobile conversion from the Mac browser

Local training, evaluation and portable export remain on the Mac. This document
covers only a later, separately approved conversion of a compatible selected model
to LiteRT .tflite. Do not install Linux, Docker, a VM or Linux export requirements
in the Mac environment. Check current official converter/Python requirements when
executing conversion; no remote runtime or Android result is established here.

The active contract is `driftlock-ml-1.1-centre-forward`: signed vehicle-forward
speed and sigma at the centre of a completed 20-sample window. Legacy latest-sample
magnitude checkpoints are incompatible. Conversion cannot fix their target or sign
semantics. See [DATA_CONTRACT.md](DATA_CONTRACT.md).

## Minimal approved bundle

After compatible local portable parity succeeds, prepare:

- Matching driftlock_ml source, including config/constants, and actual saved config.
- requirements-export-linux.txt for the separate supported runtime.
- Compatible checkpoint.pt and virtual_odometer.pt2.
- contract.json, golden_input.npy, golden_input_f32.bin and golden_timestamps_s.npy.
- Expected golden output, artifact hashes and portable_inference.py.
- Exact commands, dependency versions and the source/contract version.

Exclude .venv, secrets, original private trips and unrelated files. Uploading source
and a checkpoint still sends them off the Mac, so specific upload approval is
required. Do not infer upload permission from conventions or local-training approval.

## Approved conversion sequence

1. Open Google's official PyTorch-to-LiteRT example, check the actual remote OS,
   Python/PyTorch/converter support and available packages.
2. Upload only the minimal approved bundle. Install compatible export dependencies
   in that separate runtime and restart it if required.
3. Verify the bundle hashes, versioned contract and golden timestamp vector.
   Preserve real since-boot times for any supplied real window.
4. Run the existing exporter from the extracted project directory, replacing the
   example checkpoint/output with the actual paths:

```bash
python -m driftlock_ml.export \
  --checkpoint handoff/portable/checkpoint.pt \
  --out handoff/mobile --format litert
```

If passing a real `--sample-npy`, also pass its matching
`--sample-timestamps-npy`; never fabricate its clock origin.

5. Require the exporter's converted-model parity checks to pass, and retain
   errors if operations/versions are unsupported. Download the resulting complete
   bundle; the ephemeral notebook must not be the only copy.
6. Vishisht runs the same golden input and timestamp vector on an actual Android
   device, checks the signed speed/sigma output and exact
   `t,speed_mps,sigma_mps,valid` behaviour, and measures warmed-up batch-one latency.

The tensor input remains raw phone `[1,20,6]`; clocks are wrapper metadata, never a
seventh feature. The packet's target time is the midpoint of the first/last actual
window times in seconds since boot. At 10 Hz the completed estimate is available
0.95 s after its centre. Warmup/gaps/unusable/uncalibrated output must be invalid;
normalization is embedded, and recurrent state resets per window. No variance
field crosses the filter boundary. Android hardware timing and Antarjot's delayed
fusion need integration checks beyond converted tensor parity.

No conversion, real-road utility, reverse accuracy or Android execution is claimed
by this guide. If conversion fails, preserve its evidence and deliver verified
portable artifacts with mobile conversion pending. Do not rename .pt/.pt2 files
to .tflite or change architecture to hide failure.

Official conversion references:

- https://developers.google.com/edge/litert/conversion/pytorch/overview
- https://github.com/google-ai-edge/litert-torch
