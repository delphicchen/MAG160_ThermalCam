// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        [sp - 0x4]! = fp
        sp -= 0x30
        r5 = r0       // env
        r0 = [0x00054498] // [0x54580:4]=0x2446f0
        r4 = r3       // arg4
        r0 += pc      // reloc.__stack_chk_guard
        r1 = [r0]
        r0 = [0x000544a0] // [0x54584:4]=0x26f95c "eam" // "eam"
        r1 = [r1]
        r0 += pc
        [var_2ch] = r1
        add.w r1, r2, r2, lsl 2 // arg3
        ldr.w r2, [r0, r1, lsl 2]
        (a, b) = compare (r2, 0)
        je 0x5455c    // likely
        goto loc_0x000544b0;
    loc_0x0005455c:
        vldr s0, [pc, 0x28]
        
    loc_0x00054560:
        // CODE XREF from sym.Java_cn_com_magnity_coresdk_MagDevice_SetFixPara @ 0x5455a(x)
        r0 = [0x00054564] // [0x545b8:4]=0x244624
        r1 = [var_2ch]
        r0 += pc      // reloc.__stack_chk_guard
        r0 = [r0]
        r0 = [r0]
        r0 -= r1
        itttt eq
        vmoveq r0, s0
        addeq sp, 0x30
        fp = [sp] + 4
        pop (r4, r5, r6, r7, pc)
        goto loc_0x0005457a;
    loc_0x000544b0: // orphan
         add.w r6, r0, r1, lsl 2
         r1 = [0x000544b8]        // [0x5458c:4]=0x1f8718
         r0 = r5
         r2 = r4
         r1 += pc                 // "fDistance" str.fDistance
         0x58b38 ()               // 0x58b38(0x0, 0x24cbd6, 0x0, 0x0)
         r1 = [0x000544c4]        // [0x54590:4]=0x1f8714
         r2 = r4
         [sp] = r0
         r0 = r5
         r1 += pc                 // "fEmissivity" str.fEmissivity
         0x58b38 ()               // 0x58b38(0x0, 0x24cbe0, 0x0, 0x0)
         r1 = [0x000544d0]        // [0x54594:4]=0x1f8712
         r2 = r4
         [var_4h] = r0
         r0 = r5
         r1 += pc                 // "fTemp" str.fTemp
         0x58b38 ()               // 0x58b38(0x0, 0x24cbec, 0x0, 0x0)
         r1 = 0xb8                // "fRH" // 0x54598
         [var_8h] = r0
         r0 = r5
         r2 = r4
         0x58b38 ()               // 0x58b38(0x0, 0x54598, 0x0, 0x0)
         r1 = [0x000544ec]        // [0x5459c:4]=0x1f86fe
         r2 = r4
         [var_ch_2] = r0
         r0 = r5
         r1 += pc                 // "fVisDistance" str.fVisDistance
         0x58b38 ()               // 0x58b38(0x0, 0x24cbf2, 0x0, 0x0)
         r1 = [0x000544f8]        // [0x545a0:4]=0x1f86fd
         r2 = r4
         [var_10h] = r0
         r0 = r5
         r1 += pc                 // "fRain" str.fRain
         0x58b38 ()               // 0x58b38(0x0, 0x24cbff, 0x0, 0x0)
         r1 = [0x00054508]        // [0x545a4:4]=0x1f86f5
         r2 = r4
         [var_14h] = r0
         r0 = r5
         r1 += pc                 // "fSnow" str.fSnow
         0x58b38 ()               // 0x58b38(0x0, 0x24cc05, 0x0, 0x0)
         r1 = [0x00054514]        // [0x545a8:4]=0x1f86ed
         r2 = r4
         [var_18h] = r0
         r0 = r5
         r1 += pc                 // "fExtrapara1" str.fExtrapara1
         0x58b38 ()               // 0x58b38(0x0, 0x24cc0b, 0x0, 0x0)
         r1 = [0x00054524]        // [0x545ac:4]=0x1f86eb
         r2 = r4
         [var_1ch] = r0
         r0 = r5
         r1 += pc                 // "fExtrapara2" str.fExtrapara2
         0x58b38 ()               // 0x58b38(0x0, 0x24cc17, 0x0, 0x0)
         r1 = [0x00054530]        // [0x545b0:4]=0x1f86e9
         r2 = r4
         [var_20h] = r0
         r0 = r5
         r1 += pc                 // "fTaoAtm" str.fTaoAtm
         0x58b38 ()               // 0x58b38(0x0, 0x24cc23, 0x0, 0x0)
         r1 = [0x00054540]        // [0x545b4:4]=0x1f86e3
         r2 = r4
         [var_24h] = r0
         r0 = r5
         r1 += pc                 // "fTaoFilter" str.fTaoFilter
         0x58b38 ()               // 0x58b38(0x0, 0x24cc2b, 0x0, 0x0)
         [var_28h] = r0
         r1 = sp                  // r13
         r0 = [r6]
         r2 = 2
         0x5317e ()               // 0x5317e(0x464c457f, 0x10078000, 0x2, 0x0)
         s0 = (float) r0 .
         
}

