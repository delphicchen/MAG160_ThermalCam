# Temperature captures (`.mgt`)

A PNG or MP4 keeps palette colours; the numbers behind them are gone. With
**Save temperature data with captures** ticked in the drawer, Snap and Rec also write a
`.mgt` file to `Download/MagViewer/` holding the per-pixel absolute temperature of every
frame, so a position can be measured again afterwards.

Open one with **Open temperature capture…** in the drawer: the frame is shown with the
current palette, the slider scrubs a recording, and tapping the image reads that pixel's
°C (long-press clears). MIN / MAX come from the stored field, not from the image.

MediaStore may normalise the file name (an unknown extension can pick up a `.bin` tail);
the "saved" message always reports the name the file actually got.

## File layout

Little-endian throughout. A 32-byte header, then frames back to back:

| offset | type | field |
|---|---|---|
| 0  | char[4] | `MAGT` |
| 4  | u16 | version (1) |
| 6  | u16 | header size (32) |
| 8  | u16 | width — oriented grid, rotation/mirror already applied |
| 10 | u16 | height |
| 12 | u16 | flags (0) |
| 14 | u16 | reserved |
| 16 | i64 | wall clock of frame 0, ms since epoch |
| 24 | f32 | emissivity in effect |
| 28 | u32 | frame count — 0 or wrong means "derive from the file length" |

Each frame is `4 + width·height·2` bytes: a `u32` millisecond offset from the header's
epoch, then one `i16` per pixel in **centi-°C** (row-major, `value / 100 = °C`). That
spans ±327 °C at 0.01 °C, well past the module's −20…150 °C range.

The fixed stride is the point: frame *i* starts at `32 + i·(4 + width·height·2)`, so the
player seeks straight to it instead of walking the file. A recording cut short (crash,
battery) never gets its frame count patched — readers should fall back to the length.

At 160×120 a frame is 38.4 kB, so a minute at ~25 fps is about 57 MB. Nothing is
compressed; that is what keeps the seek arithmetic trivial.

Reading one in Python:

```python
import numpy as np

with open("MagViewer_20260923_141530.mgt", "rb") as f:
    head = f.read(32)
    assert head[:4] == b"MAGT"
    w, h = np.frombuffer(head, "<u2", 2, 8)
    stride = 4 + w * h * 2
    f.seek(0, 2); n = (f.tell() - 32) // stride
    f.seek(32 + 0 * stride)                      # frame 0
    t_ms = np.frombuffer(f.read(4), "<u4")[0]
    degC = np.frombuffer(f.read(w * h * 2), "<i2").reshape(h, w) / 100.0

print(n, "frames", degC[60, 80], "°C at the centre")
```
