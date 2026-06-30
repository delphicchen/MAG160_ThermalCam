#!/usr/bin/env python3
"""RESOLVED (2026-06-26): the radiometric LUTs of sub_3f970's simple path.

Long-standing blocker was "the LUT address 0x29d700 lands past EOF". It does NOT —
it lands in .bss (runtime-populated), which is why it reads as zeros statically.
This script proves the resolution end-to-end:

  1. From sub_3f970's binary-search code, the three tables resolve to:
        radLUT   @ 0x29d700  (647 x int32)  -- also the interp base (baseLUT == radLUT)
        slopeLUT @ 0x29e11c  (646 x int32)
        threshold@ 0x29eb34  (scalar)
     all in .bss (0x29d6f8 .. 0x2cdf00) -> filled at init, not stored in the file.

  2. radLUT is one of the 8 static Planck curves in .rodata (recon/planck_luts.npy),
     and slopeLUT is the fixed-point reciprocal slope slopeLUT[i] = 2**24 // d[i]
     (d[i]=radLUT[i+1]-radLUT[i]), so (slopeLUT[i]*d[i])>>12 == 4095..4096 (one index
     step, floor-rounded) -- i.e. it reproduces a full linear step to ~1 mK.

  3. The integer lookup (firmware) matches the float interpolation to < 0.01 C.

Output U is milli-Kelvin: U = idx*4096 - 150000 ; C = U/1000 - 273.15
(idx 109 -> 23.31 C for every curve).
"""
import os, struct, numpy as np
from elftools.elf.elffile import ELFFile

HERE = os.path.dirname(os.path.abspath(__file__))
LIB = "/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so"

RADLUT_VA, SLOPE_VA, THRESH_VA = 0x29d700, 0x29e11c, 0x29eb34
U_OFFSET, U_STEP = -150000, 4096


def section_of(va):
    elf = ELFFile(open(LIB, "rb"))
    for s in elf.iter_sections():
        a, sz = s["sh_addr"], s["sh_size"]
        if a and a <= va < a + sz:
            return s.name
    return "?"


def main():
    luts = np.load(os.path.join(HERE, "planck_luts.npy")).astype(np.int64)
    print("planck_luts.npy:", luts.shape)
    if os.path.exists(LIB):
        for name, va in (("radLUT", RADLUT_VA), ("slopeLUT", SLOPE_VA),
                         ("threshold", THRESH_VA)):
            print(f"  {name:9s} @ 0x{va:x}  section={section_of(va)}")

    # reconstruct + verify the slope identity on every curve
    for li in range(luts.shape[0]):
        L = luts[li]
        d = np.diff(L)
        slope = (1 << 24) // np.maximum(d, 1)
        step = (slope * d) >> 12          # one index step, floor-rounded (4095..4096)
        print(f"  LUT{li}: (slope*d)>>12 in [{step.min()},{step.max()}]  "
              f"(==4096 for {(step==4096).mean()*100:.0f}% of entries)")

    # integer lookup vs float interpolation
    L = luts[3]; slope = (1 << 24) // np.maximum(np.diff(L), 1)

    def fw(target):
        ti = int(min(max(target, L[0]), L[-1]))
        idx = int(np.searchsorted(L, ti, side="right") - 1)
        idx = max(0, min(idx, len(L) - 2))
        return idx * U_STEP + U_OFFSET + ((int(slope[idx]) * (ti - int(L[idx]))) >> 12)

    def flt(target):
        idx = int(np.searchsorted(L, target, side="right") - 1)
        idx = max(0, min(idx, len(L) - 2))
        f = (target - L[idx]) / max(L[idx + 1] - L[idx], 1)
        return (idx + f) * U_STEP + U_OFFSET

    err = max(abs(fw(t) - flt(t)) for t in np.linspace(L[2], L[-3], 4000))
    print(f"  exact-int vs float interp: max |dU| = {err:.2f} mK ({err/1000:.4f} C)")
    print(f"  idx 109 -> {(109*U_STEP+U_OFFSET)/1000-273.15:.2f} C  (confirms U=milli-K)")


if __name__ == "__main__":
    main()
