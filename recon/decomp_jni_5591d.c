// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        push (r8, sb, sl, fp)
        sp -= 0xc
        fp = r0       // env
        r0 = [0x0005592c] // [0x559f8:4]=0x24325a
        r4 = r3       // arg4
        r6 = 0
        r0 += pc      // reloc.__stack_chk_guard
        (a, b) = compare (r4, 0)
        r0 = [r0]
        r0 = [r0]
        [var_8h] = r0
        je 0x559bc    // likely
        goto loc_0x0005593a;
    loc_0x000559bc:
        // CODE XREFS from sym.Java_cn_com_magnity_coresdk_MagDevice_GetTemperatureData @ 0x559b8(x), 0x559f6(x)
        r0 = [0x000559c0] // [0x55a00:4]=0x2431c8
        r1 = [var_8h]
        r0 += pc      // reloc.__stack_chk_guard
        r0 = [r0]
        r0 = [r0]
        r0 -= r1
        itttt eq
        moveq r0, r6
        addeq sp, 0xc
        pop (r8, sb, sl, fp)
        pop (r4, r5, r6, r7, pc)
        goto loc_0x000559d4;
            goto loc_0x0005593a;
    loc_0x0005593a:
        sl = [0x00055940] // [0x559fc:4]=0x26e4ba " OpenCL device (GPU, CPU, ACCELERATOR): " // " OpenCL device (GPU, CPU, ACCELERATOR): "
        add.w r5, r2, r2, lsl 2 // arg3
        sl += pc
        ldr.w r0, [sl, r5, lsl 2]
        if (!r0) goto 0x559bc // likely
        goto loc_0x0005594a;
        return r0;
    loc_0x0005594a:
        0x52fe0 ()    // 0x52fe0(0x0, 0x0, 0x0, 0x0)
        r6 = r0
        if (!r6) goto 0x559ba // likely
        goto loc_0x00055952;
    loc_0x000559ba:
        r6 = 0
        goto loc_0x000559d4;
    loc_0x000559d4:
        sym.imp.__stack_chk_fail () // void __stack_chk_fail(void)
        goto loc_0x0005596e;
        return r0;
    loc_0x0005596e:
        [r7 - 0x21] = (byte) r6
        r2 = r7 - 0x21
        r0 = [fp]
        r1 = r4
        r3 = [r0 + 0x2ec] // (pstr 0x00010028) "trixEi"
        r0 = fp       // r13
        r3 ()         // 0x11(0x10078000, 0x0, 0xffffffdf, 0x12)
        r8 = r0       // r13
        cmp.w r8, 0
        je 0x559bc    // unlikely
        goto loc_0x0005598c;
    loc_0x0005598c:
        add.w r0, sl, r5, lsl 2
        ldrd r1, r3, [r7, 8]
        r2 = sb << 2
        r0 = [r0]
        if (!r1) goto 0x559d8 // likely
        goto loc_0x0005599c;
    while (/* 0x000559d8 */) {
        r0 = [fp]
        r1 = r4
        r2 = r8
        r3 = 0
        r6 = [r0 + 0x30c] // (pstr 0x00010028) "trixEi"
        r0 = fp       // r13
        r6 ()         // 0x11(0x10078000, 0x0, 0x0, 0x0)
        r6 = 1
        goto 0x559bc
    }
    loc_0x000559e2:
        r0 = [fp]
        r1 = r4
        r2 = r8
        r3 = 0
        r6 = 0
        r5 = [r0 + 0x30c] // (pstr 0x00010028) "trixEi"
        r0 = fp       // r13
        r5 ()         // 0x11(0x10078000, 0x0, 0x0, 0x0)
        goto 0x559bc
            goto loc_0x0005599c;
    loc_0x0005599c:
        r1 = r8
        0x531b8 ()    // 0x531b8(0x0, 0x0, 0x0, 0x0)
        if (!r0) goto 0x559e2 // likely
        goto loc_0x000559a4;
        return r0;
}

