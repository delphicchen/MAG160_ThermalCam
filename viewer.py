#!/usr/bin/env python3
"""Live thermal viewer for the Magnity 833c camera (PySide6).

Display + spot/region readout. Uses the MagCamera driver (magcam.py). Temperature
is shown in raw sensor counts plus a user-calibratable linear estimate (absolute
°C needs the calibration file — see PROTOCOL.md; the linear two-point calibration
here lets you anchor it against a known reference in the meantime).
"""
import sys, time, json, os
import numpy as np
from PySide6 import QtCore, QtGui, QtWidgets
import matplotlib

from magcam import MagCamera
from radiometry import Radiometry
from enhance import Enhancer
from factory_nuc_grid import FactoryNUC
import ddt

CAL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration.json")
FLAT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flatfield.npy")
GAIN_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gain_nuc.npz")

PALETTES = ["inferno", "ironbow", "jet", "gray", "hot", "viridis", "rainbow", "nipy_spectral"]


def make_lut(name):
    """Return a 256x3 uint8 RGB lookup table for a palette name."""
    if name == "ironbow":
        # classic iron palette via a custom anchor set
        anchors = [(0, (0, 0, 0)), (0.25, (40, 0, 90)), (0.5, (160, 30, 90)),
                   (0.72, (230, 90, 30)), (0.88, (255, 180, 30)), (1.0, (255, 255, 220))]
        xs = np.linspace(0, 1, 256)
        lut = np.zeros((256, 3))
        pts = [a[0] for a in anchors]
        for c in range(3):
            lut[:, c] = np.interp(xs, pts, [a[1][c] for a in anchors])
        return lut.astype(np.uint8)
    cmap = matplotlib.colormaps[name]
    lut = (np.asarray([cmap(i / 255.0)[:3] for i in range(256)]) * 255).astype(np.uint8)
    return lut


