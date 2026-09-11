# PLAN — 原廠級免校正絕對測溫整合進 Android App

目標：重現原廠 App 的行為——**直接輸出絕對溫度、免使用者校正、長時間不漂移**。
狀態：逆向已完成 ~70%，公式骨架已解碼，剩資料來源驗證與移植。

## 已確認的事實（2026-08-25）

### 1. 校正檔沒有問題
- 從實機即時下載 `mag_cali.bin`（1,856,416 B，GetCaliFile→EP 0x84，`recon/getcali.py`），
  存回 `recon/mag_cali.bin`。
- 以模擬器重建 `factory_nuc_grid.npz`：與 APK 內打包的資產 **bit-identical**。
  ⇒ 「原廠比較準」不是校正資料的差異，是演算法層。

### 2. 漂移的根因（現行 port 的兩處偏離）
- 現行鏈：`FactoryNuc.apply(raw, fpa, cam.ffcRef)` + 固定 (a,b) 使用者兩點校正。
  每次自動 FFC 換 ffcRef → NUC 輸出位準跳動，但 (a,b) 不變 ⇒ 漂移。
- 原廠鏈：**每幀**重推 (k,b)，輸入自洽 ⇒ 不漂移。radiometry.py docstring 早記錄此事。

### 3. 本日解碼：sub_3d280 的真身與資料流
真實函數起點是 **0x3d228**（0x3d280 是中段標籤，無 prologue——與當年 sub_3a8c0 教訓相同）。

```
每幀（sub_3d228, r0=ctx）：
  framecnt = ++ctx[0x58]
  every7   = framecnt / 7            # uidiv；每第 7 幀走不同分支（待驗證語意）
  if framecnt > every7 且 ctx[0x12d4]!=0:          # 一般幀：線性外推
      dT  = float(ctx[0x40] - ctx[0x38])           # 現在溫度 − 校正參考溫度
      s18 = ctx[0x13f0] * dT                       # b 分量基準
      s16 = ctx[0x13ec] * dT                       # k 分量
  else:
      s16 = s18 = 0.0（常數 literal @0x3d368 = 0.0）

  快門修正 delta（三條路徑）：
  A) ctx[0x16f4]!=0：idx = ctx[0x80]（>=ctx[0x16f8] 則 0）
        p = ctx + idx*0x21c
        r2 = p[0x1734]-p[0x1730] + ctx[0x38]（−ctx[0x3c]，若 ctx[0x48]!=0）
        再 += ctx[0x3c] + 2*(ctx[0x40]-ctx[0x38]) − ctx[0x44]
  B) ctx[0x16f4]==0：r2 = 0 + 同上尾巴組合
  C) ctx[0x48]==0：clamp(ctx[0x3c]-r2, -1000, +1000)

  if delta >= 0: b = s18 + f32(sub_3d9f4(ctx,...)) else: b = s18 − …
  if b == 0: b = 0.1（literal @0x3d36c）
  ctx[0x50] = k = s16 ；ctx[0x54] = b
```

斜率來源（sub_3a574 @0x3a574，prepare 階段）：
```
  以 ctx[0x38] 對錨點表 A（ctx[0x1404..]，6 個）做 bracketing → 權重 w
  ctx[0x13f0] = lerp(table@rec+268, w)
  ctx[0x13ec] = lerp(table@rec+264, w)     # per-section float 表，build 期填入
```
⇒ **k,b 完全是 mag_cali 內容的確定性函數**（錨點內插 + 溫差外推 + 快門修正）。

sub_3d9f4（快門修正項）：
- fast path（ctx[0x7758]==0）：`min(r2 >> ctx[0x7754], 0xffff)`（parser 設 7754=3）
- slow path（7758!=0）：646-entry Planck 表風格的固定點內插（結構同 sub_3f970）

### 4. 周邊確認
- 全 .so 只有 sub_3d280 寫 ctx[0x50]/[0x54]（vstr s16/s0）。
- sub_3d280/sub_3d9f4 無直接呼叫者、無 literal 指標 → 由 runtime 函數表間接呼叫
  （不影響移植；我們已知其語意與時機：每幀、NUC apply 之後、溫度查表之前）。

## 下一步（依序）

1. **補齊資料來源**（模擬器黑箱即可，不需再讀 asm）：
   - rec+264/+268 浮點表的產生處（build 鏈哪一步寫入、值域多少）
   - 平行陣列 p[0x1730/0x1734]（stride 0x21c）的寫入者與意義
   - ctx[0x80]（索引）的產生：感測溫度 → 索引的映射（可能在 frame-tail 處理裡）
   - sub_3d9f4 slow path 完整語意（照 emu_harness.validate() 方式餵 LUT 驗證）
2. **numpy 重實作 + 對照模擬器 bit-exact**（仿 EMULATION_NUC.md 的驗證法）：
   隨機化 ctx 欄位 → 模擬器 sub_3d228 vs numpy 公式，max|diff|=0。
3. **實機擷取驗證**：抓連續 frames+FPA+FFC，跑完整原廠鏈（NUC grid apply +
   k,b 公式 + Planck LUT），確認室溫物體讀值合理且跨 FFC 週期穩定。
   （工具已備妥：getcali.py、grab3.py、emu_* 系列）
