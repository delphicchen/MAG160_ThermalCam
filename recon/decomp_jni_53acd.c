// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        push (r8, sb, fp)
        r8 = [0x00053adc] // [0x53b3c:4]=0x27031c " interleaved images can be read" // " interleaved images can be read"
        add.w r5, r2, r2, lsl 2 // arg3
        r4 = r0       // env
        r6 = r3       // arg4
        r8 += pc
        ldr.w r0, [r8, r5, lsl 2]
        if (!r0) goto 0x53b30 // likely
        goto loc_0x00053ae8;
    loc_0x00053b30:
        r6 = 0
        
    loc_0x00053b32:
        // CODE XREF from sym.Java_cn_com_magnity_coresdk_MagDevice_PrepareProcessImage @ 0x53b2e(x)
        r0 = r6
        pop (r8, sb, fp)
        pop (r4, r5, r6, r7, pc)
        goto loc_0x00053afe;
        return r0;
    loc_0x00053afe:
        r0 = r4
        r1 = r6
        0x58d70 ()    // 0x58d70(0x0, 0x0, 0x0, 0x0)
        (a, b) = compare (r0, 0)
        str.w r0, [sb]
        je 0x53b30    // likely
        goto loc_0x00053b0e;
    loc_0x00053b0e:
        add.w r0, r8, r5, lsl 2
        r1 = [0x00053b14] // [0x53b44:4]=0xfffffc1d
        r2 = 0
        r0 = [r0]
        r1 += pc
        0x5301e ()    // 0x5301e(0x464c457f, 0x53739, 0x0, 0x0)
        r6 = r0
        r0 = r6 + 1
        (a, b) = compare (r0, 1)
        ittt ls
        movls r0, r4
        r1 = sb
        0x58d7e ()    // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x464c4580, 0x53739, 0x0, 0x0)
        goto 0x53b32
        break;
}

