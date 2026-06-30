// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void sym.MAG_GetTemperatureData (int16_t arg1) {
        ~   push (r7, lr)
        r7 = sp
        sp -= 8
        (a, b) = compare (r0, 0x80) // arg1
        bhi 0x4de96   // unlikely
        goto loc_0x0004de4a;
    loc_0x0004de96:
        r0 = 0
        sp += 8
        pop (r7, pc)
        goto loc_0x0004de62;
        return r0;
    loc_0x0004de62:
        lr = [0x0004de68] // [0x4dea0:4]=0x250ef6 // "6"
        lr += pc
        mla ip, r0, ip, lr
        ip = [ip + 4]
        cmp.w ip, 0
        je 0x4de96    // likely
        goto loc_0x0004de76;
    loc_0x0004de76:
        mov.w lr, 0x498
        lr = r0 * lr  // arg1
        r0 = [0x0004de80] // [0x4dea4:4]=0x250edc
        r0 += pc
        r0 = [r0 + lr]
        if (!r0) goto 0x4de96 // likely
        return r0;
    loc_0x0004de88:
        r0 = 0
        [sp] = r0
        r0 = ip
        fcn.0004640c () // fcn.0004640c(0x0, 0x0, 0x0, 0x0)
        sp += 8
        pop (r7, pc)
}

