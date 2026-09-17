#!/usr/bin/env python3
"""Rebuild the notebook state that a Colab kernel restart wipes.

The validate / export cells used ROOT, CKPT and the working directory left behind by
earlier cells, so after a restart they failed with NameError. This recovers all three
from what is still on disk:

    ROOT  <- where cell 5's `experiments` symlink points (Drive or the runtime disk)
    CKPT  <- newest generator checkpoint, stage 2 (GAN) first, then stage 1

Usage inside the notebook:

    import sys; sys.path.insert(0, '/content/Real-ESRGAN/scripts')
    from colab_state import restore
    ROOT, CKPT = restore(ITER)      # ITER = None -> newest
"""
import os
import pathlib
import re

REPO = pathlib.Path('/content/Real-ESRGAN')
STAGES = ('thermal_srvgg_x4_gan', 'thermal_srvgg_x4_net')   # preferred first


def iter_of(p: pathlib.Path) -> int:
    """net_g_latest.pth carries no iteration number -> -1."""
    m = re.findall(r'\d+', p.stem)
    return int(m[-1]) if m else -1


def restore(iter_=None):
    if not REPO.is_dir():
        raise SystemExit('/content/Real-ESRGAN is gone — the runtime was recycled. Re-run '
                         'sections 1, 2 and 5, then restore your progress zip (section 5b).')
    os.chdir(REPO)

    exp = REPO / 'experiments'
    if not exp.is_symlink():
        raise SystemExit('experiments/ is not linked to the data root yet — run section 5 first.')
    target = pathlib.Path(os.readlink(exp))
    if not target.is_dir():
        raise SystemExit(f'{target} does not exist. With USE_DRIVE = True, re-run section 1 '
                         'to remount Drive; otherwise restore your progress zip (section 5b).')
    root = target.parent

    for stage in STAGES:
        d = exp / stage / 'models'
        cks = sorted(d.glob('net_g_*.pth'), key=iter_of) if d.is_dir() else []
        if iter_ is not None:
            cks = [c for c in cks if iter_of(c) == iter_]
        numbered = [c for c in cks if iter_of(c) >= 0]
        pick = (numbered or cks)[-1:]
        if pick:
            print('ROOT:', root)
            print('CKPT:', pick[0])
            return root, pick[0]

    want = 'any checkpoint' if iter_ is None else f'net_g_{iter_}.pth'
    raise SystemExit(f'No {want} under experiments/{{{",".join(STAGES)}}}/models — '
                     'train first, or restore your progress zip (section 5b).')
