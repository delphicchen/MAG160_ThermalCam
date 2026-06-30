import sys
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_LITTLE_ENDIAN
DATA = open('/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so', 'rb').read()

e_shoff = int.from_bytes(DATA[0x20:0x24], 'little')
e_shentsize = int.from_bytes(DATA[0x2e:0x30], 'little')
e_shnum = int.from_bytes(DATA[0x30:0x32], 'little')

sections = []
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    sh_addr = int.from_bytes(DATA[off+0x0c:off+0x10], 'little')
    sh_offset = int.from_bytes(DATA[off+0x10:off+0x14], 'little')
    sh_size = int.from_bytes(DATA[off+0x14:off+0x18], 'little')
    if sh_addr: sections.append((sh_addr, sh_offset, sh_size))

def v2o(vaddr):
    for a, o, s in sections:
        if a <= vaddr < a + s: return o + (vaddr - a)
    return None

addr = 0x30024 & ~1
offset = v2o(addr)
code = DATA[offset:offset+0x400]

md = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
for ins in md.disasm(code, addr):
    print(f"0x{ins.address:06x}: {ins.mnemonic:8s} {ins.op_str}")
    if ins.mnemonic == 'pop' and 'pc' in ins.op_str:
        break
