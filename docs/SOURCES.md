# Primary sources checked for this implementation

Reviewed on 8 September 2026. These support implementation choices, not claimed
DRIFTLOCK performance.

1. Apple Developer, Accelerated PyTorch training on Mac.
   https://developer.apple.com/metal/pytorch/
   Supports using the Mac GPU through PyTorch's Metal Performance Shaders backend.

2. PyTorch, MPS backend.
   https://docs.pytorch.org/docs/stable/notes/mps.html
   Device availability and tensor/model placement. Runtime doctor tests our exact model.

3. PyTorch, MPS environment variables.
   https://docs.pytorch.org/docs/stable/mps_environment_variables.html
   Documents optional CPU fallback for unsupported operations.

4. Onyekpe et al. (2021), IO-VNBD.
   https://doi.org/10.1016/j.dib.2021.106885
   https://arxiv.org/pdf/2005.01701
   Dataset provenance, phone vs vehicle streams, sensor units and label cadence.

5. Dataset authors' repository.
   https://github.com/onyekpeu/IO-VNBD
   Author-supplied S-dataset CSV files. Actual selected training logs must be obtained
   and reviewed; the package does not redistribute or invent them.

6. PyTorch, GRU documentation.
   https://docs.pytorch.org/docs/stable/generated/torch.nn.GRU.html
   Gate semantics used for equivalent primitive-operation mobile export.

7. PyTorch, GaussianNLLLoss.
   https://docs.pytorch.org/docs/stable/generated/torch.nn.GaussianNLLLoss.html
   Mean/variance regression loss. Coverage must still be checked independently.

8. Google AI Edge, LiteRT Torch repository.
   https://github.com/google-ai-edge/litert-torch
   Documents the PyTorch converter, Linux host requirement and conversion call.

9. Google AI Edge, PyTorch-to-LiteRT quickstart.
   https://developers.google.com/edge/litert/conversion/pytorch/overview
   Conversion, export and numerical output comparison before deployment.

10. Qian et al. (2025), AVNet.
    https://doi.org/10.1186/s43020-025-00168-7
    Research context for learned inertial measurements fused with a navigation filter.
    This package is not an AVNet reproduction and does not claim its accuracy.

## Terminology

MPS: Metal Performance Shaders. GPU: Graphics Processing Unit. CPU: Central
Processing Unit. IMU: Inertial Measurement Unit. CNN: Convolutional Neural Network.
GRU: Gated Recurrent Unit. GNSS: Global Navigation Satellite System. RMSE: Root Mean
Square Error. MAE: Mean Absolute Error. NLL: Negative Log-Likelihood. CSV:
Comma-Separated Values. JSON: JavaScript Object Notation. WSL2: Windows Subsystem
for Linux 2. IO-VNBD: Inertial Odometry Vehicle Navigation Benchmark Dataset.
