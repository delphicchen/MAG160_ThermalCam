#!/usr/bin/env python3
"""Drive the REAL cali parser (sub_424d0) under emulation to expand mag_cali.bin into the
camera's per-pixel NUC / radiometric context — the thing that was "locked behind the
parser". We serve the cali file through hooked libc fopen/fread/fseek/ftell/feof, run
sub_424d0(context, path), then dump the populated context + heap.

parser:  sub_424d0(r0 = context base 'sb', r1 = path string)
reader:  sub_346aa(stream, dest, size, count) -> fread(dest,size,count, stream[+4])
"""
import os, sys, struct
import numpy as np
from unicorn.arm_const import *
from emu_harness import Emu, BASE, FAKE

HERE = os.path.dirname(os.path.abspath(__file__))
CALI = os.path.join(HERE, "mag_cali.bin")
PARSER = BASE + 0x424d0
CTX_SZ = 0x20000


def main():
    e = Emu(verbose=True)
    cali = open(CALI, "rb").read()
    vf = {"pos": 0, "data": cali, "open": False}
    u = e.mu
    R0, R1, R2, R3 = UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3
    FH = 0xCA11F11E                          # fake FILE* handle

    def h_fopen(uc):
        vf["pos"] = 0; vf["open"] = True
        uc.reg_write(R0, FH)
    def h_fclose(uc):
        vf["open"] = False; uc.reg_write(R0, 0)
    def h_fread(uc):
        dest, size, count = uc.reg_read(R0), uc.reg_read(R1), uc.reg_read(R2)
        n = size * count
        avail = max(0, len(vf["data"]) - vf["pos"])
        n = min(n, avail)
        if n:
            uc.mem_write(dest, vf["data"][vf["pos"]:vf["pos"] + n])
            vf["pos"] += n
        uc.reg_write(R0, (n // size) if size else 0)
    def h_fseek(uc):
        off, whence = uc.reg_read(R1), uc.reg_read(R2)
        off = struct.unpack('<i', struct.pack('<I', off))[0]
        base = {0: 0, 1: vf["pos"], 2: len(vf["data"])}.get(whence, 0)
        vf["pos"] = max(0, base + off); uc.reg_write(R0, 0)
    def h_ftell(uc):
        uc.reg_write(R0, vf["pos"])
    def h_feof(uc):
        uc.reg_write(R0, 1 if vf["pos"] >= len(vf["data"]) else 0)
    def h_strlen(uc):
        p = uc.reg_read(R0); s = 0
        while uc.mem_read(p + s, 1) != b"\0" and s < 4096:
            s += 1
        uc.reg_write(R0, s)
    def h_memcpy(uc):
        d, s, n = uc.reg_read(R0), uc.reg_read(R1), uc.reg_read(R2)
        if n:
            uc.mem_write(d, bytes(uc.mem_read(s, n)))
        uc.reg_write(R0, d)
    def h_memclr(uc):                          # __aeabi_memclr(dest, n)
        d, n = uc.reg_read(R0), uc.reg_read(R1)
        if n:
            uc.mem_write(d, b"\0" * n)
        uc.reg_write(R0, d)
    def h_arg0(uc):                            # passthrough (operator new/delete, catch)
        pass

    e.handlers.update({
        'fopen': h_fopen, 'fdopen': h_fopen, 'freopen': h_fopen, 'fclose': h_fclose,
        'fread': h_fread, 'fseek': h_fseek, 'ftell': h_ftell, 'feof': h_feof,
        'ferror': lambda uc: uc.reg_write(R0, 0), 'fileno': lambda uc: uc.reg_write(R0, 3),
        'access': lambda uc: uc.reg_write(R0, 0),        # file exists
        'strlen': h_strlen, 'strcpy': h_memcpy, 'strncpy': h_memcpy,
        'memcpy': h_memcpy, 'memmove': h_memcpy,
        '__aeabi_memclr': h_memclr, '__aeabi_memclr4': h_memclr, '__aeabi_memclr8': h_memclr,
        '_ZdaPv': h_arg0, '_ZdlPv': h_arg0,              # operator delete[]/delete = noop free
        '__cxa_begin_catch': h_arg0, '__cxa_end_catch': h_arg0,
        '_Znwj': lambda uc: uc.reg_write(R0, e.malloc(uc.reg_read(R0))),   # operator new
        '_Znaj': lambda uc: uc.reg_write(R0, e.malloc(uc.reg_read(R0))),   # operator new[]
    })

    # ring buffer of recent basic blocks (to reconstruct the call chain on fault)
    import unicorn as _uc
    from collections import deque
    recent = deque(maxlen=20)
    def blk(uc, addr, size, ud):
        recent.append(addr - BASE)
    u.hook_add(_uc.UC_HOOK_BLOCK, blk)
    def fault(uc, access, addr, size, value, ud):
        pc = uc.reg_read(UC_ARM_REG_PC)
        print(f"    !! fault {'W' if access==_uc.UC_MEM_WRITE_UNMAPPED else 'R'} "
              f"addr=0x{addr:x} size={size} pc=0x{pc-BASE:x}  (cali pos={vf['pos']})")
        print("    recent blocks: " + " -> ".join(f"0x{a:x}" for a in recent))
        return False
    u.hook_add(_uc.UC_HOOK_MEM_READ_UNMAPPED | _uc.UC_HOOK_MEM_WRITE_UNMAPPED, fault)

    # call-trace: log key parser checkpoints so we can see how far it gets
    from emu_harness import BASE as _B
    marks = {_B+0x425f6: "first read", _B+0x42646: "magic cmp",
             _B+0x42672: "MAGIC OK -> parsing", _B+0x4269c: "magic FAIL/return"}
    def trace(uc, addr, size, ud):
        if addr in marks:
            print(f"    [parser @0x{addr-_B:x}] {marks[addr]}  (cali pos={vf['pos']})")
    u.hook_add(__import__('unicorn').UC_HOOK_CODE, trace,
               begin=_B+0x42500, end=_B+0x42700)
    def dbg(uc, addr, size, ud):
        if addr == _B + 0x42900:                 # cmp r0(filesize), sl(expected)
            r0 = uc.reg_read(UC_ARM_REG_R0); sl = uc.reg_read(UC_ARM_REG_R10)
            print(f"    [size check @0x42900] filesize={r0} expected_sl={sl} -> FORCING PASS")
            uc.reg_write(UC_ARM_REG_R0, 0x7fffffff)   # force filesize>=expected (skip error)
    u.hook_add(__import__('unicorn').UC_HOOK_CODE, dbg, begin=_B+0x428f0, end=_B+0x42910)

    # allocate a zeroed context + a path string
    ctx = e.malloc(CTX_SZ)
    u.mem_write(ctx, b"\0" * CTX_SZ)
    path = e.malloc(64); u.mem_write(path, b"/tmp/cali.bin\0")
    # Device-init context fields the parser VALIDATES (normally set from GetParameter1).
    # Any zero one diverts to an error/log path that null-derefs an unconstructed ostream,
    # so we supply them here, one per discovered gate.
    INIT = {
        0x20c:  160,        # W (nonzero gate)
        0x1e2c: 160,        # W (validated == cali header W)
        0x1e30: 120,        # H (validated == cali header H)
        0x7740: 0x10000,    # upper bound for parsed coeff indices (loop @0x427f6)
    }
    for off, val in INIT.items():
        u.mem_write(ctx + off, struct.pack('<I', val))
    heap_before = e.heap_top

    print(f"running parser sub_424d0(ctx=0x{ctx:x}, path=0x{path:x}) ...")
    try:
        e.call(PARSER, args=(ctx, path), count=200_000_000)
        print(f"parser returned OK; bytes consumed from cali = {vf['pos']}/{len(cali)}")
    except Exception as ex:
        print(f"parser stopped: {ex}  (consumed {vf['pos']}/{len(cali)} bytes)")

    # ---- now build the per-pixel NUC: call sub_45ca4(ctx) on the populated context ----
    BUILDER = BASE + 0x45ca4
    u.mem_write(ctx + 0x40, struct.pack('<I', 23123))    # live sensor/FPA temp (raw)
    u.mem_write(ctx + 0x80, struct.pack('<I', 1))        # sensor-temp block index
    # buffer B(ctx[0x16e0]) and C(ctx[0x16ec]) pointers + bound ctx[0x7740]
    b_ptr = struct.unpack_from('<I', bytes(u.mem_read(ctx + 0x16e0, 4)), 0)[0]
    c_ptr = struct.unpack_from('<I', bytes(u.mem_read(ctx + 0x16ec, 4)), 0)[0]
    for w7740 in (160*120, 0x10000):                     # try W*H first, then big
        u.mem_write(ctx + 0x7740, struct.pack('<I', w7740))
        u.mem_write(b_ptr, b"\0" * 0x40000); u.mem_write(c_ptr, b"\0" * 0x40000)
        try:
            e.call(BUILDER, args=(ctx,), count=200_000_000)
            nb = np.count_nonzero(np.frombuffer(bytes(u.mem_read(b_ptr, 0x40000)), np.uint32))
            nc = np.count_nonzero(np.frombuffer(bytes(u.mem_read(c_ptr, 0x40000)), np.uint32))
            print(f"  builder(ctx[0x7740]={w7740}) OK; bufB nonzero={nb} bufC nonzero={nc}")
            if nb or nc:
                break
        except Exception as ex:
            print(f"  builder(ctx[0x7740]={w7740}) stopped: {ex}")

    # dump context + heap allocated during parse
    ctx_bytes = bytes(u.mem_read(ctx, CTX_SZ))
    heap_bytes = bytes(u.mem_read(heap_before, e.heap_top - heap_before)) if e.heap_top > heap_before else b""
    np.save(os.path.join(HERE, "emu_ctx.npy"), np.frombuffer(ctx_bytes, np.uint8))
    open(os.path.join(HERE, "emu_heap.bin"), "wb").write(heap_bytes)
    print(f"dumped context ({len(ctx_bytes)}B) -> emu_ctx.npy; heap ({len(heap_bytes)}B) -> emu_heap.bin")

    # quick scan: non-zero context fields + any 160x120-ish float/int arrays in the heap
    nz = np.nonzero(np.frombuffer(ctx_bytes, np.uint32))[0]
    print(f"non-zero u32 words in ctx: {len(nz)} (first offsets: "
          + ", ".join(hex(int(o)*4) for o in nz[:12]) + ")")
    # peek key radiometric fields the temp core reads
    for off in (0x50, 0x54, 0x80, 0x16f4, 0x16f8, 0x7758, 0x775c):
        v = struct.unpack_from('<I', ctx_bytes, off)[0]
        f = struct.unpack_from('<f', ctx_bytes, off)[0]
        print(f"  ctx[0x{off:04x}] = 0x{v:08x}  ({f:.4g} as float)")


if __name__ == "__main__":
    main()
