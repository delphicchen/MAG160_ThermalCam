# MAG SDK 整合狀態 — 已結案（2026-09-06 更新）

## 結論先講

**原本的計畫前提是錯的，「per-die 非線性 radiometric LUT」不存在。**

之前以為固件在 `ctx+0x1900` 建了一組 per-die 135-entry LUT，需要 32-bit SDK 或舊裝置才能取得。
實際逆向結果：

1. `ctx+0x1900` **不是 LUT，是一個純量 emissivity**（值 = 1.0，parser 夾在 (0,1]）。
2. 整個 `ctx + die*0x21c + 0x16fc..0x1918` 區塊 **不是算出來的，是直接從 `mag_cali.bin` 尾端讀進來的**。
3. 檔案裡只有 **一筆** 這種區塊，名字叫 `lensf6.5`（鏡頭 profile，不是 per-die）。
4. 真正的 radiometric 轉換是 **8 段分段線性曲線**，資料全在 `mag_cali.bin` 裡。

**所以：不需要 32-bit 裝置、不需要 SDK、不需要 Unicorn 模擬。**
32-bit 的 `libcoresdk.so` 對這件事已經沒有價值。

---

## 證據

### 1. `0x400431d0` 不是 LUT builder

它是 **鏡頭幾何畸變 remap table builder**，輸出在 `ctx+0x254`：

- `calloc(rows*cols*4, 4)` → 160×120×16 bytes
- 每 pixel 4 個 u32 = `(u16 來源索引, u16 Q15 權重)`，4 個權重加總 = 32768
- 中心 `[60,80]` 索引 = 9680 = 60*160+80（identity），四角往內縮 → 桶形畸變校正
- 徑向係數 `p` 由 **r1** 傳入（不是 s22）；且 `s22` 在 `0x43318` 被 `0.999/(ratio²·p+1)` 覆寫，
  所以 p≈0 時整張表≈identity

`recon/extract_per_die_lut.py` 同時搞錯了兩件事：對錯了函式，而且把 `p` 寫進 `s22` 而不是 `r1`。
該腳本已無用。

### 2. `ctx+0x1900` 的唯一寫入者是 parser

全 binary 掃描 `add.w rd, rn, #0x1900` 只有三處：

| 位址 | 角色 |
|---|---|
| `0x4003f9f2` | sub_3f970 讀取（emissivity 邊界檢查） |
| `0x40042c6a` | parser：大區塊路徑，讀完 0x1dc bytes 後取 `[0x1900]` |
| `0x40042c9c` | parser：小區塊路徑，直接寫入 `1.0f` |

`0x40042c6a` / `0x40042c9c` 都在 parser（`0x400424d0`）裡，**沒有任何計算**。

### 3. `mag_cali.bin` 尾端就是那個區塊

magic `0x6BB60001` 在 offset **1855872**，剛好是檔案最後 **544 (0x220)** bytes，
對應 parser 的 `sub.w fp, fp, #0x220`。整個檔案只有這一筆。

---

## 已解出的資料（`lensf6.5`）

用 `recon/extract_lens_profile.py` 抽出，存成 `android2/recon/lens_profile.npz`。

```
name        = "lensf6.5"
emissivity  = 1.0            (ctx+0x1900)
n1 (shift)  = 12             (ctx+0x172c)  -> 位移 12 bits = /4096
n2 (segs)   = 8              (ctx+0x1738)
breakpoints = [9344 9959 10299 10545 10760 11109 11356 11707]     (ctx+0x173c)
seg gain/off= [(35880,-57021) (32628,-49604) (35578,-56777) (33542,-51656)
               (36565,-59441) (34123,-53024) (31869,-46911) (34134,-53192)]  (ctx+0x17d0)
params A..F = [10.60016  6.8699  443.25949  13828.37  44.490898  16567.859]  (ctx+0x17a8)
amb_coef    = [-9.0061e-06  1.5125e-04]                            (ctx+0x17a0)
clamp       = [19790, 44930] centi-K = -75.2 .. +176.2 degC        (ctx+0x1904/0x1908)
```

### 分段曲線（消費者在 `0x40040020`）

```
v    = (planck_interp - ctx[0x7750]) >> ctx[0x7754]     # 夾 >= 0
seg  = 第一個 i in [0, n2-2] 使 v <= breakpoints[i]，都不符則 n2-1
T_cK = ((gain[seg] * v) >> n1) + offset[seg]            # 64-bit 乘法
```

**注意 `v` 不是 raw counts，是 Planck 反算後的值。** 這條曲線是 Planck 反算「之後」的
最終線性化修正。

驗證：8 段在每個 breakpoint 上都連續（誤差 < 0.005%），
且 `n1 = 12` 與經驗上湊出的 `/4096` 完全吻合。

```
v=9344  -> -24.85 degC      v=10545 ->  73.81 degC
v=9959  ->  24.12 degC      v=11707 -> 170.53 degC
```

### params A..F 的用途（`0x4003fa4a`..`0x4003fb3a`）

