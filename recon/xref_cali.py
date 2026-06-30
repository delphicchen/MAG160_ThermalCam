from elftools.elf.elffile import ELFFile
from capstone import *
from capstone.arm import *

with open('/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so', 'rb') as f:
    elf = ELFFile(f)
    rodata = elf.get_section_by_name('.rodata')
    text = elf.get_section_by_name('.text')
    
    rodata_data = rodata.data()
    rodata_addr = rodata['sh_addr']
    
    text_data = text.data()
    text_addr = text['sh_addr']

# Find string "magcore.cali" or "The calibration table"
search_str = b"The calibration table size mismatch"
idx = rodata_data.find(search_str)
str_addr = rodata_addr + idx
print(f"String '{search_str}' found at 0x{str_addr:x}")

# Find references to this string in .text
# In ARM, strings are usually loaded via literal pools.
# LDR R0, [PC, #offset]
# We will just search the whole text section for the literal value of str_addr!

# Actually, it's easier to find LDR Rd, [PC, #offset] where PC+offset+4 points to a word containing str_addr.
import struct

possible_xrefs = []
for i in range(0, len(text_data)-3, 2): # Thumb mode 2-byte alignment
    # Check if there is a word here equal to str_addr
    val, = struct.unpack('<I', text_data[i:i+4])
    if val == str_addr:
        literal_addr = text_addr + i
        # Now find LDR instruction referencing literal_addr
        # Let's just print the literal_addr and search around it
        print(f"Found literal pool entry at 0x{literal_addr:x} pointing to string")
        possible_xrefs.append(literal_addr)

# Now disassemble around the literal pool references
md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
md.detail = True

for literal_addr in possible_xrefs:
    # search backward 1000 bytes for LDR PC relative
    start_search = max(0, literal_addr - text_addr - 1000)
    end_search = literal_addr - text_addr
    
    print(f"\n--- Analyzing function around 0x{text_addr + start_search:x} ---")
    for i in md.disasm(text_data[start_search:end_search+200], text_addr + start_search):
        # Very crude xref matching
        if i.mnemonic == 'ldr' and 'pc' in i.op_str:
            # LDR rX, [pc, #off]
            pass
        # Just print the last 20 instructions before the literal
        if literal_addr - 40 <= i.address <= literal_addr + 20:
            print(f"0x{i.address:x}:\t{i.mnemonic}\t{i.op_str}")

