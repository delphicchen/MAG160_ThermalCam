#!/usr/bin/env python3
"""
Package an ncnn export for the app's model download and update models.json.

    python scripts/make_sr_package.py --size 256x192 --version 1 \
        export/thermal_256x192_fp16.param export/thermal_256x192_fp16.bin \
        export/thermal_192x256_fp16.param export/thermal_192x256_fp16.bin
    gh release upload sr-models dist/thermal_ncnn_256x192.zip dist/models.json --clobber

Writes dist/thermal_ncnn_<size>.zip (the same zip "Load SR model" imports) and adds or
replaces its entry in dist/models.json. models.json is fetched from the release first
(when gh is available) so the other packages listed there are kept. The app offers the
package to a camera whose frame is one of the sizes inside the zip, and offers it again
when --version goes up.
"""

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

MODEL_FILE = re.compile(r'thermal_(\d+)x(\d+)_fp16\.(param|bin)')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('files', nargs='+', help='thermal_<w>x<h>_fp16.param/.bin files')
    ap.add_argument('--size', required=True, help='WxH naming the package, e.g. 256x192')
    ap.add_argument('--version', type=int, required=True)
    ap.add_argument('--out', default='dist')
    ap.add_argument('--release', default='sr-models', help='gh release tag holding models.json')
    a = ap.parse_args()

    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    files = [pathlib.Path(f) for f in a.files]
    sizes = sorted({f'{m[1]}x{m[2]}' for f in files if (m := MODEL_FILE.fullmatch(f.name))})
    if not sizes or len(sizes) * 2 != len(files):
        raise SystemExit('need a .param and a .bin named thermal_<w>x<h>_fp16.* per size')

    name = f'thermal_ncnn_{a.size}.zip'
    zp = out / name
    with zipfile.ZipFile(zp, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in files:
            z.write(f, f.name)
    data = zp.read_bytes()

    manifest = out / 'models.json'
    subprocess.run(['gh', 'release', 'download', a.release, '-p', 'models.json',
                    '-D', str(out), '--clobber'], capture_output=True)
    doc = json.loads(manifest.read_text()) if manifest.exists() else {'packages': []}
    doc['packages'] = [p for p in doc['packages'] if p['file'] != name]
    doc['packages'].append({'file': name, 'sizes': sizes, 'version': a.version,
                            'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    manifest.write_text(json.dumps(doc, indent=2) + '\n')
    print(f'{zp} ({len(data)} bytes, sizes {", ".join(sizes)}) + {manifest}')


if __name__ == '__main__':
    main()
