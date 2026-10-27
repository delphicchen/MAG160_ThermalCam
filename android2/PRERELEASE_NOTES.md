# MagViewer 2.9-alpha1 (HIK 4117: FFC, emissivity, all 11 stream modes)
**Alpha — a diagnostic build for testing, not a stable release.** Built by GitHub Actions
from the `ccr-1a3608d0-s97tvm` branch.

**This APK is “MagViewer β”** (`com.magnity.viewer.beta`, signed by the build server): it
installs **next to** MagViewer 2.8, not over it, with its own settings. If a β you built
yourself is installed, uninstall that first. It installs over 2.8-alpha1 … alpha4.

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
   image appears (about 1 frame per second).
2. Tap **Debug** and take a screenshot.
3. Tap **FFC** once (the module's shutter should click). Open ☰ and set **Emissivity ε** to
   0.90, then back to 1.00.
4. ☰ → **Run stream test** → wait about a minute → **Copy report**, and send the text.

## 测试步骤（简体中文）
1. 需要 Android 10 或以上。安装后插上 4117，先允许“相机”权限，再允许 USB 访问。
   应出现温度画面（约每秒 1 帧）。
2. 点 **Debug**，截一张全屏图。
3. 点一次 **FFC**（模组快门应会“咔”一声）。打开 ☰ 菜单，把 **发射率 ε** 调到 0.90，再调回 1.00。
4. ☰ →“运行串流测试”→ 等约 1 分钟 →“复制报告”，把文字发回来。

The APK is ~80 MB because the Tiny2C's AC020 SDK brings its own native libraries.
