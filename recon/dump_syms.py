import sys
from elftools.elf.elffile import ELFFile

elf = ELFFile(open('/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so', 'rb'))
symtab = elf.get_section_by_name('.dynsym')

for section in elf.iter_sections():
    if section.name.startswith('.rel'):
        for rel in section.iter_relocations():
            if rel['r_offset'] >= 0x30000 and rel['r_offset'] <= 0x30100:
                sym = symtab.get_symbol(rel['r_info_sym']) if rel['r_info_sym'] != 0 else None
                name = sym.name if sym else "none"
                print(f"Reloc at 0x{rel['r_offset']:x} -> {name}")
