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

CAL_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration.json")
FLAT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "flatfield.npy")

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
        self.sr_scale = 2
        self.auto_ffc = False
        self.auto_ffc_interval = 60         # seconds
        self.cursor = None          # (x,y) in frame coords
        self.last_frame = None
        # Tier-1 image-quality pipeline (display); measurement uses the BPC+temporal data
        self.enhancer = Enhancer(cam.W, cam.H)
        if os.path.exists(FLAT_PATH):
            try:
                self.enhancer.flat_map = np.load(FLAT_PATH)
            except Exception:
                pass
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
        if self.auto_ffc and not self.paused:
            self.do_ffc(light=True)         # lightweight: refresh shutter ref, no relearn

    def set_auto_ffc(self, on):
        self.auto_ffc = on
        if on:
            self.ffc_timer.start(self.auto_ffc_interval * 1000)
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
        self.chk_autoffc = QtWidgets.QCheckBox(f"Auto-FFC ({self.auto_ffc_interval}s)")
        self.chk_autoffc.setToolTip("Re-FFC automatically on a timer — the sensor drifts, "
                                    "so the shutter reference needs periodic refreshing.")
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
        self.chk_nn_sr = QtWidgets.QCheckBox("Super-res ×2 (neural)")
        self.chk_nn_sr.setToolTip("Single-frame neural super-resolution (ESPCN).\n"
                                  "Pre-smooths then upscales via OpenVINO/ONNX Runtime.\n"
                                  "~2 ms (Intel iGPU) / ~3 ms (CPU). Train with train_sr.py.")
        self.chk_nn_sr.toggled.connect(lambda v: setattr(self, 'nn_superres_on', v))
        side.addWidget(self.chk_nn_sr)

        side.addSpacing(8)
        side.addWidget(QtWidgets.QLabel("Temperature (°C)"))
        self.btn_addcal = QtWidgets.QPushButton("+ Add cal point")
        self.btn_addcal.setToolTip("Point the cursor at an object of known temperature, "
                                   "then click and enter its °C. Need ≥2 points.")
        self.btn_addcal.clicked.connect(self.add_cal_point); side.addWidget(self.btn_addcal)
        self.btn_clearcal = QtWidgets.QPushButton("Clear calibration")
        self.btn_clearcal.clicked.connect(self.clear_cal); side.addWidget(self.btn_clearcal)

        side.addSpacing(10)
        self.lbl_read = QtWidgets.QLabel("—")
        self.lbl_read.setStyleSheet("font-family:monospace; font-size:12px;")
        self.lbl_read.setTextFormat(QtCore.Qt.RichText)
        side.addWidget(self.lbl_read)
        side.addStretch(1)

        self.statusBar().showMessage("starting…")

    # ---- controls ----
    def _toggle_pause(self, v): self.paused = v
    def do_ffc(self, light=False):
        self.statusBar().showMessage("FFC: closing shutter…")
        QtWidgets.QApplication.processEvents()
        ok = self.cam.trigger_ffc()
        self.enhancer.reset_temporal()      # FFC changed the per-pixel reference
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
        self.statusBar().showMessage("flat-field cleared", 3000)
    def snapshot(self):
        pm = self.image_label.pixmap()
        if pm:
            fn = time.strftime("/tmp/mag_%Y%m%d_%H%M%S.png")
            pm.save(fn); self.statusBar().showMessage(f"saved {fn}", 4000)

    # ---- mouse ----
    def eventFilter(self, obj, ev):
        if obj is self.image_label and ev.type() == QtCore.QEvent.MouseMove:
            self._map_cursor(ev.position().x(), ev.position().y())
        return super().eventFilter(obj, ev)

    def _map_cursor(self, px, py):
        if self._disp_rect is None:
            return
        x0, y0, w, h = self._disp_rect
        if w <= 0 or h <= 0:
            return
        fx = int((px - x0) / w * self.cam.W)
        fy = int((py - y0) / h * self.cam.H)
        if 0 <= fx < self.cam.W and 0 <= fy < self.cam.H:
            self.cursor = (fx, fy)
        else:
            self.cursor = None

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
        if self.cursor:
            cx, cy = self.cursor
        else:
            cx, cy = self.cam.W // 2, self.cam.H // 2
        raw = int(self.last_frame[cy, cx])
        t, ok = QtWidgets.QInputDialog.getDouble(
            self, "Add calibration point",
            f"Pixel ({cx},{cy}) raw={raw}\nKnown temperature (°C):",
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

    # ---- render ----
    _disp_rect = None
    def update_frame(self):
        if self.paused and self.last_frame is not None:
            frame = self.last_frame
            disp = self.enhancer.enhance_display(frame)
        else:
            raw = self._grab(0.0)               # mirror applied here
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
        self.lbl_read.setText(txt)

        self._fps_n += 1
        if time.time() - self._fps_t >= 1.0:
            self._fps = self._fps_n / (time.time() - self._fps_t)
            self._fps_t = time.time(); self._fps_n = 0
        self.statusBar().showMessage(f"{self._fps:.1f} fps   frames={self.cam.frame_count}")

    def _mark(self, p, x, y, color, label):
        p.setPen(QtGui.QPen(color, 2))
        p.drawLine(int(x-6), int(y), int(x+6), int(y))
        p.drawLine(int(x), int(y-6), int(x), int(y+6))
        p.drawText(int(x+7), int(y-7), label)

    def closeEvent(self, ev):
        self.timer.stop()
        try: self.cam.stop(); self.cam.close()
        except Exception: pass
        super().closeEvent(ev)


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
