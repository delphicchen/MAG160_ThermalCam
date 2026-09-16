# Third-party notices

## Anime4K
- Source: https://github.com/bloc97/Anime4K (v3.2, `glsl/Upscale/Anime4K_Upscale_CNN_x2_M.glsl`)
- Copyright (c) 2019-2021 bloc97 — MIT License (`app/src/main/assets/anime4k/LICENSE`)
- Use: GPU CNN upscaler for the thermal display (`pipeline/Anime4kGpu.kt`); the shader
  file is shipped unmodified and wrapped for GLES 3.0 at runtime.

## Magnity / Elo camera SDK — proprietary, not covered by this repository's licence
- Files: `libcoresdk.so` (arm64), `cn.com.magnity.coresdk.*` Java classes, vendor AAR
  files under `android2/lib/`.
- Owner: Magnity Technologies / Elo Touch (respectively). All rights reserved by them.
- This project links against the SDK to talk to the camera. No licence to the SDK is
  granted or implied here, and it is not redistributed as source.
- **Anyone building this app supplies the vendor SDK themselves** from their own copy of
  the vendor software.
