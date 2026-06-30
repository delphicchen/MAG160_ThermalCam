import sys
from capstone import *
from capstone.arm import *

# Function offset from readelf
offset = 0x00054269

with open('/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so', 'rb') as f:
    code = f.read()

# Try to get MagDevice_GetFixPara (which likely loads the 48 matrices)
md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
md.detail = True

print(f"Disassembling MagDevice_GetFixPara at offset 0x{offset:x}")
try:
    for i in md.disasm(code[offset:offset+544], offset):
        print("0x%x:\t%s\t%s" %(i.address, i.mnemonic, i.op_str))
except Exception as e:
    print(f"Error: {e}")
