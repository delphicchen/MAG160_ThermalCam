"""Fine scan of (zoom, dx, dy) around the band-scan result at the reliable distances,
then the 1/Z line fit for the new calibration curve. usage: fit_registration.py"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag
from scan_burst import sample
sets = [('data_20261012_fan2', [0.6, 1.2, 1.8], [0.116, 0.040, 0.016], True),
        ('data_20261012_fan',  [0.6, 1.2, 1.8, 2.4], [0.098, 0.042, 0.002, -0.002], False)]
zooms = np.arange(2.20, 2.521, 0.04); dxs = np.arange(-0.070, 0.0001, 0.003)
rows = []
for d, dist, dy0, burst in sets:
    jsons = sorted(glob.glob(d + '/fd_*_0.json')) if burst else sorted(glob.glob(d + '/fd_*.json'))
    for i, z in enumerate(dist):
        b = jsons[i][:-5]; m = json.load(open(b + '.json'))
        tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
        if burst:
            stem = b[:-2]
            t = np.mean([np.fromfile(f'{stem}_{k}.f32', '<f4').reshape(th, tw) for k in range(10)], axis=0)
            vs = [np.fromfile(f'{stem}_{k}.u8', np.uint8).reshape(vh, vw) for k in (0, 5)]
        else:
            t = np.fromfile(b + '.f32', '<f4').reshape(th, tw); vs = [np.fromfile(b + '.u8', np.uint8).reshape(vh, vw)]
        vs = [cv2.GaussianBlur(v.astype(np.float32), (0, 0), 1.2) for v in vs]
        tg = grad_mag(t, 1.0); mask = tg >= np.percentile(tg, 90)
        dys = np.arange(dy0[i] - 0.024, dy0[i] + 0.0241, 0.003)
        best = (-1, None)
        for zm in zooms:
            for dx in dxs:
                for dy in dys:
                    s = 0
                    for v in vs:
                        vg = grad_mag(sample(v, tw, th, zm, dx, dy), 0.7); s += vg[mask].mean() / (vg.mean() + 1e-6)
                    if s > best[0]: best = (s, (zm, dx, dy))
        zm, dx, dy = best[1]
        rows.append((d, z, zm, dx, dy)); print(f'{d[-4:]} {z} m: zoom {zm:.2f} dx {dx:+.3f} dy {dy:+.3f}', flush=True)
r = np.array([(x[1], x[2], x[3], x[4]) for x in rows])
iz = 1 / r[:, 0]; A = np.c_[np.ones_like(iz), iz]
for name, col in [('dx', 2), ('dy', 3)]:
    (a, k), *_ = np.linalg.lstsq(A, r[:, col], rcond=None)
    res = (r[:, col] - A @ [a, k]) * r[:, 1].mean() * (120 if name == 'dx' else 160)
    print(f'{name} = {a:+.4f} + {k:+.4f}/Z   residuals (thermal px) {np.round(res, 1)}')
print(f'zoom mean {r[:, 1].mean():.3f} (sd {r[:, 1].std():.3f})')