class Viewer(QtWidgets.QMainWindow):
    def __init__(self, cam):
        super().__init__()
        self.cam = cam
        self.setWindowTitle(f"Magnity Thermal  {cam.W}x{cam.H} @ {cam.fps}fps")
        self.luts = {p: make_lut(p) for p in PALETTES}
        self.palette = "ironbow"
        self.auto_range = True
        self.lo = 0
        self.hi = 65535
        self.paused = False
        self.mirror = False                 # left-right flip
        self.nn_superres_on = False          # neural (ONNX ESPCN) super-resolution
        self.sr_scale = 4
        self.auto_ffc = False
        # Firmware-style FFC trigger: re-FFC when the FPA/sensor temperature drifts past a
        # threshold (the real reason the shutter is needed), with a time fallback.
        self.ffc_temp_threshold = 120       # raw sensor counts of drift since last FFC
        self.auto_ffc_max_interval = 120    # seconds (fallback if temp is stable)
        self.last_ffc_temp = None           # sensor_temp_raw at the last FFC
        self.last_ffc_time = 0.0
        self.cursor = None          # (x,y) live hover position in frame coords
        self.cal_target = None       # (x,y) LOCKED calibration sample point (click to set)
        self.cal_box = 2             # half-size: averages a (2*cal_box+1)^2 region (5x5)
        self.last_frame = None
        self.review_ddt = None       # dict from ddt.load() when reviewing a saved snapshot
        # Tier-1 image-quality pipeline (display); measurement uses the BPC+temporal data
        self.enhancer = Enhancer(cam.W, cam.H)
        if os.path.exists(FLAT_PATH):
            try:
                self.enhancer.flat_map = np.load(FLAT_PATH)
            except Exception:
                pass
        if os.path.exists(GAIN_PATH):                # two-point per-pixel gain NUC (supersedes flat)
            try:
                d = np.load(GAIN_PATH)
                self.enhancer.gain_a, self.enhancer.gain_b = d["a"], d["b"]
            except Exception:
                pass
        self._gain_cold = None                       # cold burst held between the 2 capture steps
        # factory per-pixel radiometric NUC (reversed from mag_cali.bin, FPA-temp grid).
        # Self-contained per-pixel piecewise correction; when ON it replaces FFC/flat/gain.
        self.fnuc = FactoryNUC()
        self.factory_nuc_on = False
        # radiometric temperature (built-in Planck curve + reference-point calibration)
        self.radio = Radiometry()
        self.cal_points = []        # list of [raw_value, known_celsius]
        self._load_cal()
        self._fps_t = time.time(); self._fps_n = 0; self._fps = 0.0
        self._build_ui()
        self.timer = QtCore.QTimer(self); self.timer.timeout.connect(self.update_frame)
        self.timer.start(int(1000 / max(1, cam.fps)))
        self.ffc_timer = QtCore.QTimer(self); self.ffc_timer.timeout.connect(self._auto_ffc_tick)

    def _grab(self, timeout=0.0):
        """Single frame entry point: applies the left-right mirror so every consumer
        (display, measurement, bad-pixel & flat-field learning) stays consistent."""
        f = self.cam.get_frame(timeout=timeout)
        if f is not None and self.mirror:
            f = np.ascontiguousarray(np.fliplr(f))
        return f

    def _auto_ffc_tick(self):
        """Polled ~every 2 s. Re-FFC when the sensor temperature has drifted past the
        threshold since the last FFC, or after a max-interval fallback — mirroring the
        firmware, which keys the shutter off FPA-temperature change, not a fixed timer."""
        if not self.auto_ffc or self.paused:
            return
        cur = self.cam.sensor_temp_raw()
        drift = (abs(cur - self.last_ffc_temp)
                 if (cur is not None and self.last_ffc_temp is not None) else None)
        elapsed = time.time() - self.last_ffc_time
        if (drift is not None and drift >= self.ffc_temp_threshold) or \
           elapsed >= self.auto_ffc_max_interval:
            self.do_ffc(light=True)

    def set_auto_ffc(self, on):
        self.auto_ffc = on
        if on:
            # seed the baseline so we don't immediately fire, then poll at 2 s
            self.last_ffc_temp = self.cam.sensor_temp_raw()
            self.last_ffc_time = time.time()
            self.ffc_timer.start(2000)
        else:
            self.ffc_timer.stop()

    def _build_ui(self):
        central = QtWidgets.QWidget(); self.setCentralWidget(central)
        h = QtWidgets.QHBoxLayout(central)

        self.image_label = QtWidgets.QLabel(); self.image_label.setMinimumSize(640, 480)
        self.image_label.setAlignment(QtCore.Qt.AlignCenter)
        self.image_label.setMouseTracking(True)
        self.image_label.installEventFilter(self)
        self.image_label.setStyleSheet("background:#111;")
        h.addWidget(self.image_label, 1)

        side = QtWidgets.QVBoxLayout(); h.addLayout(side)

        side.addWidget(QtWidgets.QLabel("Palette"))
        self.cb_pal = QtWidgets.QComboBox(); self.cb_pal.addItems(PALETTES)
        self.cb_pal.setCurrentText(self.palette)
        self.cb_pal.currentTextChanged.connect(lambda t: setattr(self, "palette", t))
        side.addWidget(self.cb_pal)

        self.chk_auto = QtWidgets.QCheckBox("Auto range"); self.chk_auto.setChecked(True)
        self.chk_auto.toggled.connect(lambda v: setattr(self, "auto_range", v))
        side.addWidget(self.chk_auto)

        self.btn_ffc = QtWidgets.QPushButton("FFC (shutter)")
        self.btn_ffc.clicked.connect(lambda: self.do_ffc(False)); side.addWidget(self.btn_ffc)
        self.chk_autoffc = QtWidgets.QCheckBox("Auto-FFC (FPA drift)")
        self.chk_autoffc.setToolTip(
            f"Re-FFC automatically when the FPA/sensor temperature drifts "
            f"≥{self.ffc_temp_threshold} counts (or after {self.auto_ffc_max_interval}s) — "
            "this is the firmware's actual shutter trigger.")
        self.chk_autoffc.toggled.connect(self.set_auto_ffc); side.addWidget(self.chk_autoffc)
        self.chk_mirror = QtWidgets.QCheckBox("Mirror (left-right)")
        self.chk_mirror.toggled.connect(lambda v: setattr(self, "mirror", v))
        side.addWidget(self.chk_mirror)

        self.btn_pause = QtWidgets.QPushButton("Pause")
        self.btn_pause.setCheckable(True)
        self.btn_pause.toggled.connect(self._toggle_pause); side.addWidget(self.btn_pause)

        self.btn_snap = QtWidgets.QPushButton("Snapshot")
        self.btn_snap.clicked.connect(self.snapshot); side.addWidget(self.btn_snap)

        side.addSpacing(8)
        side.addWidget(QtWidgets.QLabel("Enhance"))
        self.btn_flat = QtWidgets.QPushButton("Flat-field cal (uniform)")
        self.btn_flat.setToolTip("Point the camera at a uniform-temperature surface "
                                 "(wall, glass, hand-warmed plate), then click to remove "
                                 "the vignette / shading / column stripes.")
        self.btn_flat.clicked.connect(self.do_flatfield); side.addWidget(self.btn_flat)
        self.btn_gain = QtWidgets.QPushButton("Gain NUC: capture COLD")
        self.btn_gain.setToolTip(
            "Two-point per-pixel GAIN correction (the responsivity flat-field that the "
            "offset-only flat-field can't do — see recon/EMULATION_NUC.md).\n"
            "Step 1: aim at a uniform COOL surface (wall) and click.\n"
            "Step 2: aim at a uniform WARMER surface (palm/warm plate) and click.\n"
            "Right-click to reset / clear.")
        self.btn_gain.clicked.connect(self.do_gain_nuc)
        self.btn_gain.setContextMenuPolicy(QtCore.Qt.CustomContextMenu)
        self.btn_gain.customContextMenuRequested.connect(lambda *_: self.clear_gain_nuc())
        side.addWidget(self.btn_gain)
        self.chk_factory = QtWidgets.QCheckBox("Factory NUC (radiometric)")
        self.chk_factory.setToolTip(
            "Per-pixel factory NUC reversed from mag_cali.bin (bit-exact to the camera\n"
            "firmware), keyed on the live FPA temperature. Self-contained per-pixel\n"
            "piecewise correction — when ON it replaces shutter-FFC / flat-field / gain.\n"
            "Needs factory_nuc_grid.npz (build with recon/build_nuc_grid.py).")
        self.chk_factory.setEnabled(self.fnuc.ok)
        if not self.fnuc.ok:
            self.chk_factory.setText("Factory NUC (grid missing)")
        self.chk_factory.toggled.connect(self._toggle_factory_nuc)
        side.addWidget(self.chk_factory)
        self.chk_flat = QtWidgets.QCheckBox("Flat-field correct"); self.chk_flat.setChecked(True)
        self.chk_flat.toggled.connect(lambda v: setattr(self.enhancer, "flatfield", v))
        side.addWidget(self.chk_flat)
        self.chk_bpc = QtWidgets.QCheckBox("Bad-pixel correct"); self.chk_bpc.setChecked(True)
        self.chk_bpc.toggled.connect(lambda v: setattr(self.enhancer, "bpc", v))
        side.addWidget(self.chk_bpc)
        self.chk_temporal = QtWidgets.QCheckBox("Temporal denoise"); self.chk_temporal.setChecked(True)
        self.chk_temporal.toggled.connect(lambda v: setattr(self.enhancer, "temporal", v))
        side.addWidget(self.chk_temporal)
        self.chk_spatial = QtWidgets.QCheckBox("Spatial denoise"); self.chk_spatial.setChecked(True)
        self.chk_spatial.toggled.connect(lambda v: setattr(self.enhancer, "spatial", v))
        side.addWidget(self.chk_spatial)
        sr_layout = QtWidgets.QHBoxLayout()
        self.chk_nn_sr = QtWidgets.QCheckBox("Neural super-res")
        self.chk_nn_sr.setToolTip("Single-frame neural super-resolution (ESPCN).\n"
                                  "Pre-smooths then upscales via OpenVINO/ONNX Runtime.\n"
                                  "Train with train_sr.py.")
        self.chk_nn_sr.toggled.connect(self._toggle_nn_sr)
        sr_layout.addWidget(self.chk_nn_sr)

        self.cb_sr_scale = QtWidgets.QComboBox()
        self.cb_sr_scale.addItems(["2x", "4x"])
        self.cb_sr_scale.setCurrentText(f"{self.sr_scale}x")
        self.cb_sr_scale.currentTextChanged.connect(self._change_sr_scale)
        sr_layout.addWidget(self.cb_sr_scale)
        self.btn_clear_model = QtWidgets.QPushButton("Clear model")
        self.btn_clear_model.setToolTip("Delete the trained neural SR model for the current "
                                        "scale (.onnx/.pt) and revert to bicubic upscaling.")
        self.btn_clear_model.clicked.connect(self._clear_nn_model)
        sr_layout.addWidget(self.btn_clear_model)
        side.addLayout(sr_layout)

        side.addSpacing(8)
        side.addWidget(QtWidgets.QLabel("Temperature (°C)"))
        side.addWidget(QtWidgets.QLabel("① click image to drop a CAL marker\n② press button, enter its °C"))
        self.btn_addcal = QtWidgets.QPushButton("+ Add cal point")
        self.btn_addcal.setToolTip(
            "Left-click the image to LOCK a green CAL marker on an object of known "
            "temperature (a 5×5 region is averaged — the marker stays put while you reach "
            "the button). Then press this and enter its °C. Right-click clears the marker. "
            "Need ≥2 points at different temperatures.")
        self.btn_addcal.clicked.connect(self.add_cal_point); side.addWidget(self.btn_addcal)
        self.btn_clearcal = QtWidgets.QPushButton("Clear calibration")
        self.btn_clearcal.clicked.connect(self.clear_cal); side.addWidget(self.btn_clearcal)

        ddt_row = QtWidgets.QHBoxLayout()
        self.btn_save_ddt = QtWidgets.QPushButton("Save DDT")
        self.btn_save_ddt.setToolTip("Save a radiometric snapshot (.ddt): the measurement "
                                     "frame + FPA temp + calibration, re-measurable offline.")
        self.btn_save_ddt.clicked.connect(self.save_ddt_file); ddt_row.addWidget(self.btn_save_ddt)
        self.btn_load_ddt = QtWidgets.QPushButton("Load DDT…")
        self.btn_load_ddt.setToolTip("Open a saved .ddt to review it frozen and calibrate "
                                     "temperature from it (drop CAL markers, add points).")
        self.btn_load_ddt.clicked.connect(self.load_ddt_file); ddt_row.addWidget(self.btn_load_ddt)
        side.addLayout(ddt_row)

        side.addSpacing(10)
        self.lbl_read = QtWidgets.QLabel("—")
        self.lbl_read.setStyleSheet("font-family:monospace; font-size:12px;")
        self.lbl_read.setTextFormat(QtCore.Qt.RichText)
        side.addWidget(self.lbl_read)
        side.addStretch(1)

        self.statusBar().showMessage("starting…")

    # ---- controls ----
    def _change_sr_scale(self, text):
        new_scale = int(text[0])
        self.sr_scale = new_scale
        # If SR is currently active, we need to verify the newly selected scale model is trained
        if self.nn_superres_on:
            self._toggle_nn_sr(True)

    def _toggle_nn_sr(self, v):
        self.nn_superres_on = v
        if v:
            # Check if trained model exists for the current scale
            model_dir = os.path.dirname(os.path.abspath(__file__))
            pt_path = os.path.join(model_dir, f"thermal_espcn_{self.sr_scale}x.pt")
            if not os.path.exists(pt_path):
                reply = QtWidgets.QMessageBox.question(
                    self, "Train Neural Network",
                    f"偵測到尚未訓練 {self.sr_scale}x 神經網路模型（目前為預設隨機權重，畫面會較模糊）。\n\n"
                    "強烈建議使用您的相機資料進行訓練以獲得最佳的高解析度畫質。\n"
                    "是否現在進行自動採集與訓練？（約需 5-10 分鐘，期間請慢慢移動相機拍攝不同場景）",
                    QtWidgets.QMessageBox.Yes | QtWidgets.QMessageBox.No,
                    QtWidgets.QMessageBox.Yes
                )
                if reply == QtWidgets.QMessageBox.Yes:
                    self._collect_and_train()

    def _collect_and_train(self):
        self.statusBar().showMessage("Collecting 500 frames for training... Please move the camera around slowly.")
        frames = []
        t0 = time.time()
        # Collect 500 frames (approx 20 seconds at 25fps)
        while len(frames) < 500:
            r = self._grab(0.2)
            if r is not None:
                # Need raw frames for training (not enhanced with flat-field/bpc yet)
                # We use the raw grabbed frame (mirror applied)
                frames.append(r)
                if len(frames) % 25 == 0:
                    self.statusBar().showMessage(f"Collecting frames: {len(frames)}/500... Keep moving camera.")
            QtWidgets.QApplication.processEvents()
            time.sleep(0.02)
        
        self.statusBar().showMessage("Saving frames...")
        QtWidgets.QApplication.processEvents()
        
        frames_out = np.stack([x.astype(np.float32) for x in frames])
        out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sr_training_frames.npy')
        np.save(out_path, frames_out)
        
        self.statusBar().showMessage("Training started...")
        
        # Launch training in a separate window
        dialog = TrainingDialog(self.sr_scale, self)
        dialog.start_training()
        dialog.exec()  # Block until done or closed
        
        # Force reload of the ONNX models dict if it was loaded
        self.enhancer._nn_sr = {}
        self.statusBar().showMessage(f"{self.sr_scale}x model reloaded.")

    def _toggle_pause(self, v): self.paused = v

    def _toggle_factory_nuc(self, on):
        self.factory_nuc_on = on
        # the factory NUC uses the shutter dark frame as its per-pixel offset reference;
        # make sure one exists (else apply would fall back to the wrong grid ref).
        if on and getattr(self.cam, "_ffc_ref", None) is None:
            self.statusBar().showMessage("Factory NUC: capturing shutter reference…")
            QtWidgets.QApplication.processEvents()
            self.do_ffc(light=True)

    def do_ffc(self, light=False):
        self.statusBar().showMessage("FFC: closing shutter…")
        QtWidgets.QApplication.processEvents()
        ok = self.cam.trigger_ffc()
        self.enhancer.reset_temporal()      # FFC changed the per-pixel reference
        # reset the sensor-temp drift baseline so auto-FFC measures drift from *now*
        self.last_ffc_temp = self.cam.sensor_temp_raw()
        self.last_ffc_time = time.time()
        if not light:
            self.learn_bad_pixels()         # manual FFC also refreshes the bad-pixel map
        self.statusBar().showMessage(("auto-FFC" if light else "FFC") + (" done" if ok else " failed"), 2500)

    def learn_bad_pixels(self, n=25):
        """Capture a short burst and learn the persistent bad-pixel map."""
        self.statusBar().showMessage("learning bad-pixel map…")
        QtWidgets.QApplication.processEvents()
        frames = []
        t0 = time.time()
        while len(frames) < n and time.time() - t0 < 2.5:
            r = self._grab(0.2)                   # same (FFC-corrected, mirrored) data we display
            if r is not None:
                frames.append(r)
            QtWidgets.QApplication.processEvents()
            time.sleep(0.02)
        if len(frames) >= 8:
            nb = self.enhancer.learn_bad_pixels(frames)
            self.statusBar().showMessage(f"bad-pixel map: {nb} pixels", 3000)

    def do_flatfield(self, n=40):
        """Capture a uniform-target burst and build the shading/flat-field correction."""
        self.statusBar().showMessage("flat-field: hold steady on the uniform surface…")
        QtWidgets.QApplication.processEvents()
        frames = []
        t0 = time.time()
        # use the raw FFC-corrected frame WITHOUT the existing flat map, so it's rebuilt fresh
        prev = self.enhancer.flat_map; self.enhancer.flat_map = None
        while len(frames) < n and time.time() - t0 < 4.0:
            r = self._grab(0.2)
            if r is not None:
                frames.append(r)
            QtWidgets.QApplication.processEvents()
            time.sleep(0.02)
        if len(frames) >= 10:
            res = self.enhancer.capture_flatfield(frames)
            np.save(FLAT_PATH, self.enhancer.flat_map)
            self.statusBar().showMessage(
                f"flat-field captured (removed ±{res:.0f} cnt shading)", 5000)
        else:
            self.enhancer.flat_map = prev
            self.statusBar().showMessage("flat-field failed (not enough frames)", 4000)

    def clear_flatfield(self):
        self.enhancer.clear_flatfield()
        try: os.remove(FLAT_PATH)
        except OSError: pass
        try: os.remove(GAIN_PATH)
        except OSError: pass
        self._gain_cold = None
        self.btn_gain.setText("Gain NUC: capture COLD")
        self.statusBar().showMessage("flat-field + gain NUC cleared", 3000)

    def _capture_burst(self, msg, n=40, secs=4.0):
        """Grab a steady burst of FFC-corrected frames (no flat/gain applied) for calibration."""
        self.statusBar().showMessage(msg)
        QtWidgets.QApplication.processEvents()
        pa, pb, pf = self.enhancer.gain_a, self.enhancer.gain_b, self.enhancer.flat_map
        self.enhancer.gain_a = self.enhancer.gain_b = self.enhancer.flat_map = None
        frames, t0 = [], time.time()
        while len(frames) < n and time.time() - t0 < secs:
            r = self._grab(0.2)
            if r is not None:
                frames.append(r)
            QtWidgets.QApplication.processEvents()
            time.sleep(0.02)
        self.enhancer.gain_a, self.enhancer.gain_b, self.enhancer.flat_map = pa, pb, pf
        return frames

    def do_gain_nuc(self):
        """Two-point per-pixel gain NUC: step 1 captures the COLD uniform burst, step 2 the
        WARM one, then solves the per-pixel affine (offset+gain). See enhance.capture_flatfield_2pt."""
        if self._gain_cold is None:
            frames = self._capture_burst("Gain NUC step 1/2: hold steady on a COOL uniform surface…")
            if len(frames) < 10:
                self.statusBar().showMessage("gain NUC: not enough frames (try again)", 4000); return
            self._gain_cold = frames
            self.btn_gain.setText("Gain NUC: capture WARM")
            self.statusBar().showMessage(
                "COLD captured ✓ — now aim at a WARMER uniform surface (palm/warm plate) and click again", 8000)
            return
        frames = self._capture_burst("Gain NUC step 2/2: hold steady on a WARMER uniform surface…")
        if len(frames) < 10:
            self.statusBar().showMessage("gain NUC: not enough frames (try again)", 4000); return
        gstd, span = self.enhancer.capture_flatfield_2pt(self._gain_cold, frames)
        self._gain_cold = None
        self.btn_gain.setText("Gain NUC: capture COLD")
        if span < 200:
            self.statusBar().showMessage(
                f"gain NUC: the two surfaces were too close in level (Δ{span:.0f} cnt) — "
                "use a clearly warmer second target", 6000)
            return
        np.savez(GAIN_PATH, a=self.enhancer.gain_a, b=self.enhancer.gain_b)
        try: os.remove(FLAT_PATH)            # the affine supersedes the offset-only flat map
        except OSError: pass
        self.statusBar().showMessage(
            f"gain NUC built ✓ (Δ{span:.0f} cnt, per-pixel gain std {gstd:.3f}) → saved", 6000)

    def clear_gain_nuc(self):
        self.enhancer.gain_a = self.enhancer.gain_b = None
        self._gain_cold = None
        self.btn_gain.setText("Gain NUC: capture COLD")
        try: os.remove(GAIN_PATH)
        except OSError: pass
        self.statusBar().showMessage("gain NUC cleared", 3000)
    def snapshot(self):
        pm = self.image_label.pixmap()
        if pm:
            fn = time.strftime("/tmp/mag_%Y%m%d_%H%M%S.png")
            pm.save(fn); self.statusBar().showMessage(f"saved {fn}", 4000)

    # ---- mouse ----
    def eventFilter(self, obj, ev):
        if obj is self.image_label:
            if ev.type() == QtCore.QEvent.MouseMove:
                self._map_cursor(ev.position().x(), ev.position().y())
            elif ev.type() == QtCore.QEvent.MouseButtonPress:
                fxy = self._frame_xy(ev.position().x(), ev.position().y())
                if ev.button() == QtCore.Qt.LeftButton and fxy is not None:
                    self.cal_target = fxy        # lock the calibration sample point
                elif ev.button() == QtCore.Qt.RightButton:
                    self.cal_target = None        # right-click clears it
        return super().eventFilter(obj, ev)

    def _frame_xy(self, px, py):
        """Map a label pixel to (fx,fy) in measurement-frame coords, or None if outside."""
        if self._disp_rect is None:
            return None
        x0, y0, w, h = self._disp_rect
        if w <= 0 or h <= 0:
            return None
        fx = int((px - x0) / w * self.cam.W)
        fy = int((py - y0) / h * self.cam.H)
        if 0 <= fx < self.cam.W and 0 <= fy < self.cam.H:
            return (fx, fy)
        return None

    def _map_cursor(self, px, py):
        self.cursor = self._frame_xy(px, py)

    def _region_raw(self, x, y):
        """Averaged raw counts over the (2*cal_box+1)^2 window around (x,y) — mirrors the
        firmware's window-averaged temperature probe (sub_45014), and is far steadier than
        a single noisy pixel for calibration."""
        if self.last_frame is None:
            return None
        b = self.cal_box
        x0, x1 = max(0, x - b), min(self.cam.W, x + b + 1)
        y0, y1 = max(0, y - b), min(self.cam.H, y + b + 1)
        return int(round(float(np.mean(self.last_frame[y0:y1, x0:x1]))))

    def counts_to_temp(self, c):
        if not self.radio.calibrated:
            return None
        return float(self.radio.raw_to_celsius(c))

    # ---- temperature calibration ----
    def _load_cal(self):
        try:
            with open(CAL_PATH) as f:
                d = json.load(f)
            self.cal_points = d.get("points", [])
            if d.get("calibrated"):
                self.radio.a = d["a"]; self.radio.b = d["b"]
                self.radio.lut_idx = d["lut_idx"]; self.radio.calibrated = True
        except Exception:
            pass

    def _save_cal(self):
        d = dict(points=self.cal_points, calibrated=self.radio.calibrated,
                 a=self.radio.a, b=self.radio.b, lut_idx=self.radio.lut_idx)
        try:
            with open(CAL_PATH, "w") as f:
                json.dump(d, f, indent=2)
        except Exception as e:
            self.statusBar().showMessage(f"save cal failed: {e}", 4000)

    def add_cal_point(self):
        if self.last_frame is None:
            return
        # use the LOCKED target (click the image to set it); fall back to hover, then centre
        if self.cal_target is not None:
            cx, cy = self.cal_target
        elif self.cursor is not None:
            cx, cy = self.cursor
        else:
            cx, cy = self.cam.W // 2, self.cam.H // 2
        if self.cal_target is None:
            QtWidgets.QMessageBox.information(
                self, "Pick a point first",
                "Click on the image to drop a calibration marker on the object of known "
                "temperature, then press “+ Add cal point”. (Right-click clears it.)")
            self.cal_target = (cx, cy)
            return
        raw = self._region_raw(cx, cy)
        n = 2 * self.cal_box + 1
        t, ok = QtWidgets.QInputDialog.getDouble(
            self, "Add calibration point",
            f"Locked point ({cx},{cy}), {n}×{n} avg raw = {raw}\n"
            f"Enter the known temperature of that spot (°C):",
            25.0, -50.0, 1000.0, 1)
        if not ok:
            return
        self.cal_points.append([raw, float(t)])
        if len(self.cal_points) >= 2:
            rms, n = self.radio.calibrate(self.cal_points)
            self.statusBar().showMessage(
                f"calibrated: {n} pts, lut={self.radio.lut_idx}, rms={rms:.2f}°C", 6000)
        else:
            self.statusBar().showMessage(
                f"1 reference point stored (need ≥2 to calibrate)", 5000)
        self._save_cal()

    def clear_cal(self):
        self.cal_points = []
        self.radio.calibrated = False
        self._save_cal()
        self.statusBar().showMessage("calibration cleared", 4000)

    # ---- DDT radiometric snapshots ----
    def save_ddt_file(self):
        if self.last_frame is None:
            self.statusBar().showMessage("no frame to save", 3000); return
        default = time.strftime("%Y-%m-%d_%H-%M-%S.ddt")
        fn, _ = QtWidgets.QFileDialog.getSaveFileName(
            self, "Save DDT snapshot", default, "DDT radiometric (*.ddt)")
        if not fn:
            return
        if not fn.lower().endswith(".ddt"):
            fn += ".ddt"
        try:
            ddt.save(fn, self.last_frame, fpa_raw=self.cam.sensor_temp_raw(),
                     a=self.radio.a, b=self.radio.b, lut_idx=self.radio.lut_idx,
                     calibrated=self.radio.calibrated)
            self.statusBar().showMessage(f"saved {os.path.basename(fn)}", 4000)
        except Exception as e:
            self.statusBar().showMessage(f"save DDT failed: {e}", 5000)

    def load_ddt_file(self):
        # while reviewing, this button resumes the live stream
        if self.review_ddt is not None:
            self._exit_ddt_review(); return
        fn, _ = QtWidgets.QFileDialog.getOpenFileName(
            self, "Load DDT snapshot", "", "DDT radiometric (*.ddt)")
        if not fn:
            return
        try:
            d = ddt.load(fn)
        except Exception as e:
            self.statusBar().showMessage(f"load DDT failed: {e}", 5000); return
        if d["W"] != self.cam.W or d["H"] != self.cam.H:
            self.statusBar().showMessage(
                f"DDT geometry {d['W']}x{d['H']} != camera {self.cam.W}x{self.cam.H}", 5000)
            return
        # freeze: feed the snapshot in as last_frame so the click-to-lock CAL + Add cal point
        # workflow operates on it exactly like a live frame.
        self.review_ddt = d
        self.paused = True
        self.last_frame = d["frame"].astype(np.float32)
        self.cal_target = None
        self.btn_load_ddt.setText("Resume live ▶")
        self.btn_save_ddt.setEnabled(False)
        self.statusBar().showMessage(
            f"reviewing {d['name']}  —  click a known-temp spot, then “+ Add cal point”", 8000)
        self.update_frame()

    def _exit_ddt_review(self):
        self.review_ddt = None
        self.paused = False
        self.btn_load_ddt.setText("Load DDT…")
        self.btn_save_ddt.setEnabled(True)
        self.statusBar().showMessage("resumed live stream", 3000)

    # ---- neural SR model management ----
    def _clear_nn_model(self):
        model_dir = os.path.dirname(os.path.abspath(__file__))
        # .onnx (graph), .onnx.data (external weights sidecar), .pt (training checkpoint)
        files = [os.path.join(model_dir, f"thermal_espcn_{self.sr_scale}x.{ext}")
                 for ext in ("onnx", "onnx.data", "pt")]
        existing = [f for f in files if os.path.exists(f)]
        if not existing:
            self.statusBar().showMessage(f"no {self.sr_scale}x model to clear", 4000); return
        if QtWidgets.QMessageBox.question(
                self, "Clear neural model",
                f"Delete the trained {self.sr_scale}x model and revert to bicubic?\n\n"
                + "\n".join(os.path.basename(f) for f in existing)) \
                != QtWidgets.QMessageBox.Yes:
            return
        removed = []
        for f in existing:
            try:
                os.remove(f); removed.append(os.path.basename(f))
            except Exception as e:
                self.statusBar().showMessage(f"could not remove {os.path.basename(f)}: {e}", 5000)
        self.enhancer._nn_sr = {}            # drop any cached loaded model -> bicubic fallback
        if self.nn_superres_on:
            self.chk_nn_sr.setChecked(False)  # turn it off (also flips nn_superres_on)
        self.statusBar().showMessage(f"cleared {self.sr_scale}x model: {', '.join(removed)}", 5000)

    # ---- render ----
    _disp_rect = None
    def update_frame(self):
        if self.paused and self.last_frame is not None:
            frame = self.last_frame
            disp = self.enhancer.enhance_display(frame)
        else:
            if self.factory_nuc_on and self.fnuc.ok:
                # Factory per-pixel NUC needs the UNCORRECTED raw + the camera's
                # shutter dark frame as the offset reference (the grid's own ref is a
                # radiance-domain stand-in, wrong on live raw). Tables are in sensor
                # orientation, so apply BEFORE the mirror, then flip the result.
                raw_native = self.cam.get_raw(timeout=0.0)
                if raw_native is None:
                    return
                fpa = self.cam.sensor_temp_raw()
                clean = self.fnuc.apply(raw_native, fpa if fpa is not None else 20000,
                                        offset_ref=self.cam._ffc_ref)
                if self.mirror:
                    clean = np.ascontiguousarray(np.fliplr(clean))
                if self.enhancer.bpc:               # mask dead/blinking pixels (factory
                    clean, _ = self.enhancer._correct_bad(clean)   # NUC leaves them in)
            else:
                raw = self._grab(0.0)               # FFC-corrected (get_frame), mirror applied
                if raw is None:
                    return
                clean = self.enhancer.clean(raw)    # per-frame value-safe (bad-pixel + flat-field)
            # measurement-grade frame: + motion-adaptive temporal (value-safe)
            frame = self.enhancer.temporal_step(clean)
            self.last_frame = frame
            # display layer
            if self.nn_superres_on:
                disp = self.enhancer.neural_superres(frame, self.sr_scale)
            else:
                disp = self.enhancer.enhance_display(frame)
        f = disp.astype(np.float32)
        if self.auto_range:
            lo, hi = np.percentile(f, 1), np.percentile(f, 99)
        else:
            lo, hi = self.lo, self.hi
        norm = np.clip((f - lo) / max(1.0, hi - lo) * 255.0, 0, 255).astype(np.uint8)
        rgb = self.luts[self.palette][norm]            # HxWx3
        H, W, _ = rgb.shape
        qimg = QtGui.QImage(rgb.tobytes(), W, H, 3 * W, QtGui.QImage.Format_RGB888)
        # scale preserving aspect to label
        lbl = self.image_label.size()
        pm = QtGui.QPixmap.fromImage(qimg).scaled(lbl, QtCore.Qt.KeepAspectRatio,
                                                  QtCore.Qt.FastTransformation)
        # compute displayed rect for cursor mapping
        dx = (lbl.width() - pm.width()) / 2
        dy = (lbl.height() - pm.height()) / 2
        self._disp_rect = (dx, dy, pm.width(), pm.height())
        # markers/cursor live in measurement-pixel coords (cam.W×cam.H), independent of
        # the display resolution (which may be super-res 2×)
        sx, sy = pm.width() / self.cam.W, pm.height() / self.cam.H
        # overlay min/max/cursor markers
        painter = QtGui.QPainter(pm)
        mn = np.unravel_index(np.argmin(frame), frame.shape)
        mx = np.unravel_index(np.argmax(frame), frame.shape)
        self._mark(painter, mx[1]*sx, mx[0]*sy, QtGui.QColor(255, 60, 60), "H")
        self._mark(painter, mn[1]*sx, mn[0]*sy, QtGui.QColor(80, 160, 255), "C")
        if self.cursor:
            cx, cy = self.cursor
            self._mark(painter, cx*sx, cy*sy, QtGui.QColor(255, 255, 0), "+")
        if self.cal_target is not None:
            tx, ty = self.cal_target
            self._mark_box(painter, tx, ty, sx, sy,
                           QtGui.QColor(60, 255, 120), "CAL")
        painter.end()
        self.image_label.setPixmap(pm)

        # readout
        def fmt(v):
            t = self.counts_to_temp(v)
            return f"{v} cnt" + (f" / {t:.1f}°C" if t is not None else "")
        txt = (f"<b>max</b> {fmt(int(frame.max()))}<br>"
               f"<b>min</b> {fmt(int(frame.min()))}<br>"
               f"<b>ctr</b> {fmt(int(frame[self.cam.H//2, self.cam.W//2]))}")
        if self.cursor:
            cx, cy = self.cursor
            txt += f"<br><b>cur</b> ({cx},{cy}) {fmt(int(frame[cy, cx]))}"
        if self.cal_target is not None:
            tx, ty = self.cal_target
            rv = self._region_raw(tx, ty)
            n = 2 * self.cal_box + 1
            txt += (f"<br><b style='color:#3cf078'>CAL</b> ({tx},{ty}) "
                    f"{n}×{n}avg {fmt(rv) if rv is not None else '—'}")
        self.lbl_read.setText(txt)

        self._fps_n += 1
        if time.time() - self._fps_t >= 1.0:
            self._fps = self._fps_n / (time.time() - self._fps_t)
            self._fps_t = time.time(); self._fps_n = 0
        if self.review_ddt is not None:
            d = self.review_ddt
            fpa = d["fpa_raw"]
            self.statusBar().showMessage(
                f"DDT review: {d['name']}" + (f"   FPA={fpa}" if fpa is not None else "")
                + "   (frozen — click a known-temp spot then “+ Add cal point”)")
            return
        st = self.cam.sensor_temp_raw()
        sttxt = f"   FPA={st}" if st is not None else ""
        if st is not None and self.last_ffc_temp is not None:
            sttxt += f" (Δ{st - self.last_ffc_temp:+d})"
        self.statusBar().showMessage(f"{self._fps:.1f} fps   frames={self.cam.frame_count}{sttxt}")

    def _mark(self, p, x, y, color, label):
        p.setPen(QtGui.QPen(color, 2))
        p.drawLine(int(x-6), int(y), int(x+6), int(y))
        p.drawLine(int(x), int(y-6), int(x), int(y+6))
        p.drawText(int(x+7), int(y-7), label)

    def _mark_box(self, p, fx, fy, sx, sy, color, label):
        """Crosshair + the actual averaging box, in display pixels (fx,fy in frame coords)."""
        cx, cy = fx * sx, fy * sy
        bw, bh = (self.cal_box + 0.5) * sx, (self.cal_box + 0.5) * sy
        p.setPen(QtGui.QPen(color, 2))
        p.drawRect(int(cx - bw), int(cy - bh), int(2 * bw), int(2 * bh))
        p.drawLine(int(cx-8), int(cy), int(cx+8), int(cy))
        p.drawLine(int(cx), int(cy-8), int(cx), int(cy+8))
        p.drawText(int(cx + bw + 3), int(cy - bh - 3), label)

    def closeEvent(self, ev):
        self.timer.stop()
        try: self.cam.stop(); self.cam.close()
        except Exception: pass
        super().closeEvent(ev)


