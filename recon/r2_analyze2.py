import r2pipe
import os
import sys

os.environ['PATH'] = '/home/delphic/.local/bin:' + os.environ['PATH']

r = r2pipe.open("/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so")
r.cmd("aaaa")

print("Searching for strings...")
res = r.cmd("/ The calibration table")
print("Search result:", res)
cali_str_addr = None
for line in res.splitlines():
    if "The calibration table size mismatch" in line:
        parts = line.split()
        # example: 0x0024c8df hit2_0 .The calibration table size mismatch FPA.
        cali_str_addr = int(parts[0], 16)
        break

if not cali_str_addr:
    print("Could not find calibration string.")
    sys.exit(1)

print(f"String found at 0x{cali_str_addr:x}")

xrefs = r.cmdj(f"axtj {cali_str_addr}")
if not xrefs:
    print("No xrefs found.")
    sys.exit(1)

for xref in xrefs:
    func_addr = xref.get('fcn_addr')
    if func_addr:
        print(f"Function containing the string is at 0x{func_addr:x}")
        decomp = r.cmd(f"pdc @ {func_addr}")
        with open(f"recon/decompiled_{func_addr:x}.c", "w") as f:
            f.write(decomp)
        print(f"Saved to recon/decompiled_{func_addr:x}.c")

