#!/usr/bin/env python3
"""Shared setup for the libcoresdk.so emulation drivers.

`build_parsed_ctx()` runs the REAL cali parser (sub_424d0) under the Unicorn
harness with the cali file served through hooked libc, and returns the emulator
plus the populated context pointer. This is the common foundation for
`emu_parse_nuc.py` (dump) and `emu_build_nuc.py` (run the NUC build chain).
"""
import os, struct
import numpy as np
import unicorn as _uc
from unicorn.arm_const import *
from emu_harness import Emu, BASE, FAKE

HERE = os.path.dirname(os.path.abspath(__file__))
CALI = os.path.join(HERE, "mag_cali.bin")
PARSER = BASE + 0x424d0
CTX_SZ = 0x20000
FH = 0xCA11F11E


def install_libc(e, vf):
    """Install the libc/file handlers that let the parser read the cali file."""
    u = e.mu
    R0, R1, R2 = UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2

    def h_fopen(uc):
        vf["pos"] = 0; vf["open"] = True; uc.reg_write(R0, FH)
    def h_fclose(uc):
        vf["open"] = False; uc.reg_write(R0, 0)
    def h_fread(uc):
        dest, size, count = uc.reg_read(R0), uc.reg_read(R1), uc.reg_read(R2)
        n = min(size * count, max(0, len(vf["data"]) - vf["pos"]))
        if n:
            uc.mem_write(dest, vf["data"][vf["pos"]:vf["pos"] + n]); vf["pos"] += n
        uc.reg_write(R0, (n // size) if size else 0)
    def h_fseek(uc):
        off = struct.unpack('<i', struct.pack('<I', uc.reg_read(R1)))[0]
        base = {0: 0, 1: vf["pos"], 2: len(vf["data"])}.get(uc.reg_read(R2), 0)
        vf["pos"] = max(0, base + off); uc.reg_write(R0, 0)
    def h_ftell(uc): uc.reg_write(R0, vf["pos"])
    def h_feof(uc): uc.reg_write(R0, 1 if vf["pos"] >= len(vf["data"]) else 0)
    def h_strlen(uc):
        p = uc.reg_read(R0); s = 0
        while uc.mem_read(p + s, 1) != b"\0" and s < 4096:
            s += 1
        uc.reg_write(R0, s)
    def h_memcpy(uc):
        d, s, n = uc.reg_read(R0), uc.reg_read(R1), uc.reg_read(R2)
        if n: uc.mem_write(d, bytes(uc.mem_read(s, n)))
        uc.reg_write(R0, d)
    def h_memclr(uc):
        d, n = uc.reg_read(R0), uc.reg_read(R1)
        if n: uc.mem_write(d, b"\0" * n)
        uc.reg_write(R0, d)
    e.handlers.update({
        'fopen': h_fopen, 'fdopen': h_fopen, 'freopen': h_fopen, 'fclose': h_fclose,
        'fread': h_fread, 'fseek': h_fseek, 'ftell': h_ftell, 'feof': h_feof,
        'ferror': lambda uc: uc.reg_write(R0, 0), 'fileno': lambda uc: uc.reg_write(R0, 3),
        'access': lambda uc: uc.reg_write(R0, 0),
        'strlen': h_strlen, 'strcpy': h_memcpy, 'strncpy': h_memcpy,
        'memcpy': h_memcpy, 'memmove': h_memcpy,
        '__aeabi_memclr': h_memclr, '__aeabi_memclr4': h_memclr, '__aeabi_memclr8': h_memclr,
        '_ZdaPv': lambda uc: None, '_ZdlPv': lambda uc: None,
        '__cxa_begin_catch': lambda uc: None, '__cxa_end_catch': lambda uc: None,
        '_Znwj': lambda uc: uc.reg_write(R0, e.malloc(uc.reg_read(R0))),
        '_Znaj': lambda uc: uc.reg_write(R0, e.malloc(uc.reg_read(R0))),
    })


def build_parsed_ctx(verbose=False):
    """Run the cali parser; return (Emu e, ctx ptr, heap_before)."""
    e = Emu(verbose=verbose)
    u = e.mu
    cali = open(CALI, "rb").read()
    vf = {"pos": 0, "data": cali, "open": False}
    install_libc(e, vf)

    # force the file-size sanity check to pass (expected_sl assumes full device init)
    def dbg(uc, addr, size, ud):
        if addr == BASE + 0x42900:
            uc.reg_write(UC_ARM_REG_R0, 0x7fffffff)
    u.hook_add(_uc.UC_HOOK_CODE, dbg, begin=BASE + 0x428f0, end=BASE + 0x42910)

    ctx = e.malloc(CTX_SZ); u.mem_write(ctx, b"\0" * CTX_SZ)
    path = e.malloc(64); u.mem_write(path, b"/tmp/cali.bin\0")
    for off, val in {0x20c: 160, 0x1e2c: 160, 0x1e30: 120, 0x7740: 0x10000}.items():
        u.mem_write(ctx + off, struct.pack('<I', val))
    heap_before = e.heap_top
    e.call(PARSER, args=(ctx, path), count=200_000_000)
    if verbose:
        print(f"parser consumed {vf['pos']}/{len(cali)} bytes")
    return e, ctx, heap_before
