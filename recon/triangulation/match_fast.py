"""Prototype of the in-app B3 matcher (what ImageMatch.kt implements): visible gradient
computed once at visible resolution + integral image, candidates along the calibration
line, score = mean gradient at the thermal top-10 % edge pixels / mean over the footprint.
usage: match_fast.py"""
import glob, json, os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from match_offline import grad_mag
ZOOM, AX, KX, AY, KY = 2.38, -0.0254, -0.0124, -0.0444, 0.0940
STEP, DXOFF = 0.02, (-0.006, -0.003, 0.0, 0.003, 0.006)

def vis_grad(v, sigma):
    g = cv2.GaussianBlur(v.astype(np.float32), (0, 0), sigma)
    return np.sqrt(cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3) ** 2 + cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3) ** 2)

def match(t, v, lo, hi, sigma_v=1.7):
    th, tw = t.shape; vh, vw = v.shape
    tg = grad_mag(t, 1.0); ys, xs = np.nonzero(tg >= np.percentile(tg, 90))
    G = vis_grad(v, sigma_v); I = cv2.integral(G)
    izs = np.arange(lo, hi + 1e-6, STEP); prof = np.zeros(len(izs))
    for a, iz in enumerate(izs):
        for off in DXOFF:
            dx = AX + KX * iz + off; dy = AY + KY * iz
            px = (((xs + .5) / tw - .5) / ZOOM + .5 + dx) * vw - .5
            py = (((ys + .5) / th - .5) / ZOOM + .5 + dy) * vh - .5
            ok = (px >= 0) & (py >= 0) & (px < vw - 1) & (py < vh - 1)
            if ok.sum() < 0.5 * len(px): continue
            x0 = px[ok].astype(int); y0 = py[ok].astype(int); fx = px[ok] - x0; fy = py[ok] - y0
            s = (G[y0, x0] * (1 - fx) * (1 - fy) + G[y0, x0 + 1] * fx * (1 - fy) +
                 G[y0 + 1, x0] * (1 - fx) * fy + G[y0 + 1, x0 + 1] * fx * fy).mean()
            # footprint of the thermal frame in the visible frame, clipped
            l = int(np.clip((-.5 / ZOOM + .5 + dx) * vw, 0, vw)); r = int(np.clip((.5 / ZOOM + .5 + dx) * vw, 0, vw))
            u = int(np.clip((-.5 / ZOOM + .5 + dy) * vh, 0, vh)); b = int(np.clip((.5 / ZOOM + .5 + dy) * vh, 0, vh))
            if r - l < 8 or b - u < 8: continue
            mean = (I[b, r] - I[u, r] - I[b, l] + I[u, l]) / ((r - l) * (b - u))
            prof[a] = max(prof[a], s / (mean + 1e-6))
    a = int(np.argmax(prof))
    return izs[a], prof, izs

def load(d, stem, burst, n):
    m = json.load(open(f'{d}/{stem}{"_0" if burst else ""}.json'))
    tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
    if burst:
        t = np.mean([np.fromfile(f'{d}/{stem}_{k}.f32', '<f4').reshape(th, tw) for k in range(n)], axis=0)
        v = np.fromfile(f'{d}/{stem}_{n - 1}.u8', np.uint8).reshape(vh, vw)
    else:
        t = np.fromfile(f'{d}/{stem}.f32', '<f4').reshape(th, tw); v = np.fromfile(f'{d}/{stem}.u8', np.uint8).reshape(vh, vw)
    return t, v, m

if __name__ == '__main__':
    px = 1 / (ZOOM * 160)
    for d, burst in [('data_20261012_fan2', True), ('data_20261012_fan', False)]:
        stems = sorted({os.path.basename(f)[:-5].rsplit('_', 1)[0] if burst else os.path.basename(f)[:-5]
                        for f in glob.glob(f'{d}/fd_*{"_0" if burst else ""}.json')})
        for z, stem in zip([0.6, 1.2, 1.8, 2.4, 3.0], stems):
            t, v, m = load(d, stem, burst, 8)
            prior = m['invZ']
            for name, lo, hi in [('full', 0.0, 4.0), ('prior', max(0, prior - 0.5), prior + 0.5)]:
                iz, prof, izs = match(t, v, lo, hi)
                err = (iz - 1 / z) * KY / px
                edge = iz <= lo + 1e-6 or iz >= hi - 1e-6
                print(f'{d[-4:]} {z} m {name:<5} found {iz:.2f} (true {1/z:.2f}) error {err:+5.1f} px'
                      f'{"  [window edge]" if edge else ""}', flush=True)
