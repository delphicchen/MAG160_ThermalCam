Fusion debug dumps, 2026-10-12 00:31–00:32, Xiaomi 14T Pro, target = standing fan (hot motor).
One frame per position; distance phone lens → **fan head**, measured on 60 cm floor tiles:

| file | distance |
|---|---|
| fd_20261012_003123_565 | 0.6 m |
| fd_20261012_003131_814 | 1.2 m |
| fd_20261012_003146_262 | 1.8 m |
| fd_20261012_003155_722 | 2.4 m |
| fd_20261012_003203_550 | 3.0 m |

`.f32` raw oriented thermal °C (120×160, little-endian), `.u8` upright visible luma (480×640),
`.json` registration in effect (AUTO distance) + calibration samples.
`compare_app_vs_B.png`: thermal edges (red) over the visible frame — top row the app's
registration, bottom row the band-limited B match (scan_band.py).
