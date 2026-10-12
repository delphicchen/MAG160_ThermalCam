# B2 round 2 — standing fan, 10-frame bursts (2026-10-12)

One press per position, distance to the fan head; each burst `fd_<time>_<k>` k = 0..9.

| burst | distance |
|---|---|
| fd_20261012_060018_817 | 0.6 m |
| fd_20261012_060033_640 | 1.2 m |
| fd_20261012_060054_889 | 1.8 m |
| fd_20261012_060108_861 | 2.4 m |
| fd_20261012_060124_035 | 3.0 m |

Note: `.dn.f32` is identical to `.f32` in every frame — both denoisers were off (or thermal SR
was on) during capture; the scripts average the 10 frames instead.

`out/`: `scan.log` (scan_burst.py), `variants.log` (scan_variants.py), `prior.log` (scan_prior.py),
`overlay.png` (overlay_burst.py: thermal mean | app registration | B's dy).
