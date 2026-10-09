# Qwen Image 2.1 model conversion

The Android app intentionally does not convert models on-device. Run the
conversion on a workstation with Python, MNNConvert and enough free disk space:

```bash
QWEN_SOURCE_REPO=abenzerps/Qwen-Image-2.1-Uncensored-GGUF \
QWEN_GGUF=qwen-image-2.1-UC-Q4_K_M.gguf \
OUT=models/qwen_image21_uc_q4km \
scripts/convert_models.sh
```

`Q4_K_M` is the selected baseline. The MNN exporter preserves supported Q4_K
blocks and uses the configured auxiliary precision for the remaining layers;
`make_manifest.py` records the resulting file hashes. Import the complete
directory in the app or publish it to a public Hugging Face model repository.

The app also supports the official Qwen-Image-2.1-Turbo checkpoint as a
second DiT variant. For a phone-sized package, use the Q4_K_M GGUF derived
from that official checkpoint and convert it beside the base DiT:

```bash
scripts/convert_official_turbo.sh
```

This produces `dit_official_turbo.mnn` with MNN int4 weights, int8 auxiliary
projections, and the official eight-value `sample_sigmas` schedule. The
shared Qwen3-VL text encoder and Tiny VAE are reused, so the package does not
duplicate them. The manifest records `official-turbo` only after the converted
file and its validation flag are present.
