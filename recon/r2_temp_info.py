import r2pipe
import os

os.environ['PATH'] = '/home/delphic/.local/bin:' + os.environ['PATH']
r = r2pipe.open("/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so")
r.cmd("e bin.relocs.apply=true")
r.cmd("aa") # basic analysis

for fcn in [0x0005591d]: # GetTemperatureData
    decomp = r.cmd(f"pdc @ {fcn}")
    with open(f"recon/decomp_jni_{fcn:x}.c", "w") as f:
        f.write(decomp)

print("Done")
