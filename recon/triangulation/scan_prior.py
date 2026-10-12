"""B2 round 2: mean10/top10 score restricted to a 1/Z window around the app's AF estimate.
usage: scan_prior.py <dump dir> <window half-width in 1/m>"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag
from scan_burst import sample
d, half = sys.argv[1], float(sys.argv[2])
dist = [0.6, 1.2, 1.8, 2.4, 3.0]
A, K = -0.030, 0.0864                          # dy = A + K/Z, fit through the clear 0.6/1.2/1.8 m results
ZOOM = 2.35
dxs = np.arange(-0.062, -0.0099, 0.004)
bursts = sorted({os.path.basename(f).rsplit('_', 1)[0] for f in glob.glob(d + '/fd_*_0.json')})
for bi, b in enumerate(bursts):
    m = json.load(open(f'{d}/{b}_0.json')); tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
    tm = np.mean([np.fromfile(f'{d}/{b}_{k}.f32', '<f4').reshape(th, tw) for k in range(10)], axis=0)
    vs = [cv2.GaussianBlur(np.fromfile(f'{d}/{b}_{k}.u8', np.uint8).reshape(vh, vw).astype(np.float32), (0, 0), 1.2) for k in (0, 3, 6, 9)]
    tg = grad_mag(tm, 1.0); mask = tg >= np.percentile(tg, 90)
    izs = np.arange(max(0.0, m['invZ'] - half), m['invZ'] + half + 1e-6, 0.02)
    p = np.zeros(len(izs))
    for v in vs:
        for a, iz in enumerate(izs):
            best = 0
            for dx in dxs:
                vg = grad_mag(sample(v, tw, th, ZOOM, dx, A + K * iz), 0.7)
                best = max(best, vg[mask].mean() / (vg.mean() + 1e-6))
            p[a] += best
    a = int(np.argmax(p))
    print(f'{dist[bi]} m: AF 1/Z {m["invZ"]:.2f} -> window {izs[0]:.2f}..{izs[-1]:.2f}; '
          f'found 1/Z {izs[a]:.2f} = {1/max(izs[a],1e-3):.2f} m (true 1/Z {1/dist[bi]:.2f}); '
          f'error {(izs[a]-1/dist[bi])*K/(1/(160*ZOOM)):+.1f} thermal px', flush=True)