一個以 B 為轉折點的兩段線性映射，對「量測值」與「參考值 A」各做一次：

```
lin(x) = (x > B) ? (E*x + F) : (C*x + D)
s2 = lin(measured)      # measured 來自 sub_455bc
s0 = lin(A)             # A = 10.60016，常數參考
```
在 B = 6.8699 處連續（16873.4 vs 16873.5）。這是 emissivity / 環境反射補償；
本機 emissivity = 1.0，此路徑大機率退化成 passthrough。

---

## col-128 seam — 已找到根因並修掉

**per-die LUT 那條線是死的**（那個區塊是鏡頭 profile，不含 per-die 資料）。
真正的原因在別的地方：**factory NUC gain table 在 col 128 有一個假的斷階。**

### 量測（相機實測，FFC 快門 + 室內場景，兩個 level）

| 位置 | 觀察 |
|---|---|
| col 128 | NUC 輸出 step = **−103 counts**（其他 column 中位數 2.2） |
| row 60 | step = **+0.09** → 沒有 row seam |
| col 80 | step = **+3.0** → 沒有 seam |

所以 §5.4 的「2×2 quadrant die」模型跟實際不符：**只有 col 128 一條垂直接縫。**

### 根因：gain table 的斷階是假的

factory gain table 在各 FPA anchor 的 col-128 斷階：

```
fpa=14000  c128: -91.3     其他 16-col 邊界: -7.6 ~ +12.4
fpa=19000  c128: -157.7    其他:              -10.3 ~ +25.1
fpa=24000  c128: -84.6     其他:              -5.9 ~ +13.7
```

col 128 比任何其他邊界大 **10–20 倍**。而直接量感測器本身（不經過任何 table）：

```
dRaw = scene - shutter          (level 差 -2394 counts)
step@128 = -10.4                median|step| 其他地方 = 7.5
±4 column 視窗 L/R 響應比 = 0.99978  (-0.02%)
```

**感測器在 col 128 完全平坦（0.02%），但 table 宣稱有 3.4–4.5% 的差異。**
→ 這個斷階是萃取出來的假資料，不是感測器結構。

旁證：同一次萃取出來的 `ref` 欄位**確定是壞的** — 整張圖偶數 column ≈3766、
奇數 column ≈11252，差 3 倍。`NUC_RAW_MAP_DECODE.md` 也明講 ref/bp 的
`(base_map, off_b)` transform 還沒校準（只有 gain 是 bit-exact）。

### 兩個成分

| 成分 | 大小 | 誰處理 |
|---|---|---|
| offset（快門 level 的固定 pattern） | −128 counts | `seam2d` + `seamResidual`（已有，OK） |
| **gain（隨 level 縮放）** | **≈ +4.5%** | **原本沒人處理 ← 就是剩下的 25–34 counts** |

驗證：快門 level 的 step = −128.1，正好等於 offset table 自己的 col-128 斷階（−128.8）。
`seam2d` 把它打到 0；剩下 +25.5 是 gain 成分（level = −550 時）。

### 修法：`FactoryNuc.flattenColumnSeam()`

在 `tablesAt()` 載入每個 grid index 後，把 gain table 的 col-128 斷階抹平：
對 col 128 兩側各 16 個 column 的 column-mean 做最小平方直線，
外推到邊界 (127.5) 取比值，然後把 col ≥ 128 整塊乘上該比值。

**為什麼要用外推而不是直接取區塊平均**：斷階疊在一個平滑的 column 梯度上，
區塊平均會被梯度稀釋而低估。

```
估計方式              seg0 ratio    殘餘 step@128
--------------------  -----------   -------------
（未修正）                  —           +25.54
區塊平均 win=16          1.03358        +7.45
區塊平均 win=4           1.04502        +1.29
線性外推 n=16            1.04565        +0.93   <- 採用
線性外推 n=8             1.04736        -0.02
```

殘餘 **+0.93**，已經低於雜訊底（其他 column 的 median |step| = 2.7）。
比值在各 FPA anchor 穩定（seg0 1.043–1.046、seg1 0.9858、seg2 1.018），
`ratio` 落在 0.8–1.25 之外時跳過，避免把退化的 anchor（fpa≥29000 讀不到資料）搞壞。

只改 gain，不動 offset — offset 成分已由 `seam2d` 完整處理，再改會雙重修正。

### 均勻場景驗證（手掌貼鏡頭，2026-09-06）

`recon/calib_col128.py`，fpa=23242 → anchor 23000（跟前面那次不同的 anchor）：

```
level vs shutter = -1252 counts
dRaw cols112-127 = -1359.3      cols128-143 = -1359.9
responsivity ratio L/R = 0.99955   (-0.04 %)
factory gain table 宣稱 = 1.03360  (+3.36 %)
```

**感測器響應在 col 128 兩側差 0.04%，table 宣稱 3.4% — 差 75 倍。斷階是假的，確認。**

