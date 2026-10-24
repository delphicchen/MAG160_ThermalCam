# MagViewer 2.8-alpha1 (HIK 4117 diagnostics)
**Alpha — a diagnostic build for testing, not a stable release.** Built by GitHub Actions
from the `ccr-1a3608d0-s97tvm` branch.

**This APK is “MagViewer β”** (`com.magnity.viewer.beta`, signed by the build server): it
installs **next to** MagViewer 2.7, not over it, with its own settings. If a β you built
yourself is installed, uninstall that first. The MAG160 and Tiny2C work as in 2.7.

## New in 2.8-alpha1
- **Hikvision TB-4117-3/S** (USB UVC firmware, `2BDF:0101`) is recognised as *HIK 4117* —
  a first look, to find out what the module can stream:
  - The temperature image comes from the module's radiometric snapshot (120×160 °C,
    **about 1 frame per second**) and goes through the usual palettes / Thermal SR.
  - On connecting, a report records the USB speed, the formats the module offers,
    firmware info and its settings.
  - **Run stream test** in the menu (~1 min) tries each thermal stream mode of the module
    with each USB format and records what it sends. If one of them carries temperature
    every frame, the 4117 can become a live camera like the others.
  - Changes to the module: body-temperature compensation (which adds ~3 °C to skin) is off
    while connected and back on when it disconnects; the stream test puts the stream mode
    back when it ends (or on the next connect, if it was interrupted).
  - Not in this build: emissivity (the module keeps its own, shown under Debug) and FFC
    (the module runs its own).

## For testers
1. Install, plug in the 4117, allow **Camera** and then **USB** access.
2. Tap **Debug** and take a screenshot.
3. Open ☰ → **Run stream test** → wait about a minute → **Copy report**, and send the text.

## 测试步骤（简体中文）
1. 安装后插上 4117，先允许“相机”权限，再允许 USB 访问。应出现温度画面（约每秒 1 帧）。
2. 点 **Debug**，截一张全屏图。
3. 打开 ☰ 菜单 →“运行串流测试”→ 等约 1 分钟 →“复制报告”，把文字发回来。

The APK is ~79 MB because the Tiny2C's AC020 SDK brings its own native libraries.
