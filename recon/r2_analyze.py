import r2pipe
import os
import sys

# r2 is at ~/.local/bin/r2
os.environ['PATH'] = '/home/delphic/.local/bin:' + os.environ['PATH']

r = r2pipe.open("/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so")
print("Analyzing...")
r.cmd("aaaa")

# Find strings related to 'mag_cali' or 'The calibration table size mismatch FPA'
print("Searching for strings...")
res = r.cmdj("izj")
cali_str_addr = None
for s in res:
    if 'The calibration table size mismatch' in s.get('string', ''):
        cali_str_addr = s['vaddr']
        break

if not cali_str_addr:
    print("Could not find calibration string.")
    sys.exit(1)

print(f"String found at 0x{cali_str_addr:x}")

# Find references to this string
print("Finding xrefs...")
xrefs = r.cmdj(f"axtj {cali_str_addr}")
if not xrefs:
    print("No xrefs found.")
    sys.exit(1)

func_addr = xrefs[0]['fcn_addr']
print(f"Function containing the string is at 0x{func_addr:x}")

# Decompile the function
print("Decompiling function...")
decomp = r.cmd(f"pdc @ {func_addr}")
with open("recon/decompiled.c", "w") as f:
    f.write(decomp)

print("Decompilation saved to recon/decompiled.c")