`dRaw = scene − shutter` 兩張都含相同的 offset，相減時 offset 完全抵消，
所以這是純粹的響應度量測，跟 offset 誤差無關，level 的正負號也不影響結論。

端到端（同一組均勻場景資料）：

```
                          step@128    median|step|   max elsewhere
before (factory gain)      +14.98        2.43           7.18
after flattenColumnSeam()   -1.36        2.40           7.18
```

殘餘 **−1.36**，低於雜訊底。修正在不同 FPA anchor、不同 level 都成立。

### 還沒做的

- **seg1 / seg2 沒有實測驗證。** 兩次量測 level 都在快門以下（−550、−1252），
  v 全落在 seg0。seg1/seg2 的比值（0.9854、1.0177）目前只有 table 內部一致性，
  沒有感測器實測背書。要驗需要**比機身還熱**的場景（相機跑久了機身溫度偏高，
  手掌反而是冷的 → 用熱水杯或讓相機先降溫）。
- 根治做法是修 `ref`/`bp` 的 de-interleave（見 `NUC_RAW_MAP_DECODE.md` 待辦），
  重新萃取一份乾淨的 `factory_nuc_grid.npz`；目前是在下游補。

## flat-field 學完後下一次 FFC 就失效（已修）

### 症狀
按 Learn / Re-learn 後畫面乾淨，但下一次 FFC 之後線條和冷區又跑出來。

### 根因
`flatMap` 是在**處理後的輸出域**學的（`learnFlatField()` 存的是 `processCounts()` 的殘差），
而那個輸出域是相對於三樣東西定義的：

- `camera.ffcRef`（NUC 的 `v = raw - ffcRef` 基準）
- `seam2d`（`afterFfc()` 用新快門重算）
- 當前的 NUC grid table（FPA 漂移會切換）

**一次 FFC 把前兩樣同時換掉**，而 `flatMap` 完全沒被碰。`doFfc() → afterFfc()` 裡沒有
任何 invalidate，`clearFlatField()` 只有 UI 手動呼叫。於是舊的 map 變成在「減掉一個
已經不存在的 pattern」，等於把舊的接縫重新打回畫面。

實測（同一個場景，連續兩次 FFC）：

```
FFC#1 學完當下         residual FPN sd = 0.00
FFC#2 之後（舊 map）   residual FPN sd = 8.05    <- 使用者看到的
flat-field 關掉         residual FPN sd = 68.73
```

### 為什麼不是改成 gain map

先驗證過殘餘 FPN 是 offset-like 還是 gain-like：兩個 level（-328 / -748，比值 2.28）下

```
FPN pattern sd 比值   = 1.353      (純 offset 預測 1.00，純 gain 預測 2.28)
最佳純量 a (pB≈a·pA)  = 1.245
corr(pattern A, B)    = 0.925
```

**以 offset 為主**，所以 gain map 是錯的方向。map 本身沒問題，壞的是它所依附的基準。

### 修法

存下學習當下的**平均 raw frame**（`flatLearnRaw`），配置一變就用當前配置重新推導：

- `learnFlatField()`：改成先平均 raw、再跑一次 `processCounts`（順便讓雜訊不進 segment 選擇）
- `buildFlatMap(raw)`：`processCounts` + 減 median
- `rebuildFlatField()`：在 `afterFfc()` 末端（seam2d 更新後）以及 grid table 切換時呼叫
- `clearFlatField()` 一併清 `flatLearnRaw`

map 是 median-removed，所以純量的 `trim` 會自動抵消，level-lock 變動不需要重算。

實測（這次剛好同時跨過 grid 13→14，最壞情況）：

```
flat-field OFF      sd = 72.99
舊：凍結的 map      sd = 16.27
新：重算的 map      sd =  5.79     <- 改善 2.8 倍
```

殘餘 5.79 是學習到測試之間場景本身的熱漂移，那是 re-learn 才能解決的，不是這個 bug。

## 環境

系統 python 沒有 pip。已建立 venv：

```bash
cd /home/delphic/win_share/Delphic/mag160_thermalcam
uv venv .venv --python 3.13
VIRTUAL_ENV=.venv uv pip install numpy pyusb unicorn pyelftools capstone
.venv/bin/python recon/extract_lens_profile.py
```

`magcam.py` 的 `open()` **回傳 None**（不是 bool），
`if not cam.open(): sys.exit()` 會誤判成失敗 — 直接呼叫即可。

`libcoresdk.so` 路徑：`/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so`
（symlink → `reverse_eng/apk_decompile/lib/armeabi-v7a/libcoresdk.so`，開機後可能要重建）

## 編譯

```bash
cd android2
JAVA_HOME=/tmp/jdk-17.0.13+11 ./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`

## SDK 整合嘗試（已放棄，保留參考）

`jniLibs/armeabi-v7a/` 下的 `.so`、`app/src/main/java/cn/com/magnity/`、
`MagDeviceWrapper.kt` 都可以刪掉了 — 資料已經全部從 `mag_cali.bin` 拿到。
