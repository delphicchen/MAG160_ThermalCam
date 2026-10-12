"""Round 3, second pass: tame visible texture (clip / local normalization / binary edges), and the AF
window, on the same 13 cases as score_variants3.py. usage: score_variants3b.py"""
import os, sys, numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import score_variants3 as sv

def G_variant(v, mode):
    vx, vy = sv.grads(v, 1.7); G = np.hypot(vx, vy)
    if mode.startswith('clip'): return np.minimum(G, np.percentile(G, int(mode[4:])))
    if mode.startswith('local'):
        r = int(mode[5:]); return G / (cv2.blur(G, (2 * r + 1, 2 * r + 1)) + np.percentile(G, 50))
    if mode.startswith('bin'): return (G >= np.percentile(G, 100 - int(mode[3:]))).astype(np.float32)
    return G

def profile(t, v, mode, lo=0.0, hi=4.0):
    th, tw = t.shape; vh, vw = v.shape
    tx, ty = sv.grads(t, 1.0); tm = np.hypot(tx, ty)
    ys, xs = np.nonzero(tm >= np.percentile(tm, 90))
    G = G_variant(v, mode); I = cv2.integral(G)
    izs = np.arange(lo, hi + 1e-6, 0.02); prof = np.zeros(len(izs))
    for a, iz in enumerate(izs):
        for off in sv.OFFS:
            dx = sv.AX + sv.KX * iz + off; dy = sv.AY + sv.KY * iz
            px = (((xs + .5) / tw - .5) / sv.ZOOM + .5 + dx) * vw - .5
            py = (((ys + .5) / th - .5) / sv.ZOOM + .5 + dy) * vh - .5
            ok = (px >= 0) & (py >= 0) & (px < vw - 1) & (py < vh - 1)
            if ok.sum() < 0.5 * len(px): continue
            x0 = px[ok].astype(int); y0 = py[ok].astype(int); fx = px[ok] - x0; fy = py[ok] - y0
            s = (G[y0, x0] * (1 - fx) * (1 - fy) + G[y0, x0 + 1] * fx * (1 - fy) +
                 G[y0 + 1, x0] * (1 - fx) * fy + G[y0 + 1, x0 + 1] * fx * fy).mean()
            l = int(np.clip((-.5 / sv.ZOOM + .5 + dx) * vw, 0, vw)); r = int(np.clip((.5 / sv.ZOOM + .5 + dx) * vw, 0, vw))
            u = int(np.clip((-.5 / sv.ZOOM + .5 + dy) * vh, 0, vh)); b = int(np.clip((.5 / sv.ZOOM + .5 + dy) * vh, 0, vh))
            if r - l < 8 or b - u < 8: continue
            prof[a] = max(prof[a], s / ((I[b, r] - I[u, r] - I[b, l] + I[u, l]) / ((r - l) * (b - u)) + 1e-6))
    return izs, prof

data = list(sv.cases())
for mode, prior in [('raw', 0), ('clip90', 0), ('clip75', 0), ('local8', 0), ('local16', 0), ('bin20', 0), ('bin10', 0),
                    ('raw', 0.6), ('raw', 0.8)]:
    errs = []
    for name, z, t, v, afz in data:
        lo, hi = (max(0, afz - prior), afz + prior) if prior else (0.0, 4.0)
        izs, prof = profile(t, v, mode, lo, hi)
        errs.append((izs[np.argmax(prof)] - 1 / z) * sv.KY / sv.PX)
    e = np.array(errs)
    print(f'{mode:<8} prior ±{prior:<3} | ' + ' '.join(f'{x:+5.0f}' for x in e) +
          f' | median |err| {np.median(np.abs(e)):4.1f}  >8px: {(np.abs(e) > 8).sum()}', flush=True)
print('cases: ' + ' '.join(n for n, *_ in data))
