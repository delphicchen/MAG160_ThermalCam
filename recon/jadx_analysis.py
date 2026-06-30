import sys
import os
import subprocess

# check where jadx outputted the java files
cmd = "find /tmp/apk_java -type f -name '*.java' | grep -i 'MagDevice' | head -n 10"
try:
    output = subprocess.check_output(cmd, shell=True, text=True)
    print("Found files:")
    print(output)
except:
    pass
