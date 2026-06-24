#!/usr/bin/env python3
"""Tiny ARM/Thumb disassembler helper for libcoresdk.so reverse engineering.

Maps vaddr<->file offset from ELF section headers, builds a symbol map from the
dynamic symbol table, disassembles a function in Thumb mode, and annotates:
  - BL/BLX call targets resolved to symbol names
  - PC-relative literal loads (LDR Rn,[pc,#imm]) with the 32-bit constant value
"""
import sys, struct, subprocess, re
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_LITTLE_ENDIAN

LIB = sys.argv[1] if len(sys.argv) > 1 else "/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so"
DATA = open(LIB, "rb").read()

# ---- parse ELF32 section headers to map vaddr->offset ----
e_shoff = struct.unpack_from("<I", DATA, 0x20)[0]
e_shentsize = struct.unpack_from("<H", DATA, 0x2e)[0]
e_shnum = struct.unpack_from("<H", DATA, 0x30)[0]
sections = []  # (addr, offset, size)
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    sh_addr = struct.unpack_from("<I", DATA, off + 0x0c)[0]
    sh_offset = struct.unpack_from("<I", DATA, off + 0x10)[0]
    sh_size = struct.unpack_from("<I", DATA, off + 0x14)[0]
    if sh_addr:
        sections.append((sh_addr, sh_offset, sh_size))

def v2o(vaddr):
    for a, o, s in sections:
        if a <= vaddr < a + s:
            return o + (vaddr - a)
    return None

def read(vaddr, n):
    o = v2o(vaddr)
    return DATA[o:o + n]

# ---- symbol map from readelf --dyn-syms ----
SYMS = {}  # addr(no thumb bit) -> name
def load_syms():
    out = subprocess.check_output(["readelf", "-sW", LIB], text=True, errors="replace")
    for line in out.splitlines():
        m = re.match(r"\s*\d+:\s*([0-9a-f]+)\s+\d+\s+FUNC\s+\S+\s+\S+\s+\S+\s+(\S+)", line)
        if m:
            addr = int(m.group(1), 16) & ~1
            SYMS[addr] = m.group(2)
load_syms()

def symname(addr):
    a = addr & ~1
    if a in SYMS:
        return SYMS[a]
    # nearest below
    best = None
    for s in SYMS:
        if s <= a and (best is None or s > best):
            best = s
    if best is not None and a - best < 0x400:
        return f"{SYMS[best]}+0x{a-best:x}"
    return f"sub_{a:x}"

def find_sym(name):
    for a, n in SYMS.items():
        if n == name:
            return a
    return None

md = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
md.detail = True

def disasm_func(name_or_addr, length=None):
    if isinstance(name_or_addr, str):
        addr = find_sym(name_or_addr)
        if addr is None:
            print("symbol not found:", name_or_addr); return
        name = name_or_addr
    else:
        addr = name_or_addr & ~1
        name = symname(addr)
    if length is None:
        # crude: until next symbol
        nexts = sorted(s for s in SYMS if s > addr)
        length = (nexts[0] - addr) if nexts else 0x200
        length = min(length, 0x800)
    code = read(addr | 0, length)
    print(f"==== {name} @ 0x{addr:x} (len {length}) ====")
    for ins in md.disasm(code, addr):
        line = f"  0x{ins.address:06x}: {ins.mnemonic:8s} {ins.op_str}"
        # resolve BL/BLX
        if ins.mnemonic in ("bl", "blx") and ins.op_str.startswith("#"):
            tgt = int(ins.op_str[1:], 0)
            line += f"   ; -> {symname(tgt)}"
        # resolve pc-relative literal loads: ldr rX, [pc, #imm]
        m = re.search(r"ldr\w*\s+(\w+), \[pc, #(\d+)\]", ins.mnemonic + " " + ins.op_str)
        if "[pc" in ins.op_str and ins.mnemonic.startswith("ldr"):
            mm = re.search(r"\[pc, #(\-?\d+)\]", ins.op_str)
            if mm:
                imm = int(mm.group(1))
                pcbase = (ins.address + 4) & ~3
                litaddr = pcbase + imm
                val = struct.unpack_from("<I", read(litaddr, 4))[0]
                line += f"   ; =0x{val:x} ({val})"
                if val & ~1 in SYMS:
                    line += f" [{SYMS[val & ~1]}]"
        print(line)

if __name__ == "__main__":
    targets = sys.argv[2:] or ["MAG_LinkCamera"]
    for t in targets:
        try:
            if t.startswith("0x"):
                parts = t.split(":")
                addr = int(parts[0], 16)
                length = int(parts[1]) if len(parts) > 1 else None
                disasm_func(addr, length)
                continue
            disasm_func(t)
        except Exception as e:
            print("ERR", t, e)
        print()