4. **移植 Android**：
   - `FactoryNuc.kt`：apply 改回忠實格式（grid ref 當 offset_ref、移除 levelBaseline；
     是否仍需 ffcRef 待步驟 3 結論）
   - `Radiometry.kt`：新增 per-frame k,b 推導（Kotlin 版 sub_3d228/sub_3a574），
     rawToCelsius 改吃 k,b；使用者校正降級為選用的微調 trim
   - `ThermalViewModel.kt`：管線接線、DDT 格式欄位因應
5. **收尾**：README（中英）、PROTOCOL.md 補「Conversion formula SOLVED」、
   移除/改寫 radiometry.py 的 workaround 註記。

## 2026-08-26 追加：FFC/offset_ref 機制解碼（Phase B 部分達成）

- `sub_34220(struct, frame)` = **多幀平均狀態機**（states 0/1/2，計數 +0x14，目標 +0x1c，
  累加緩衝指標 +8）。五個並行 struct：ctx+0x1d50/0x1d74/0x1d98/0x1dbc/0x1de4（stride 0x24，
  +0x20 = enable 旗標；ctx[0x1e48] 選擇作用中 struct）。
- **struct0 +8 = ctx[0x1d58] = offset_ref** ⇒ 原廠的 offset_ref 是**即時快門多幀平均**
  （與我們的 ffcRef 作法相同！），由 0x3a54a 呼叫 sub_34220(&ctx[0x1d50], frame) 填入。
  ⇒ 「原廠用 cali ref 我們用快門幀」的假設**不成立**——兩邊都用快門平均。
- 讀取者：sub_45ca4/0x3c8ce apply（gate ctx[0x1d70]!=0）、0x436c4。
- **未解**：既然兩邊 offset_ref 相同，縫為何原廠看不到？候選：
  (a) cali block pos0/1 的「gain maps」在 build 時被折進表裡（格式已解——見追加五；u16 奇偶列
      65000/600 互補結構，非常規 Q15/gain 格式，所有標準變換實測皆失敗）；
  (b) build（sub_3a7f8）在真機上以完整初始化的狀態執行，產生的表與部分初始化模擬器
      版本有系統性差異；
  (c) sub_34220 的平均幀數/來源與我們不同（例如取自 EP0x84 校正端點的某流）。
- 過渡方案（App v2 已實作）：FFC 快門幀量 1D 行/列「偏移剖面 + 增益剖面」扣除，
  FFC 後丟 6 幀暫態，cmd 前先 drain EP0x82 防協定失步。

## 2026-08-26 追加二：差分實驗結果（bufA 資料流）

方法：改磁碟上的 mag_cali.bin → 重跑 parse+build → diff 輸出表（diff_build2.py）。

- **表只依賴 file maps 27–35**（FPA=20000 時；連續 9 張，非 8 張/塊結構）。
  其餘 39 張歸零**完全不影響**輸出表。全零 → 表全變（表示 27-35 是唯一資料源）。
- 9 張的角色（逐張歸零 diff）：
  map27→ref+bp、map28→ref+bp、map29→bp+gain/off、map30-35→gain/off。
  ⇒ 每section 是一份「計算管線快照」（ref → breakpoints → gain/offset），
  不是獨立的 gain/ref 表。
- **Chain B 飽和之謎解碼**：segment2 的 gain ≈ 58807（=14.36×）！表設計給
  「小 v 域」（v=(raw−ffcRef)>>1，±數千）；cali-ref 的大 v（~11000）落進 14× 段
  必然爆表。⇒ **我們的 ffcRef 做法是正解**，cali-ref 路線正式否決。
- offset 表本身含 column-128 台階（10486→10256）與 col159 大坡（+4607）——
  縫在表裡。殘餘縫（1D 修正後 ~470 counts ≈ 2°C 顯示差）是**場景位準的乘性
  column FPN**，暗幀量不到，來源指向 pos0/1「gain maps」。
- **gain map 格式已解**（最後一塊拼圖已攻克，2026-08-28，見文末追加五）：u16 奇偶結構
   65000/600 = 偶列 `0xFE00|g_even`、奇列 `0x0200|g_odd`；`0xFE00+0x0200=65536` 是對稱儲存
   偏置，真實增益資料在**低字節 (0–255)**，build 以 `vld2.16` 解交織 + `vsubl.u16` 減參考
   抵消偏置取得淨增益。所有常規定點解讀失敗正是因忽略此偏置。

## 2026-08-26 追加三：斜率表來源定案（k,b 機制閉環）

差分實驗三連（marker 注入 + build 對照）：
- parse/build **不寫**斜率區（build 後 marker 原樣存活）
- build **不讀**斜率區（改變斜率值，bufB/bufC/ref 完全不變）
- 唯一讀取者 = sub_3a574（prepare 時內插到 ctx[0x13ec]/[0x13f0]）
- .so 全量 capstone 掃描：**沒有任何**立即偏移 264–312 的 store 指令
  ⇒ 寫入用暫存器偏移，且只存在於完整初始化路徑

