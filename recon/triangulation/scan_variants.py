"""B2 round 2: compare scoring variants on the 10-frame bursts (thermal averaged over the burst).
usage: scan_variants.py <dump dir>"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag
from scan_burst import sample
d = sys.argv[1]
dist = [0.6, 1.2, 1.8, 2.4, 3.0]
ref = lambda z: -0.030 + 0.0864 / z            # fit through the clear 0.6/1.2/1.8 m results
ZOOM = 2.35
dxs = np.arange(-0.062, -0.0099, 0.004); dys = np.arange(-0.06, 0.301, 0.004)
EXCL = 0.02
bursts = sorted({os.path.basename(f).rsplit('_', 1)[0] for f in glob.glob(d + '/fd_*_0.json')})
for bi, b in enumerate(bursts):
    m = json.load(open(f'{d}/{b}_0.json')); tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
    t1 = np.fromfile(f'{d}/{b}_0.f32', '<f4').reshape(th, tw)
    tm = np.mean([np.fromfile(f'{d}/{b}_{k}.f32', '<f4').reshape(th, tw) for k in range(10)], axis=0)
    vs = [cv2.GaussianBlur(np.fromfile(f'{d}/{b}_{k}.u8', np.uint8).reshape(vh, vw).astype(np.float32), (0, 0), 1.2) for k in (0, 3, 6, 9)]
    win = np.zeros((th, tw), bool); win[th//4:3*th//4, tw//4:3*tw//4] = True
    V = {}
    for name, t, sig in [('1frame', t1, 1.5), ('mean10', tm, 1.0), ('mean10s', tm, 1.5)]:
        tg = grad_mag(t, sig)
        V[name + '/top10'] = ('mask', tg >= np.percentile(tg, 90), np.ones_like(win))
        V[name + '/ctr'] = ('mask', win & (tg >= np.percentile(tg[win], 90)), win)
        V[name + '/wgt'] = ('wgt', tg ** 2, np.ones_like(win))
        V[name + '/ncc'] = ('ncc', tg, np.ones_like(win))
    S = {k: np.zeros((len(dys), len(dxs))) for k in V}
    for v in vs:
        for a, dy in enumerate(dys):
            for c, dx in enumerate(dxs):
                vg = grad_mag(sample(v, tw, th, ZOOM, dx, dy), 0.7)
                for k, (kind, w, reg) in V.items():
                    if kind == 'mask': s = vg[w].mean() / (vg[reg].mean() + 1e-6)
                    elif kind == 'wgt': s = (vg * w).sum() / w.sum() / (vg.mean() + 1e-6)
                    else:
                        x = w - w.mean(); y = vg - vg.mean(); s = (x * y).sum() / np.sqrt((x * x).sum() * (y * y).sum() + 1e-9)
                    S[k][a, c] += s
    print(f'--- {dist[bi]} m  expected dy {ref(dist[bi]):+.3f}  app {m["dy"]:+.3f}')
    for k, s in S.items():
        p = s.max(axis=1); a = int(np.argmax(p)); far = np.abs(dys - dys[a]) > EXCL
        err = (dys[a] - ref(dist[bi])) / (1 / (160 * ZOOM))   # in thermal px
        print(f'  {k:<16} dy {dys[a]:+.3f} ({err:+5.1f} px)  peak/second {p[a]/p[far].max():.3f}', flush=True)
