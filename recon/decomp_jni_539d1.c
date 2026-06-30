// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        [sp - 0x4]! = fp
        r0 = 0x58     // 'X'
        r5 = r2       // arg3
        rsym._Znwj ()
        r4 = r0
        0x52ed0 ()    // 0x52ed0(0x58, 0x0, 0x0, 0x0)
        r0 = [r4]
        if (!r0) goto 0x53a1a // likely
        goto loc_0x000539ea;
    loc_0x00053a1a:
        r0 = r4
        0x52f8a ()    // 0x52f8a(0x0, 0x0, 0x0, 0x0)
        rsym._ZdlPv ()
        mov.w r5, -1
        
    loc_0x00053a28:
        // CODE XREF from sym.Java_cn_com_magnity_coresdk_MagDevice_LinkCamera @ 0x53a18(x)
        r0 = r5
        fp = [sp] + 4
        pop (r4, r5, r6, r7, pc)
        goto loc_0x000539f4;
        return r0;
    loc_0x000539f4:
        r0 = r4
        0x53184 ()    // 0x53184(0x0, 0x0, 0x0, 0x0)
        r1 = [0x000539fc] // [0x53a40:4]=0x2703fa "ttributes are absent" // "ttributes are absent"
        r5 = r0
        add.w r2, r5, r5, lsl 2
        r1 += pc
        add.w r6, r1, r2, lsl 2
        ldr.w r0, [r1, r2, lsl 2]
        if (!r0) goto 0x53a16 // likely
        goto loc_0x00053a0e;
    loc_0x00053a16:
        [r6] = r4
        goto 0x53a28
            goto loc_0x00053a0e;
    loc_0x00053a0e:
        0x52f8a ()    // 0x52f8a(0x0, 0x0, 0x0, 0x0)
        rsym._ZdlPv ()
        return r0;
}

