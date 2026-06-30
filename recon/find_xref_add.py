from elftools.elf.elffile import ELFFile
from capstone import *
from capstone.arm import *

str_addr = 0x43138

with open('/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so', 'rb') as f:
    elf = ELFFile(f)
    text = elf.get_section_by_name('.text')
    text_data = text.data()
    text_addr = text['sh_addr']

md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
md.detail = True

print(f"Searching for code computing address 0x{str_addr:x}")

# In Thumb-2, ADR or ADD/SUB with PC can load addresses.
# LDR with literal pool is also common.
# Let's just disassemble everything and track basic register values (very roughly)
# or just look for instructions that reference something near 0x43138.

for i in md.disasm(text_data, text_addr):
    if i.mnemonic in ['ldr', 'add', 'adr']:
        # If it's a literal LDR
        if i.mnemonic == 'ldr' and len(i.operands) == 2 and i.operands[1].type == ARM_OP_MEM:
            mem = i.operands[1].mem
            if mem.base == ARM_REG_PC:
                target = (i.address & ~3) + 4 + mem.disp
                if 0 <= target - text_addr < len(text_data):
                    val = int.from_bytes(text_data[target-text_addr:target-text_addr+4], 'little')
                    if val == str_addr:
                        print(f"Found literal pool load at 0x{i.address:x}")
