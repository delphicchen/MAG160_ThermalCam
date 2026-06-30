import r2pipe
import os
import sys

os.environ['PATH'] = '/home/delphic/.local/bin:' + os.environ['PATH']
r = r2pipe.open("/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so")
r.cmd("aa") # basic analysis only
res = r.cmd("/ The calibration table")
print("Search:", res)
cali_str_addr = None
for line in res.splitlines():
    if "The calibration table" in line:
        cali_str_addr = int(line.split()[0], 16)
        break

if cali_str_addr:
    print(f"String at {cali_str_addr:x}")
    xrefs = r.cmdj(f"axtj {cali_str_addr}")
    if xrefs:
        fcn = xrefs[0].get('fcn_addr')
        if fcn:
            print(f"Function at {fcn:x}")
            with open(f"recon/decompiled_{fcn:x}.c", "w") as f:
                f.write(r.cmd(f"pdc @ {fcn}"))
            print("Done")
