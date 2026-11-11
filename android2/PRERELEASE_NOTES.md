# MagViewer 2.9-alpha6 (contrast knob on the colour bar, HIK 4117 mirror fix)
**Alpha — a test build, not a stable release.** Built locally (not by GitHub Actions).

**This APK is “MagViewer β”** (`com.magnity.viewer.beta`), signed with a different key than
the β builds from the build server (2.9-alpha4 and older): **uninstall the old MagViewer β first**. It
installs next to MagViewer 2.8, not over it.

## New in 2.9-alpha6
- **Contrast on the main screen**: the colour bar has a white knob — drag it along the bar
  to bend the palette (same setting as ☰ → Contrast (gamma)); double-tap the bar for 1.00.
  The knob marks where the palette's middle colour sits; γ is shown next to the middle label.
- **Mirror is now remembered per camera**. The HIK 4117's own picture was shown mirrored
  (issue #4): mirror is off by default for the 4117, the other cameras keep their setting.

## New in 2.9-alpha5 — InfiRay Tiny1C and Tiny1B
- Both modules (256×192, USB) are recognised when plugged in, through InfiRay's IRUVC
  Android SDK 1.4.2: temperatures, FFC (shutter) and emissivity. Not yet tried on a module.
- Any UVC device under Realtek's VID 0x0BDA is taken for a Tiny1; PID 0x3901 is a Tiny1B.
- Debug overlay: SDK counters, the sizes the module offers, the raw temperature range and
  which scale it was read with (÷64 Tiny1C, ÷16 Tiny1B), and `rowΔ` (the smaller of the two
  is the module's real row length).
- Tiny1B is drawn 192 wide × 256 tall, as in its vendor demo — to be confirmed.

## New in 2.9-alpha4 — Hikvision TB-4117 older firmware (issue #4)
In 2.9-alpha2 the module answered every older-protocol query with the query itself, so
there was no temperature and no picture.
- **A picture now**: when the 4117 gives no temperatures, MagViewer shows the module's own
  video (about 25 fps), without readouts, markers or colour bar. The palette, gamma, DDE and
  SR settings do not apply to it.
- **Second try at temperatures**: Hikvision's SDK puts the bytes of the query in a different
  order than alpha2 did ({group, 0, sub, channel}). The 4117 is now asked in that order and
  in four variants (without the channel byte, with a pause, reading twice, alpha2's order
  with a pause); the report lists each answer (`legacy try …`) and the first that is not an
  echo is used from then on.

## New in 2.9-alpha3 — contrast (gamma), all cameras
- ☰ → **Contrast (gamma)**, under Minimum span: bends the palette without changing the
  range. Above 1 gives more colours to the cooler part of the scene, below 1 to the warmer
  part; 1.00 is linear (tap the value to reset). Range ⅓ … 3, saved.
- Display only: temperatures and saved °C are unchanged. Screenshots, videos and capture
  playback use the same curve; the colour bar shows it and its labels stay in °C.

## New in 2.9-alpha2 — Hikvision TB-4117 (issue #4)
Some 4117 modules answer the version probe with zeros instead of “2.0”: 2.9-alpha1 then
reported “command channel locked” and showed no temperature, while Hikvision's own app
worked. Hikvision's SDK (in the APK attached to issue #4) switches such modules to an older
command protocol. 2.9-alpha2 does the same — decoded from the SDK, not yet tried on a module:
- the report says `legacy protocol (SDK Uvc10)` after the version probe;
- device info, algorithm version and the other read-only queries use the older protocol,
  their raw answers go into the report;
- the temperature frame (2046) is read with the older protocol;
- FFC, emissivity, body-temperature compensation and the 11 stream modes are **off** on
  such modules for now (writing needs a format we have not seen yet); the stream test only
  measures the UVC formats.

Modules that answer “2.0” work as in 2.9-alpha1.

## New in 2.9-alpha1 — Hikvision TB-4117
From Hikvision's own USB SDK (HCUSBSDK V2.4.0.3 for Android — its header, manual and
command table; the SDK itself is not used):
- **FFC** now calibrates the 4117's shutter (command 2025, “manual correct”).
- **Emissivity ε** now goes to the 4117 (command 2031), so its temperatures use the value set
  in MagViewer. The module's own value comes back when it disconnects.
- **Run stream test** tries all 11 thermal stream modes the SDK lists (2.8 tried 0–8; the new
  ones are 9 YUV + raw, 10 YUV only, 11 header + YUV + raw) — still about a minute.
- The test recognises the SDK's thermal stream frame (the “FRMI” header) and writes its
  fields into the report: data type, size, frame rate, whether the temperatures are 32-bit
  floats or scaled 16-bit values, and their range. One such mode at video rate would make
  the 4117 a live camera like the others.
- FFC and emissivity results (with the module's error code) are in the report too.

The MAG160 and Tiny2C work as in 2.8.

## For testers
1. Install, plug in the 4117, allow **Camera** and then **USB** access. The temperature
   image appears (about 1 frame per second) — or, on the older firmware of issue #4, the
   module's own video, until a temperature read works.
2. Tap **Debug** and take a screenshot.
3. Tap **FFC** once (the module's shutter should click). Open ☰ and set **Emissivity ε** to
   0.90, then back to 1.00.
4. ☰ → **Run stream test** → wait about a minute → **Copy report**, and send the text.

## 测试步骤（简体中文）
1. 需要 Android 10 或以上。安装后插上 4117，先允许“相机”权限，再允许 USB 访问。
   应出现温度画面（约每秒 1 帧）；issue #4 那种旧固件则先显示模组自己的画面（没有温度）。
2. 点 **Debug**，截一张全屏图。
3. 点一次 **FFC**（模组快门应会“咔”一声）。打开 ☰ 菜单，把 **发射率 ε** 调到 0.90，再调回 1.00。
4. ☰ →“运行串流测试”→ 等约 1 分钟 →“复制报告”，把文字发回来。

The APK is ~80 MB because the Tiny2C's AC020 SDK brings its own native libraries.
