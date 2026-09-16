# A real package (not a namespace one) with a name that cannot collide with
# Real-ESRGAN's own `realesrgan/archs`: running `python realesrgan/train.py` puts that
# directory first on sys.path, so a top-level `archs` here would be shadowed by it.
