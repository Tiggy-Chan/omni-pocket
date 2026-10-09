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
