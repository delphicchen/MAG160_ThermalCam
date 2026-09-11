# Factory SDK (`lib/`)

Not Magnity's own developer SDK — this is **Elo Touch Solutions**' thermal SDK from
their 2020 fever-screening kiosk, which bundles Magnity's `coresdk` underneath. The Elo
wrapper itself (`com.elotouch.*`: TCM motor board, FTDI, face cascades, fever judgement)
is not used here.

| File | What it is | Used? |
|---|---|---|
| `arm64-v8a/libcoresdk.so` | Magnity coresdk, **aarch64** (2020-05-04, NDK r16b) | **yes** |
| `armeabi-v7a/libcoresdk.so` | same build, 32-bit | no (older than the APK's) |
| `thermallib-release.aar` → `libs/coresdk.jar` | Magnity Java layer (`MagDevice`) | reference only |
| `thermallib-release.aar` → `com.elotouch.*` | Elo kiosk wrapper | no |
| `libs/d2xx.jar` | FTDI driver for Elo's TCM board | no |
| `opencv-release.aar`, `libopencv_java4.so` | for Elo's face tracking; coresdk does **not** link it | no |
| `SDK Guide.pdf` | Elo's high-level `ThermalController` API, Rev 1.1 (2020-09-29) | some |

## Why the arm64 build matters

Until this arrived we only had the `armeabi-v7a` coresdk out of the MAG-Mx/Cx APKs,
which is why `recon/PLAN_MAG_SDK.md` concluded a 32-bit device or Unicorn emulation
would be needed to run factory code. This build is native on the Dimensity 9200, so
the SDK runs in-process: see `app/src/main/java/com/magnity/viewer/usb/MagDeviceWrapper.kt`.

It is an older build than the APK's and exports 61 of that build's 73 JNI entry points.
JNI resolves lazily, so the 14 missing ones only fail when called — the list is in the
`MagDeviceWrapper` header comment. The two that matter: **`GetOutputRawData`** (no
NUC-corrected raw counts from the SDK) and `GetSenorTemperature`.

## Build setup

`*.so` is git-ignored repo-wide (proprietary blobs), so the library is **not tracked**.
After a fresh clone, copy it into place or the app builds but crashes on
`System.loadLibrary("coresdk")`:

```sh
mkdir -p app/src/main/jniLibs/arm64-v8a
cp lib/arm64-v8a/libcoresdk.so app/src/main/jniLibs/arm64-v8a/
```

`libc++_shared.so` is not needed — the arm64 coresdk's `NEEDED` list is only
`liblog libjnigraphics libz libdl libc libm libstdc++`.

## Gotchas found while integrating

- **Temperature unit is milli-Celsius**, not the centi-Kelvin the firmware's own
  piecewise curve works in. Evidence: Elo's `createLegalResult` and
  `getInnerIRCameraTemp` both do `rawInt * 0.001d`, and no `273.15` appears anywhere in
  that class.
- `MagUsb.java` is 2020 code and throws outright on a modern target — patched in
  `app/src/main/java/cn/com/magnity/coresdk/MagUsb.java`:
  `registerReceiver` needs an export flag (targetSdk ≥ 34), and `PendingIntent` needs an
  explicit mutability flag (targetSdk ≥ 31).
- `getTemperatureProbe`'s 2-arg overload is `(pos, size)`, **not** `(x, y)` — pass
  `size` explicitly or the coordinates are silently misread.
- Releasing the device from `MagCamera` makes the camera re-enumerate, so it is absent
  from `UsbManager.getDeviceList` for a second or two and `deviceId` changes.
  `MagDeviceWrapper.awaitEnumeration` polls instead of failing on the first miss.
- This camera reports **160×120** (`type='160core'`), not the 256×192 an earlier draft
  of the wrapper hardcoded. Read it from `CameraInfo`.

## Build JDK

`JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug --offline` — the `java`
on PATH is a conda JDK 25 that AGP cannot parse (the build fails with a bare
`* What went wrong: 25.0.1-internal`), and `/usr/lib/jvm/java-21*` are JRE-only.
