import sys, struct, math, os
from unicorn import *
from unicorn.arm_const import *
from elftools.elf.elffile import ELFFile

def align(addr, alignment=0x1000):
    return (addr + alignment - 1) & ~(alignment - 1)

class Emu:
    def __init__(self, lib_path):
        self.mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_LITTLE_ENDIAN)
        self.lib_data = open(lib_path, "rb").read()
        self.elf = ELFFile(open(lib_path, "rb"))
        self.base = 0x10000000
        
        # Map segments
        mapped_size = 0
        for seg in self.elf.iter_segments():
            if seg['p_type'] == 'PT_LOAD':
                vaddr = self.base + seg['p_vaddr']
                memsz = align(seg['p_memsz'])
                flags = UC_PROT_ALL # hack
                try:
                    self.mu.mem_map(align(vaddr - 0x1000), memsz + 0x1000, flags)
                except Exception:
                    pass # already mapped
                self.mu.mem_write(vaddr, seg.data())
                
        # Stack
        self.stack = 0x20000000
        self.mu.mem_map(self.stack - 0x10000, 0x10000)
        self.mu.reg_write(UC_ARM_REG_SP, self.stack)
        
        # Heap
        self.heap = 0x30000000
        self.mu.mem_map(self.heap, 0x100000)
        self.heap_top = self.heap
        
    def hook_code(self, uc, address, size, user_data):
        # Extremely basic hook to catch malloc/free if they go to PLT
        # Just logging for now
        pass

emu = Emu("/tmp/apk_x/lib/armeabi-v7a/libcoresdk.so")
print("Emulation environment initialized successfully.")
