"""Thermal edges (10-frame mean) over the visible frame sampled at a given registration,
for eyeballing which dy is right. usage: overlay_burst.py <dump dir> <out.png> <dy list per burst ...>"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag
from scan_burst import sample
d, out = sys.argv[1], sys.argv[2]; regs = [tuple(map(float, a.split(','))) for a in sys.argv[3:]]
bursts = sorted({os.path.basename(f).rsplit('_', 1)[0] for f in glob.glob(d + '/fd_*_0.json')})
tiles = []
for bi, b in enumerate(bursts):
    m = json.load(open(f'{d}/{b}_0.json')); tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
    t = np.mean([np.fromfile(f'{d}/{b}_{k}.f32', '<f4').reshape(th, tw) for k in range(10)], axis=0)
    v = np.fromfile(f'{d}/{b}_0.u8', np.uint8).reshape(vh, vw).astype(np.float32)
    tg = grad_mag(t, 1.0); edge = tg >= np.percentile(tg, 90)
    row = []
    tn = ((t - t.min()) / (np.ptp(t) + 1e-6) * 255).astype(np.uint8)
    row.append(cv2.applyColorMap(cv2.resize(tn, (tw * 3, th * 3), interpolation=cv2.INTER_CUBIC), cv2.COLORMAP_INFERNO))
    # first the app's own registration, then each requested dy (burst index, dy) at zoom 2.35
    for (zoom, dx, dy) in [(m['zoom'], m['dx'], m['dy'])] + [(2.35, -0.035, r[1]) for r in regs if int(r[0]) == bi]:
        s = sample(v, tw, th, zoom, dx, dy)
        img = cv2.cvtColor(cv2.resize(s, (tw * 3, th * 3), interpolation=cv2.INTER_CUBIC).clip(0, 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
        e = cv2.resize(edge.astype(np.uint8), (tw * 3, th * 3), interpolation=cv2.INTER_NEAREST) > 0
        img[e] = (0.4 * img[e] + 0.6 * np.array([0, 0, 255])).astype(np.uint8)
        cv2.putText(img, f'dy {dy:+.3f}', (6, 22), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
        row.append(img)
    tiles.append(np.hstack(row))
w = max(t.shape[1] for t in tiles)
cv2.imwrite(out, np.vstack([np.pad(t, ((0, 6), (0, w - t.shape[1]), (0, 0))) for t in tiles]))
