# Wan2.2 image-to-video workflow

## User-facing flow

1. Open `影坊 · 动效工坊` → `创意渲染`.
2. In `参考图（可选）`, tap `选择参考图` and select an image from Android's system photo/document picker.
3. Verify the selected first frame preview and `已选择参考图` status; use `更换参考图` or `移除` as needed.
4. Enter a motion prompt and tap `开始图生视频`.
5. On-device inference copies the selected `content://` URI into the app cache, then passes that exact file via `-i <path>` to the Wan2.2-TI2V native CLI. Without a selected image, the CTA says `开始文生视频` and the CLI receives no `-i`.

## Constraints

- Reference-image conditioning is implemented for on-device Wan2.2-TI2V. Workstation bridge does not accept image payloads yet; selecting a reference while local model/runtime assets are absent fails explicitly rather than silently generating text-only video.
- The current phone-harness session reached the DocumentsUI picker and the selected-image preview state with a real image. A subsequent long inference run was not launched in the final verification pass because the Android user profile became locked and phone-harness correctly refused UI access. Do not claim successful image-to-video generation from the final UI-only verification.
