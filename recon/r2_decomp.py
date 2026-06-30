import r2pipe
import os

os.environ['PATH'] = '/home/delphic/.local/bin:' + os.environ['PATH']
r = r2pipe.open("/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so")
r.cmd("e bin.relocs.apply=true")
r.cmd("aa") # basic analysis
# The string is at 0x43138
# Let's find xrefs to 0x43138
xrefs = r.cmdj("axtj 0x43138")
if xrefs:
    for xref in xrefs:
        fcn = xref.get('fcn_addr')
        if fcn:
            print(f"Found xref at function 0x{fcn:x}")
            # Decompile it!
            with open(f"recon/decompiled_{fcn:x}.c", "w") as f:
                f.write(r.cmd(f"pdg @ {fcn}")) # pdg is Ghidra decompiler! Wait, r2ghidra might not be installed. Let's use pdc.
            with open(f"recon/decompiled_pdc_{fcn:x}.c", "w") as f:
                f.write(r.cmd(f"pdc @ {fcn}"))
            print(f"Saved decompilation for 0x{fcn:x}")
else:
    print("No xrefs found.")