class TrainingDialog(QtWidgets.QDialog):
    def __init__(self, scale, parent=None):
        super().__init__(parent)
        self.scale = scale
        self.setWindowTitle(f"Training Neural SR Model ({scale}x)")
        self.resize(600, 400)
        layout = QtWidgets.QVBoxLayout(self)
        
        self.log_view = QtWidgets.QTextEdit()
        self.log_view.setReadOnly(True)
        self.log_view.setStyleSheet("font-family: monospace; background-color: #1e1e1e; color: #d4d4d4;")
        layout.addWidget(self.log_view)
        
        self.btn_close = QtWidgets.QPushButton("Close")
        self.btn_close.setEnabled(False)
        self.btn_close.clicked.connect(self.accept)
        layout.addWidget(self.btn_close)
        
        self.process = QtCore.QProcess(self)
        self.process.readyReadStandardOutput.connect(self.handle_stdout)
        self.process.readyReadStandardError.connect(self.handle_stderr)
        self.process.finished.connect(self.process_finished)

    def start_training(self):
        self.log_view.append(f"Starting training script (CPU) for {self.scale}x scale... This may take 5-10 minutes.")
        script_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "train_sr.py")
        self.process.start("python3", [script_path, "--train", "--scale", str(self.scale)])

    def handle_stdout(self):
        data = self.process.readAllStandardOutput()
        stdout = bytes(data).decode("utf8")
        self.log_view.insertPlainText(stdout)
        self.log_view.moveCursor(QtGui.QTextCursor.End)

    def handle_stderr(self):
        data = self.process.readAllStandardError()
        stderr = bytes(data).decode("utf8")
        self.log_view.insertPlainText(stderr)
        self.log_view.moveCursor(QtGui.QTextCursor.End)

    def process_finished(self):
        self.log_view.append("\n=== Training Finished ===")
        self.btn_close.setEnabled(True)


def main():
    cam = MagCamera(); cam.open(); cam.start()
    time.sleep(0.5)
    cam.trigger_ffc()          # auto-FFC on startup for an immediately clean image
    app = QtWidgets.QApplication(sys.argv)
    v = Viewer(cam); v.resize(900, 560); v.show()
    QtCore.QTimer.singleShot(400, v.learn_bad_pixels)   # build bad-pixel map once streaming
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
