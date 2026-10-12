"""Round 3: compare matcher scores along the new curve against laser truth (the curve at the
measured distance was checked by eye at all 8 positions). usage: score_variants3.py"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
ZOOM, AX, KX, AY, KY = 2.38, -0.0254, -0.0124, -0.0444, 0.0940
PX = 1 / (ZOOM * 160)
OFFS = (-0.006, -0.003, 0.0, 0.003, 0.006)

def grads(img, sigma):
    g = cv2.GaussianBlur(img.astype(np.float32), (0, 0), sigma)
    return cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)

def profile(t, v, kind, centre, clip, lo=0.0, hi=4.0):
    th, tw = t.shape; vh, vw = v.shape
    tx, ty = grads(t, 1.0); vx, vy = grads(v, 1.7)
    tm = np.abs(ty) if kind == 'gy' else np.hypot(tx, ty)
    G = np.abs(vy) if kind == 'gy' else np.hypot(vx, vy)
    if clip: G = np.minimum(G, np.percentile(G, clip))
    w = np.ones_like(tm, bool)
    if centre: w[:] = False; w[th // 4:3 * th // 4, tw // 4:3 * tw // 4] = True
    ys, xs = np.nonzero(w & (tm >= np.percentile(tm[w], 90)))
    I = cv2.integral(G)
    izs = np.arange(lo, hi + 1e-6, 0.02); prof = np.zeros(len(izs))
    for a, iz in enumerate(izs):
        for off in OFFS:
            dx = AX + KX * iz + off; dy = AY + KY * iz
            px = (((xs + .5) / tw - .5) / ZOOM + .5 + dx) * vw - .5
            py = (((ys + .5) / th - .5) / ZOOM + .5 + dy) * vh - .5
            ok = (px >= 0) & (py >= 0) & (px < vw - 1) & (py < vh - 1)
            if ok.sum() < 0.5 * len(px): continue
            x0 = px[ok].astype(int); y0 = py[ok].astype(int); fx = px[ok] - x0; fy = py[ok] - y0
            s = (G[y0, x0] * (1 - fx) * (1 - fy) + G[y0, x0 + 1] * fx * (1 - fy) +
                 G[y0 + 1, x0] * (1 - fx) * fy + G[y0 + 1, x0 + 1] * fx * fy).mean()
            # normalizer over the same window of the footprint
            fl, fr = (.25 if centre else 0), (.75 if centre else 1)
            l = int(np.clip(((fl - .5) / ZOOM + .5 + dx) * vw, 0, vw)); r = int(np.clip(((fr - .5) / ZOOM + .5 + dx) * vw, 0, vw))
            u = int(np.clip(((fl - .5) / ZOOM + .5 + dy) * vh, 0, vh)); b = int(np.clip(((fr - .5) / ZOOM + .5 + dy) * vh, 0, vh))
            if r - l < 8 or b - u < 8: continue
            prof[a] = max(prof[a], s / ((I[b, r] - I[u, r] - I[b, l] + I[u, l]) / ((r - l) * (b - u)) + 1e-6))
    return izs, prof

def cases():
    d = 'data_20261012_cup'; dist = [0.3, 0.4, 0.5, 0.7, 1.0, 1.5, 2.5, 4.0]
    for z, s in zip(dist, sorted({os.path.basename(f)[:-7] for f in glob.glob(f'{d}/fd_*_0.json')})):
        m = json.load(open(f'{d}/{s}_0.json'))
        t = np.mean([np.fromfile(f'{d}/{s}_{k}.f32', '<f4').reshape(160, 120) for k in range(8)], axis=0)
        yield f'cup {z}', z, t, np.fromfile(f'{d}/{s}_7.u8', np.uint8).reshape(640, 480), m['invZ']
    d = 'data_20261012_fan2'
    for z, s in zip([0.6, 1.2, 1.8, 2.4, 3.0], sorted({os.path.basename(f)[:-7] for f in glob.glob(f'{d}/fd_*_0.json')})):
        m = json.load(open(f'{d}/{s}_0.json'))
        t = np.mean([np.fromfile(f'{d}/{s}_{k}.f32', '<f4').reshape(160, 120) for k in range(8)], axis=0)
        yield f'fan {z}', z, t, np.fromfile(f'{d}/{s}_7.u8', np.uint8).reshape(640, 480), m['invZ']

if __name__ == '__main__':
    data = list(cases())
    variants = [('g', False, 0), ('gy', False, 0), ('gy', False, 95), ('g', True, 0), ('gy', True, 0), ('gy', True, 95)]
    for kind, centre, clip in variants:
        errs = []
        for name, z, t, v, afz in data:
            izs, prof = profile(t, v, kind, centre, clip)
            errs.append((izs[np.argmax(prof)] - 1 / z) * KY / PX)
        e = np.array(errs)
        print(f'{kind:>2} centre={centre!s:<5} clip={clip:<3} | ' + ' '.join(f'{x:+5.0f}' for x in e) +
              f' | median |err| {np.median(np.abs(e)):4.1f}  >8px: {(np.abs(e) > 8).sum()}', flush=True)
    print('cases: ' + ' '.join(n for n, *_ in data))