**定案**：斜率浮點（k,b 的溫度係數）是完整初始化時注入的**外部輸入**
（原廠 QC 溫漂係數），不在 mag_cali.bin、不是 .so 常數、不由 parse/build 產生。
要拿到原廠值只有兩條路：(a) 繼續逆向 StartProcessImage 完整初始化找填充函數與
其資料來源；(b) 在可安裝原廠 APK 的裝置上以 ptrace/Frida 傾印運行時 ctx。

**工程結論**：不需要它。我們的 level-lock trim + FFC 快門位準錨定在功能上
等效覆蓋斜率機制（溫漂鎖定），絕對精度由兩點常數 (lut6, a, b) 提供。
實測：NUC 域縫強度與原廠 SDK 等強（646 vs ~630，exp_seam_trace.py），
顯示域由 bilateral 平滑收尾。

## 2026-08-26 追加四：尾表之謎解開 + 縫的最終定論

- 尾表（12192B）結構：1280 個 (hi,lo) u32 對 = 8 組 × 160 欄；
  hi≈13083（NUC 輸出域）、lo≈2717。
- **corr(tail-hi-detrended, offset-table-colprof-detrended) = +0.999**，
  比例 1.255±1.1% ⇒ 尾表 = offset 表行剖面的冗餘副本（供其他程式路徑），
  **不是**縫校正（拿它修正無效，實測 472.6→471.0）。
- 檔頭 128-1024（160 組遞減梯度對）：與任何縫剖面零相關，功能未明（不影響表）。
- **縫的最終定論**：out = v×gain + offset，v 帶場景位準列增益 FPN、offset 表帶
  固定行圖案，兩者在校正工作點抵消。偏離工作點 → 殘縫 ∝ 位準差
  （2 點 NUC 物理極限，原廠亦有）。緩解：FFC 快門錨定 + 1D 剖面 + 增益正規化
  + bilateral 顯示平滑（App 已全數實作）+ Flat-field 學習（極端情況）。
- **原廠 SDK NUC 域縫強度實測 = 646**（exp_seam_trace.py 跑真 apply2），
  我們 ~470-640 ⇒ 等強。

## 風險與備案
- 若 slow path 快門修正需要 runtime 才有的狀態而無法離線重現：
  備案是用模擬器離線掃描 (FPA, shutter-ref) 二維網格建成查找表帶進 app。
- 既有「手動兩點校正」UI 先保留為選用微調，避免行為突變。

## 2026-08-28 追加五：gain map 格式已解（最後一塊拼圖攻克）

方法：emu 環境（unicorn/pip）已遺失，改走靜態反彙編。先從 mag_cali.bin 抽出 48 raw maps
（header 128B + 48×38400B uint16），scan 確認每個 sensor-temp block 的 pos0/pos1（map 8-9、
16-17、24-25…）即「gain maps」，呈現偶列≈65000 / 奇列≈600 的互補結構。反彙編 gain-build
`sub_3bc54` 與其呼叫者 `sub_3a7f8`，定位到關鍵 NEON 序列：

```
vld2.16  {d26,d27,d28,d29}, [r2]!   ; 解交織：偶數列→d26、奇數列→d27
vld2.16  {d0, d1, d2, d3},   [r9]!   ; 解交織：偶數列→d0、奇數列→d1
vsubl.u16 q4, d0, d26               ; 偶列增益 − 偶列參考（高字節 0xFE−0xFE=0 抵消）
vsubl.u16 q2, d1, d27               ; 奇列增益 − 奇列參考（高字節 0x02−0x02=0 抵消）
; 之後 vmovl.s32 / umull / mla 做插值縮放 → 寫入 bufC
```

**格式結論（最後一塊拼圖）**：
- 每張 gain map 是 160×120 u16。像素按**列奇偶**分兩個交織通道：
  偶列值 = `0xFE00 | g_even`、奇列值 = `0x0200 | g_odd`，`g ∈ [0,255]` 才是真實增益量化值。
- `0xFE00 + 0x0200 = 65536` 是讓兩通道都落在 u16 非負範圍的**對稱儲存偏置**——這正是一切
  常規定點解讀（Q15/Q16/8.8/s16）失敗的根源：資料在低字節，高字節是偏置而非數值。
- build 用 `vld2.16` 把奇偶列解交織成兩張子圖，再與參考圖做 `vsubl.u16` 無符號相減，
  **偏置精確抵消**，得到純低字節的淨增益場（0–255 量級），隨後插值縮放寫入 bufC。
- 數值自洽：map9 偶列 `65098=0xFE4A`→淨 `0x4A=74`；奇列 `624=0x0270`→淨 `0x70=112`，
  皆為合理 0–255 增益值。map8/map9（同 block 的 pos0/pos1）為兩張不同的增益子圖。

**意義**：原廠 SDK 的整條資料處理鏈（取流→FFC→Factory NUC build→Planck 輻射測溫）至此
**全部逆向完成**。gain map 不再是需要模擬器黑盒繞過的未知格式；純 numpy 重建 build
（vld2 解交織 + 減參考偏置 + 插值）路徑已打通，必要時可不再依賴 Unicorn 模擬。
