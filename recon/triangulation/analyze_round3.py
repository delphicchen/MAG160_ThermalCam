"""Round 3 (laser-measured distances, hot cup): run the B3 matcher along the new curve, then a fine
(zoom, dx, dy) scan per burst for the best alignment independent of the curve.
usage: analyze_round3.py <dump dir> <distances m, comma-separated, in capture order>"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag
from match_fast import match, vis_grad, ZOOM, AX, KX, AY, KY

def scorer(t, v):
    th, tw = t.shape; vh, vw = v.shape
    tg = grad_mag(t, 1.0); ys, xs = np.nonzero(tg >= np.percentile(tg, 90))
    G = vis_grad(v, 1.7); I = cv2.integral(G)
    def score(zoom, dx, dy):
        px = (((xs + .5) / tw - .5) / zoom + .5 + dx) * vw - .5
        py = (((ys + .5) / th - .5) / zoom + .5 + dy) * vh - .5
        ok = (px >= 0) & (py >= 0) & (px < vw - 1) & (py < vh - 1)
        if ok.sum() < 0.5 * len(px): return 0.0
        x0 = px[ok].astype(int); y0 = py[ok].astype(int); fx = px[ok] - x0; fy = py[ok] - y0
        s = (G[y0, x0] * (1 - fx) * (1 - fy) + G[y0, x0 + 1] * fx * (1 - fy) +
             G[y0 + 1, x0] * (1 - fx) * fy + G[y0 + 1, x0 + 1] * fx * fy).mean()
        l = int(np.clip((-.5 / zoom + .5 + dx) * vw, 0, vw)); r = int(np.clip((.5 / zoom + .5 + dx) * vw, 0, vw))
        u = int(np.clip((-.5 / zoom + .5 + dy) * vh, 0, vh)); b = int(np.clip((.5 / zoom + .5 + dy) * vh, 0, vh))
        return s / ((I[b, r] - I[u, r] - I[b, l] + I[u, l]) / max(1, (r - l) * (b - u)) + 1e-6)
    return score

d = sys.argv[1]; dist = [float(x) for x in sys.argv[2].split(',')]
stems = sorted({os.path.basename(f)[:-7] for f in glob.glob(f'{d}/fd_*_0.json')})
assert len(stems) == len(dist), (len(stems), len(dist))
px = 1 / (ZOOM * 160); rows = []
for z, s in zip(dist, stems):
    m = json.load(open(f'{d}/{s}_0.json')); tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
    t = np.mean([np.fromfile(f'{d}/{s}_{k}.f32', '<f4').reshape(th, tw) for k in range(8)], axis=0)
    v = np.fromfile(f'{d}/{s}_7.u8', np.uint8).reshape(vh, vw)
    iz, prof, izs = match(t, v, 0.0, 4.0)
    peak = prof.max() / np.median(prof[prof > 0])
    sc = scorer(t, v); best = (-1, None)
    for zoom in np.arange(2.24, 2.53, 0.04):
        for dy in np.arange(AY + KY / z - 0.08, AY + KY / z + 0.0801, 0.003):
            for dx in np.arange(AX + KX / z - 0.02, AX + KX / z + 0.0201, 0.003):
                q = sc(zoom, dx, dy)
                if q > best[0]: best = (q, (zoom, dx, dy))
    zb, dxb, dyb = best[1]
    rows.append((z, iz, peak, zb, dxb, dyb, m['focus_diopters']))
    print(f'{z:4.2f} m  match 1/Z {iz:.2f} (true {1/z:.2f}, {1/max(iz,1e-3):.2f} m) peak {peak:.2f} | '
          f'best zoom {zb:.2f} dx {dxb:+.3f} dy {dyb:+.3f} | match vs best {(AY + KY*iz - dyb)/px:+5.1f} px | '
          f'curve@true vs best {(AY + KY/z - dyb)/px:+5.1f} px | focusD {m["focus_diopters"]:.2f}', flush=True)
r = np.array(rows); iz = 1 / r[:, 0]; A = np.c_[np.ones_like(iz), iz]
for name, col in [('dx', 4), ('dy', 5)]:
    (a, k), *_ = np.linalg.lstsq(A, r[:, col], rcond=None)
    print(f'{name} = {a:+.4f} + {k:+.4f}/Z  residuals px {np.round((r[:, col] - A @ [a, k]) / px, 1)}')
print(f'zoom mean {r[:, 3].mean():.3f} sd {r[:, 3].std():.3f}')
np.save(f'{d}/out/rows.npy', r)
