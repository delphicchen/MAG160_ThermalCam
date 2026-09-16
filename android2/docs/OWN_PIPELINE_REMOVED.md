# Own reconstruction pipeline — removed 2026-09-16

The app now takes temperature **only** from the factory SDK (`libcoresdk.so`, wrapped by
`usb/MagDeviceWrapper.kt`), which reports absolute °C and runs its own NUC and shutter
management. Our own reconstruction pipeline — built by reverse-engineering the camera —
was removed to keep one code path.

Nothing is lost: everything below is still in git history, and `REVERSE_ENGINEERING.md`
(kept in this repo) documents how the format and the correction maths were worked out.

## Last commit that still contains the pipeline

    fe262711898db7d95984e339a0ecef4a7cb1a1ab

## What was removed

| File | What it did |
|------|-------------|
| `pipeline/FactoryNuc.kt` | Factory NUC grid (gain/offset per FPA temperature bucket), nearest-grid selection, column-128 seam residual |
| `pipeline/Radiometry.kt` | Planck LUT lookup, NUC counts → °C, one-point SPOT refine (slope kept, offset shifted) |
| `pipeline/FactoryFlatField.kt` | Factory flat-field map loader |
| `data/Npy.kt` | Minimal .npy / .npz reader for those tables |
| `usb/MagCamera.kt` | Raw USB bulk reader (EP 0x81), FFC trigger, sensor temperature, frame assembly |

Also removed from `ui/ViewerViewModel.kt`: the raw frame loop, `process()` /
`processCounts()`, learned flat-field (learn / rebuild / clear), level-lock trim and
shutter anchor, 2-D seam map, post-FFC frame dropping, the FPA-drift auto-FFC watchdog,
the `Source` selector (factory SDK vs own pipeline) and the SPOT refine entry.
`app/build.gradle.kts` no longer copies `recon/factory_nuc_grid.npz`,
`recon/factory_flatfield.npz` or `recon/planck_luts.npy` into assets — those files are
still in `recon/`.

## How to bring it back

```sh
# inspect
git show fe26271 --stat
# restore the files
git checkout fe26271 -- android2/app/src/main/java/com/magnity/viewer/pipeline/FactoryNuc.kt \
                        android2/app/src/main/java/com/magnity/viewer/pipeline/Radiometry.kt \
                        android2/app/src/main/java/com/magnity/viewer/pipeline/FactoryFlatField.kt \
                        android2/app/src/main/java/com/magnity/viewer/data/Npy.kt \
                        android2/app/src/main/java/com/magnity/viewer/usb/MagCamera.kt
# the ViewModel/UI wiring and the gradle asset copy come from the same commit
git show fe26271:android2/app/src/main/java/com/magnity/viewer/ui/ViewerViewModel.kt
git show fe26271:android2/app/build.gradle.kts
```

## Why

The SDK's absolute temperature matched the factory app, while our pipeline needed the
FFC-relative trim, seam fix and flat-field upkeep to stay level, and its values drifted
after every FFC. Keeping both meant two value domains (°C vs NUC counts) in every UI
control. The reverse-engineering work stays valuable as documentation and as the fallback
if the SDK ever breaks on a future Android release.
