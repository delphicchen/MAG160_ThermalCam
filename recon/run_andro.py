import sys
from androguard.core.bytecodes import dex
from androguard.core.analysis import analysis

# Load the DEX file
with open("/tmp/apk_x/classes.dex", "rb") as f:
    d = dex.DEX(f.read())
    
# Analyze the DEX file
a = analysis.Analysis(d)

# Search for methods that interact with calibration or fix params
for m in d.get_methods():
    mname = m.get_name()
    cname = m.get_class_name()
    if 'MagDevice' in cname and ('Load' in mname or 'Fix' in mname or 'GetFixPara' in mname or 'Cali' in mname or 'Temp' in mname):
        print(f"Class: {cname}")
        print(f"Method: {mname}")
        print(f"Descriptor: {m.get_descriptor()}")
        print("-" * 40)
