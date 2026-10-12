"""B2 round 2: score the band scan on 10-frame bursts (raw vs denoised thermal,
full frame vs centre window, single frame vs accumulated).
usage: scan_burst.py <dump dir> <out dir>"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag

def sample(v, tw, th, zoom, dx, dy):      # same as scan_band.sample (that file runs on import)
    vh, vw = v.shape
    xs = ((np.arange(tw) + .5) / tw - .5) / zoom + .5 + dx
    ys = ((np.arange(th) + .5) / th - .5) / zoom + .5 + dy
    mx, my = np.meshgrid(xs * vw - .5, ys * vh - .5)
    return cv2.remap(v, mx.astype(np.float32), my.astype(np.float32), cv2.INTER_LINEAR,
                     borderValue=float(v.mean()))
dist = [0.6, 1.2, 1.8, 2.4, 3.0]
ZOOM = 2.35
dxs = np.arange(-0.062, -0.0099, 0.004); dys = np.arange(-0.15, 0.401, 0.004)
EXCL = 0.02                                  # second peak must be this far from the first (dy)

def masks(t, tw, th):
    tg = grad_mag(t, 1.5)
    full = tg >= np.percentile(tg, 90)
    win = np.zeros_like(full); win[th//4:3*th//4, tw//4:3*tw//4] = True
    cen = win & (tg >= np.percentile(tg[win], 90))
    return {'full': (full, np.ones_like(full)), 'centre': (cen, win)}

def peaks(p):
    a = int(np.argmax(p)); far = np.abs(dys - dys[a]) > EXCL
    return dys[a], p[a], p[far].max()

if __name__ == '__main__':
    d, out = sys.argv[1], sys.argv[2]; os.makedirs(out, exist_ok=True)
    bursts = sorted({os.path.basename(f).rsplit('_', 1)[0] for f in glob.glob(d + '/fd_*_0.json')})
    rows = []
    for bi, b in enumerate(bursts):
        acc = {}
        for k in range(10):
            p = f'{d}/{b}_{k}'; m = json.load(open(p + '.json'))
            tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
            v = cv2.GaussianBlur(np.fromfile(p + '.u8', np.uint8).reshape(vh, vw).astype(np.float32), (0, 0), 1.2)
            th_ = {'raw': np.fromfile(p + '.f32', '<f4').reshape(th, tw),
                   'dn': np.fromfile(p + '.dn.f32', '<f4').reshape(th, tw)}
            ms = {(src, reg): mm for src, t in th_.items() for reg, mm in masks(t, tw, th).items()}
            S = {key: np.zeros((len(dys), len(dxs))) for key in ms}
            for a, dy in enumerate(dys):
                for c, dx in enumerate(dxs):
                    vg = grad_mag(sample(v, tw, th, ZOOM, dx, dy), 0.7)
                    for key, (mk, win) in ms.items():
                        S[key][a, c] = vg[mk].mean() / (vg[win].mean() + 1e-6)
            for key, s in S.items():
                prof = s.max(axis=1)
                if k == 0: acc[key] = {'single': [], 'sum': np.zeros_like(s)}
                acc[key]['single'].append(peaks(prof)); acc[key]['sum'] += s
            if k == 0: app = (m['dy'], m['invZ'])
        for key, r in acc.items():
            sp = np.array([x[0] for x in r['single']])
            prof = r['sum'].max(axis=1) / 10
            dy, pk, sec = peaks(prof)
            rows.append((dist[bi], key, sp, dy, pk, sec))
            np.save(f'{out}/prof_{bi}_{key[0]}_{key[1]}.npy', prof)
            print(f'{dist[bi]} m {key[0]:>3}/{key[1]:<6} single dy {np.median(sp):+.3f} '
                  f'(spread {sp.min():+.3f}..{sp.max():+.3f})  accumulated dy {dy:+.3f} '
                  f'peak {pk:.2f}/{sec:.2f} ratio {pk/sec:.3f} | app dy {app[0]:+.3f}', flush=True)
    np.save(f'{out}/dys.npy', dys)
