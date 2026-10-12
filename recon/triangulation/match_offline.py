#!/usr/bin/env python3
"""
B2 offline check for image-based thermal↔visible registration (android2/PLAN_TRIANGULATION.md).

Reads the app's fusion debug dumps (fd_*.f32 thermal °C, fd_*.u8 upright visible luma,
fd_*.json registration + calibration samples) and, for each zoom candidate, finds the
(dx, dy) offset where thermal and visible edge strength agree best (normalized
cross-correlation over all offsets at once via matchTemplate).

Mapping (Fusion.compose): thermal pixel x ↔ upright visible u = ((x+.5)/tw − .5)/zoom + .5 + dx.
With the visible frame scaled to thermal pixels (width tw·zoom), template offset
ox = tw(zoom−1)/2 + dx·tw·zoom.

    python match_offline.py <dump dir> [out dir]
"""
import glob, json, os, sys
import numpy as np
import cv2


def grad_mag(img, sigma):
    g = cv2.GaussianBlur(img.astype(np.float32), (0, 0), sigma)
    gx = cv2.Sobel(g, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(g, cv2.CV_32F, 0, 1, ksize=3)
    return np.sqrt(gx * gx + gy * gy)


def match(t, v, zoom, t_sigma=1.5, v_sigma=1.0):
    th, tw = t.shape
    vh, vw = v.shape
    W, H = int(round(tw * zoom)), int(round(th * zoom))
    vs = cv2.resize(v, (W, H), interpolation=cv2.INTER_AREA)
    tg = grad_mag(t, t_sigma)
    vg = grad_mag(vs, v_sigma)
    # thermal edges are sparse: compress the dynamic range of both so a few very strong
    # edges (the hot motor) do not dominate the correlation
    tg = np.sqrt(tg); vg = np.sqrt(vg)
    pad = max(tw, th)                       # allow offsets past the visible frame edge
    vgp = cv2.copyMakeBorder(vg, pad, pad, pad, pad, cv2.BORDER_CONSTANT, value=float(vg.mean()))
    score = cv2.matchTemplate(vgp, tg, cv2.TM_CCOEFF_NORMED)
    oy, ox = np.unravel_index(np.argmax(score), score.shape)
    best = float(score[oy, ox])
    # peak sharpness: best vs the 99th percentile of the map away from the peak
    mask = np.ones_like(score, bool)
    mask[max(0, oy - 6):oy + 7, max(0, ox - 6):ox + 7] = False
    second = float(score[mask].max())
    ox -= pad; oy -= pad
    dx = (ox - tw * (zoom - 1) / 2) / (tw * zoom)
    dy = (oy - th * (zoom - 1) / 2) / (th * zoom)
    return best, second, dx, dy, score


def main():
    d = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else d
    zooms = np.round(np.arange(2.0, 2.81, 0.05), 3)
    rows = []
    for j in sorted(glob.glob(os.path.join(d, 'fd_*.json'))):
        b = j[:-5]
        m = json.load(open(j))
        tw, th, vw, vh = m['thermal_w'], m['thermal_h'], m['visible_w'], m['visible_h']
        t = np.fromfile(b + '.f32', '<f4').reshape(th, tw)
        v = np.fromfile(b + '.u8', np.uint8).reshape(vh, vw)
        res = [(z,) + match(t, v, z)[:4] for z in zooms]
        z, best, second, dx, dy = max(res, key=lambda r: r[1])
        name = os.path.basename(b)
        rows.append((name, z, best, second, dx, dy, m))
        print(f'{name}: best zoom {z:.2f} score {best:.3f} (2nd peak {second:.3f}) '
              f'dx {dx:+.4f} dy {dy:+.4f} | app used zoom {m["zoom"]:.3f} dx {m["dx"]:+.4f} '
              f'dy {m["dy"]:+.4f} (AF 1/Z {m["invZ"]:.2f})')
        # overlay check image: thermal edges (red) over the visible frame at the found fit
        _, _, _, _, _ = match(t, v, z)
        W, H = int(round(tw * z)), int(round(th * z))
        vs = cv2.resize(v, (W, H), interpolation=cv2.INTER_AREA)
        ox = int(round(tw * (z - 1) / 2 + dx * tw * z)); oy = int(round(th * (z - 1) / 2 + dy * th * z))
        crop = np.full((th, tw), 0, np.uint8)
        ys, xs = slice(max(0, oy), min(H, oy + th)), slice(max(0, ox), min(W, ox + tw))
        crop[ys.start - oy:ys.stop - oy, xs.start - ox:xs.stop - ox] = vs[ys, xs]
        tg = grad_mag(t, 1.5); tg = (tg / np.percentile(tg, 99.5)).clip(0, 1)
        img = cv2.cvtColor(crop, cv2.COLOR_GRAY2BGR).astype(np.float32)
        img[..., 2] = np.maximum(img[..., 2], tg * 255)
        cv2.imwrite(os.path.join(out, name + '_fit.png'),
                    cv2.resize(img.astype(np.uint8), (tw * 3, th * 3), interpolation=cv2.INTER_NEAREST))
    return rows


if __name__ == '__main__':
    main()
