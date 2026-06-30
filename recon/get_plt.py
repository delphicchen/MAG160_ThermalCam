import sys
from elftools.elf.elffile import ELFFile

elf = ELFFile(open('/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so', 'rb'))
symtab = elf.get_section_by_name('.dynsym')
rel_plt = elf.get_section_by_name('.rel.plt')

rels = list(rel_plt.iter_relocations())
# The PLT entry at 0x30024 corresponds to some relocation. Let's just print the first 25
for i in range(25):
    sym = symtab.get_symbol(rels[i]['r_info_sym'])
    print(f"PLT[{i}] -> {sym.name}")
