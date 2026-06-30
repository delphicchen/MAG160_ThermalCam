// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        push (r8, sb, sl, fp)
        sp -= 0xc
        r5 = [0x00053b54] // [0x53c0c:4]=0x2702a0 "scalar" // "scalar"
        r4 = r2       // arg3
        add.w r6, r4, r4, lsl 2
        r8 = r0       // env
        r5 += pc
        fp = r3       // arg4
        ldr.w r0, [r5, r6, lsl 2]
        if (!r0) goto 0x53b88 // likely
        goto loc_0x00053b66;
    loc_0x00053b88:
        r5 = 0
        
    loc_0x00053b8a:
        // CODE XREF from sym.Java_cn_com_magnity_coresdk_MagDevice_StartProcessImage @ 0x53c08(x)
        r0 = r5
        sp += 0xc
        pop (r8, sb, sl, fp)
        pop (r4, r5, r6, r7, pc)
        goto loc_0x00053b6c;
        return r0;
    loc_0x00053b6c:
        ldrd r0, sb, [r7, 8]
        add.w sl, r5, r6, lsl 2
        orr.w r1, sb, r0
        r0 = [sl]
        r5 = r1 & 0x80000000
        fcn.00053018 () // fcn.00053018(0x464c457f)
        orrs r0, r5
        je 0x53b94    // unlikely
        goto loc_0x00053b88;
    while (/* 0x00053b94 */) {
    }
    while (/* 0x00053bcc */) {
    }
    loc_0x00053be4:
        r0 = [0x00053be8] // [0x53c14:4]=0x270216
        r0 += pc
        add.w r4, r0, r6, lsl 2
        r0 = r8
        r1 = r4 + 4
        0x58d7e ()    // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x0, 0x2c3e04, 0x0, 0x0)
        r1 = r4 + 8
        r0 = r8
        0x58d7e ()    // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x0, 0x2c3e08, 0x0, 0x0)
        r1 = r4 + 0xc
        r0 = r8
        0x58d7e ()    // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x0, 0x2c3e0c, 0x0, 0x0)
        goto 0x53b8a
            goto loc_0x00053b88;
        return r0;
}

