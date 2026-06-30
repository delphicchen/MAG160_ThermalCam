#!/usr/bin/env python3
"""Bionic-less Unicorn harness for libcoresdk.so (ARM32 Thumb).

The cali parser uses only plain-C imports (fopen/fread/fseek/ftell/feof/fwrite/malloc/
memcpy/free/pthread...), so we can emulate it on x86 without an Android rootfs: map the
.so, apply R_ARM_RELATIVE relocations, point every PLT/GOT import at a trap region, and
service the imports in Python.

This module is the reusable foundation. `validate` proves it by running the real
`sub_3f970` radiometric core and checking it against radiometry.py.
"""
import struct, sys, os
import numpy as np
from unicorn import *
from unicorn.arm_const import *
from elftools.elf.elffile import ELFFile
from elftools.elf.enums import ENUM_RELOC_TYPE_ARM

LIB = "/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so"
BASE = 0x40000000
FAKE = 0x10000000          # import trap region
HEAP = 0x50000000
HEAP_SZ = 0x4000000
STACK = 0x7ff00000


def _align(x, a=0x1000): return (x + a - 1) & ~(a - 1)


class Emu:
    def __init__(self, lib=LIB, verbose=False):
        self.verbose = verbose
        self.data = open(lib, "rb").read()
        self.elf = ELFFile(open(lib, "rb"))
        self.mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_LITTLE_ENDIAN)
        # enable VFP/NEON (the radiometry uses float/SIMD; off by default in Unicorn ARM)
        self.mu.reg_write(UC_ARM_REG_C1_C0_2,
                          self.mu.reg_read(UC_ARM_REG_C1_C0_2) | (0xf << 20))  # CPACR
        self.mu.reg_write(UC_ARM_REG_FPEXC, 0x40000000)                        # FPEXC.EN
        self._map_segments()
        self._setup_aux()
        self._imports = self._collect_imports()
        self._apply_relocs()
        self._install_hooks()
        self.heap_top = HEAP
        self.files = {}          # virtual FS: path -> bytes (for fopen/fread)
        self._open = {}          # FILE* -> [path, pos]
        self.handlers = self._default_handlers()

    # ---- memory map ----
    def _map_segments(self):
        for seg in self.elf.iter_segments():
            if seg['p_type'] != 'PT_LOAD':
                continue
            va = BASE + seg['p_vaddr']
            sz = _align(seg['p_memsz'] + (va & 0xfff))
            base = va & ~0xfff
            try:
                self.mu.mem_map(base, sz, UC_PROT_ALL)
            except UcError:
                pass
            self.mu.mem_write(va, seg.data())

    def _setup_aux(self):
        self.mu.mem_map(STACK - 0x100000, 0x100000)
        self.mu.reg_write(UC_ARM_REG_SP, STACK)
        self.mu.mem_map(HEAP, HEAP_SZ)
        self.mu.mem_map(FAKE, 0x100000)
        # fill trap region with Thumb 'bx lr' (0x4770) so a dispatched import just returns
        self.mu.mem_write(FAKE, b"\x70\x47" * (0x100000 // 2))

    # ---- imports: map each PLT/GOT slot to a trap addr ----
    def _collect_imports(self):
        dynsym = self.elf.get_section_by_name('.dynsym')
        imports = {}             # trap_addr -> name
        self._name2trap = {}
        idx = 0
        for relname in ('.rel.plt', '.rel.dyn'):
            sec = self.elf.get_section_by_name(relname)
            if not sec:
                continue
            for r in sec.iter_relocations():
                si = r['r_info_sym']
                rtype = r['r_info_type']
                if not si:
                    continue
                name = dynsym.get_symbol(si).name
                if rtype in (ENUM_RELOC_TYPE_ARM['R_ARM_JUMP_SLOT'],
                             ENUM_RELOC_TYPE_ARM['R_ARM_GLOB_DAT'],
                             ENUM_RELOC_TYPE_ARM['R_ARM_ABS32']):
                    sym = dynsym.get_symbol(si)
                    if sym['st_value'] == 0:        # undefined -> import, trap it
                        trap = FAKE + idx * 4
                        self.mu.mem_write(BASE + r['r_offset'], struct.pack('<I', trap | 1))
                        imports[trap] = name
                        self._name2trap[name] = trap
                        idx += 1
        return imports

    def _apply_relocs(self):
        dynsym = self.elf.get_section_by_name('.dynsym')
        REL = ENUM_RELOC_TYPE_ARM['R_ARM_RELATIVE']
        for relname in ('.rel.dyn', '.rel.plt'):
            sec = self.elf.get_section_by_name(relname)
            if not sec:
                continue
            for r in sec.iter_relocations():
                off = BASE + r['r_offset']
                t = r['r_info_type']
                if t == REL:
                    cur = struct.unpack('<I', self.mu.mem_read(off, 4))[0]
                    self.mu.mem_write(off, struct.pack('<I', (cur + BASE) & 0xffffffff))
                elif t in (ENUM_RELOC_TYPE_ARM['R_ARM_GLOB_DAT'],
                           ENUM_RELOC_TYPE_ARM['R_ARM_ABS32'],
                           ENUM_RELOC_TYPE_ARM['R_ARM_JUMP_SLOT']):
                    si = r['r_info_sym']
                    sym = dynsym.get_symbol(si) if si else None
                    if sym is not None and sym['st_value'] != 0:
                        self.mu.mem_write(off, struct.pack(
                            '<I', (BASE + sym['st_value']) & 0xffffffff))

    # ---- import dispatch ----
    def _install_hooks(self):
        def hook(uc, addr, size, ud):
            if FAKE <= addr < FAKE + 0x100000:
                name = self._imports.get(addr & ~1)
                if name is None:
                    return
                h = self.handlers.get(name)
                if h is None:
                    if self.verbose:
                        print(f"  [unhandled import] {name}")
                    uc.reg_write(UC_ARM_REG_R0, 0)
                else:
                    h(uc)
        self.mu.hook_add(UC_HOOK_CODE, hook, begin=FAKE, end=FAKE + 0x100000)

    def malloc(self, n):
        p = self.heap_top
        self.heap_top = (self.heap_top + n + 15) & ~15
        return p

    def _default_handlers(self):
        A0, A1, A2, A3 = UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3
        u = self.mu
        def rd(r): return u.reg_read(r)
        def ret(v): u.reg_write(A0, v & 0xffffffff)
        def h_malloc(uc): ret(self.malloc(rd(A0)))
        def h_calloc(uc):
            n = rd(A0) * rd(A1); p = self.malloc(n); u.mem_write(p, b"\0" * n); ret(p)
        def h_free(uc): ret(0)
        def h_memcpy(uc):
            d, s, n = rd(A0), rd(A1), rd(A2)
            if n: u.mem_write(d, bytes(u.mem_read(s, n)))
            ret(d)
        def h_memset(uc):
            d, c, n = rd(A0), rd(A1), rd(A2)
            if n: u.mem_write(d, bytes([c & 0xff]) * n)
            ret(d)
        def h_errno(uc): ret(FAKE + 0xf0000)        # any writable dummy
        def h_zero(uc): ret(0)
        return {
            'malloc': h_malloc, 'calloc': h_calloc, 'realloc': h_malloc, 'free': h_free,
            'memcpy': h_memcpy, 'memmove': h_memcpy, '__aeabi_memcpy': h_memcpy,
            '__aeabi_memcpy4': h_memcpy, '__aeabi_memcpy8': h_memcpy,
            'memset': h_memset, '__aeabi_memclr': h_memset,
            '__errno': h_errno,
            'pthread_mutex_lock': h_zero, 'pthread_mutex_unlock': h_zero,
            'pthread_once': h_zero, '__android_log_print': h_zero,
        }

    # ---- call an ARM (Thumb) function ----
    def call(self, addr, args=(), regs_f=None, timeout=0, count=2_000_000):
        RET = 0xdeadbee0
        for i, a in enumerate(args[:4]):
            self.mu.reg_write([UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3][i],
                              a & 0xffffffff)
        if regs_f:                       # {s_reg_index: float}
            for idx, val in regs_f.items():
                self.mu.reg_write(UC_ARM_REG_S0 + idx, struct.pack('<f', val))
        self.mu.reg_write(UC_ARM_REG_LR, RET | 1)
        self.mu.reg_write(UC_ARM_REG_SP, STACK)
        self.mu.emu_start(addr | 1, RET, timeout=timeout, count=count)
        return self.mu.reg_read(UC_ARM_REG_R0)

    def w32(self, addr, v): self.mu.mem_write(addr, struct.pack('<I', v & 0xffffffff))
    def wf(self, addr, v): self.mu.mem_write(addr, struct.pack('<f', v))
    def wi32arr(self, addr, arr):
        self.mu.mem_write(addr, np.asarray(arr, '<i4').tobytes())


def validate():
    """Run the real sub_3f970 (radiometric core, simple path) and compare to radiometry.py."""
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from radiometry import Radiometry, U_to_celsius
    r = Radiometry(); li = 3
    lut = r.luts[li].astype(np.int64); slope = r.slopes[li].astype(np.int64)

    e = Emu()
    # build a fake ctx in the heap; fill the .bss LUTs the simple path reads.
    ctx = e.malloc(0x8000)
    k, b, shift = 35.0 / 1.0, -250000.0, 7      # a=k+1 etc; pick shift=7 -> target=v
    # sub_3f970 simple path: v=raw*(k+1)+b ; but we feed k,b directly as ctx[0x50],[0x54]
    e.wf(ctx + 0x50, 34.0)        # k  (so k+1 = 35)
    e.wf(ctx + 0x54, b)
    e.w32(ctx + 0x16f4, 0)        # flag==0 -> simple path
    e.w32(ctx + 0x7758, 1)        # nonzero -> use static LUT path
    e.w32(ctx + 0x775c, shift)
    # write radLUT @0x29d700, slopeLUT @0x29e11c (+BASE)
    e.wi32arr(BASE + 0x29d700, lut)
    e.wi32arr(BASE + 0x29e11c, slope)
    e.w32(BASE + 0x29eb34, 0)     # threshold = 0

    raws = [12000, 13000, 14117, 15414]
    print("raw    emu_U      py_U     emu_°C    py_°C")
    ok = True
    for raw in raws:
        u_emu = e.call(BASE + 0x3f970, args=(ctx, raw, 0, 1))
        u_emu = struct.unpack('<i', struct.pack('<I', u_emu))[0]
        rad = 35.0 * raw + b
        u_py = float(r._U_from_radiance(li, rad))
        ce, cp = U_to_celsius(u_emu), U_to_celsius(u_py)
        flag = "" if abs(u_emu - u_py) < 50 else "  <-- MISMATCH"
        ok = ok and abs(u_emu - u_py) < 50
        print(f"{raw:5d}  {u_emu:8d}  {u_py:8.0f}   {ce:6.2f}   {cp:6.2f}{flag}")
    print("HARNESS VALIDATION:", "PASS" if ok else "FAIL")
    return ok


if __name__ == "__main__":
    validate()
