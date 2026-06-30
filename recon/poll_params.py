import usb.core, struct, time

dev = usb.core.find(idVendor=0x833c, idProduct=0x0001) or usb.core.find(idVendor=0x833c, idProduct=0x0002)
dev.set_configuration()

def cmd(c):
    dev.write(0x03, struct.pack('<II', c, 0), timeout=500)
    return bytes(dev.read(0x82, 4096, timeout=1000))

print("GET_PARAM1:", cmd(0x6BB6B66B).hex())
print("GET_PARAM2:", cmd(0x6BB6B66C).hex())
