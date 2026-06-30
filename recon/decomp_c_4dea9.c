// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void sym.MAG_GetTemperatureData_Raw (int16_t arg1, int16_t arg2, int16_t arg3, int16_t arg4, int16_t arg_0h) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        push (r8, sb, sl, fp)
        sp -= 0x34
        r4 = r0       // arg1
        r6 = r2       // arg3
        sl = r1       // arg2
        (a, b) = compare (r4, 0x80)
        bhi 0x4df00   // unlikely
        goto loc_0x0004debc;
    loc_0x0004df00:
        r2 = 0
        
    loc_0x0004df02:
        // CODE XREFS from sym.MAG_GetTemperatureData_Raw @ 0x4e0e8(x), 0x4e110(x), 0x4e13e(x), 0x4e14e(x)
        r0 = r2
        sp += 0x34
        pop (r8, sb, sl, fp)
        pop (r4, r5, r6, r7, pc)
        goto loc_0x0004decc;
        return r0;
    loc_0x0004decc:
        r1 = [0x0004ded0] // [0x4e154:4]=0x250e8e // "3"
        r1 += pc
        mla r0, r4, r0, r1
        r5 = [r0 + 4]
        if (!r5) goto 0x4df00 // likely
        goto loc_0x0004ded8;
    loc_0x0004ded8:
        mov.w r0, 0x498
        r1 = [0x0004dee0] // [0x4e158:4]=0x250e7c
        r0 = r4 * r0
        r1 += pc
        r0 = [r1 + r0]
        if (!r0) goto 0x4df00 // likely
        goto loc_0x0004dee6;
    loc_0x0004dee6:
        r8 = 1
        cmp.w sl, 0
        r8 |= 0xe4ae << 16
        it ne
        (a, b) = compare (r6, 4)
        bhi 0x4df0c   // unlikely
        goto loc_0x0004def8;
    loc_0x0004df0c:
        r0 = [r5 + 0x218]
        (a, b) = compare (r0, 0)
        beq.w 0x4e0dc // likely
        goto loc_0x0004df16;
    loc_0x0004e0dc:
        r4 = r8 + 4   // (pstr 0x00010101) "OutputArray15getOGlBufferRefEv"
        sym.imp.__errno ()
        [r0] = r4
        r2 = 0
        goto 0x4df02
            goto loc_0x0004df16;
    loc_0x0004df16:
        r2 = 0x4ae8   // (pstr 0x00000028) "4 \b(\x1c\x1b\x06"
        r0 = 0x4af8
        sb = [r5 + r2]
        r2 = 0x4afc
        ip = [r5 + r2]
        fp = [r5 + 0x234]
        r1 = [r5 + r0]
        r2 = 0x4aec
        r8 = [r5 + r2]
        ldrh.w lr, [fp, ip, lsl 1] // "utArrayEPvi"
        ldrh.w r2, [fp, r1, lsl 1]
        (a, b) = compare (r3, 0) // arg4
        je 0x4df76    // likely
        goto loc_0x0004df44;
        return r0;
    loc_0x0004df76: // orphan
         r0 = [0x0004df78]        // [0x4e15c:4]=0x250ddc
         mov.w r1, 0x498
         r3 = 0xfffc              // "InputArrayEPvi"
         r0 += pc
         r3 |= 0x3fff << 16
         mla r0, r4, r1, r0
         ldrd r1, r0, [r0, 0x4c]
         r4 = r0 * r1
         cmp.w r4, r6, lsr 2
         it hs
         r4 = r6 >> 2
         (a, b) = compare (r2, lr)
         bls.w 0x4e0ea            // likely

    loc_0x0004e0ea: // orphan
         if (!r4) 
    loc_0x0004e10e: // orphan
         // CODE XREF from sym.MAG_GetTemperatureData_Raw @ 0x4e0da(x)
         r2 = 1
         
         goto loc_0x0004e0ec;
    loc_0x0004e0ec: // orphan
         (a, b) = compare (r4, 4)
         blo 0x4e140              // likely

    loc_0x0004e140: // orphan
         r0 = sl
         r1 = r4

         goto loc_0x0004e146;
    loc_0x0004e146: // orphan
         [r0] + 4 = r8
         r1 -= 1
         bne 0x4e146              // likely

         goto loc_0x0004e14e;
    loc_0x0004e14e: // orphan
         
         goto loc_0x0004e0f0;
    loc_0x0004e0f0: // orphan
         ands.w r2, r4, r3
         je 0x4e140               // likely

         goto loc_0x0004e0f6;
    loc_0x0004e0f6: // orphan
         r1 = r4 - r2
         add.w r0, sl, r2, lsl 2
         vdup.32 q8, r8
         r3 = r2

    loc_0x0004e102: // orphan
         vst1.32 (d16, d17), [sl]!
         r3 -= 4
         bne 0x4e102              // likely

         goto loc_0x0004e10a;
    loc_0x0004e10a: // orphan
         (a, b) = compare (r4, r2)
         bne 0x4e144              // unlikely

    loc_0x0004e144: // orphan
         r2 = 1

         goto loc_0x0004dfa0;
    loc_0x0004dfa0: // orphan
         r2 -= lr
         r1 = sb - r8
         r6 = r3
         r0 = 0
         r3 = r2 >> 0x1f
         r5 = lr
         rsym.__aeabi_ldivmod ()
         (a, b) = compare (r4, 0)
         beq.w 0x4e10e            // likely

         goto loc_0x0004dfba;
    loc_0x0004dfba: // orphan
         (a, b) = compare (r4, 4)
         blo.w 0x4e112            // likely

    loc_0x0004e112: // orphan
         sb = sl
         r6 = fp                  // r13
         ip = r5

    loc_0x0004e118: // orphan
         r2 = (word) [r6] + 2
         r4 -= 1
         r5 = r2 - ip
         umull r3, r2, r0, r5
         asr.w r3, r5, 0x1f
         mla r2, r0, r3, r2
         mla r2, r1, r5, r2
         r2 += r8
         [sb] + 4 = r2
         mov.w r2, 1
         bne 0x4e118              // likely

         goto loc_0x0004e13e;
    loc_0x0004e13e: // orphan
         
         goto loc_0x0004dfc0;
    loc_0x0004dfc0: // orphan
         ands.w r2, r4, r6
         beq.w 0x4e112            // likely

         goto loc_0x0004dfc8;
    loc_0x0004dfc8: // orphan
         vmov.32 d16[0], r0
         [var_10h] = r4
         r4 -= r2
         add.w r6, fp, r2, lsl 1  // r13
         add.w sb, sl, r2, lsl 2
         vdup.32 q9, r8
         vmov.32 d16[1], r1
         [var_ch_2] = r0
         r0 = r2
         [var_8h] = r1
         vorr d17, d16, d16
         vdup.32 q10, r5
         str.w r8, [sp, 0x14]
         [var_18h] = r5
         [var_4h] = r2

    loc_0x0004dff6: // orphan
         [var_2ch] = r0
         vmov.32 r5, d16[0]
         vld1.16 (d22), [fp]!
         vmovl.u16 q11, d22
         str.w fp, [sp, 0x30]
         vmov.32 fp, d17[0]
         vsub.i32 q11, q11, q10
         vmovl.s32 q12, d23
         vmovl.s32 q11, d22
         vmov.32 r2, d24[0]
         vmov.32 ip, d24[1]
         vmov.32 r8, d23[0]
         vmov.32 r3, d23[1]
         vmov.32 lr, d25[1]
         umull r0, r1, r5, r2
         mla r1, r5, ip, r1
         vmov.32 ip, d22[0]
         [var_24h] = r0
         umull r5, r0, fp, r8
         mla r0, fp, r3, r0
         vmov.32 r3, d16[1]
         [var_1ch] = r5
         vmov.32 r5, d17[1]
         mla r0, r5, r8, r0
         vmov.32 r5, d16[0]
         [var_28h] = r0
         mla r0, r3, r2, r1
         vmov.32 r2, d22[1]
         r3 = [var_1ch]
         [var_20h] = r0
         vmov.32 d23[0], r3
         r3 = [var_24h]
         umull fp, r1, r5, ip
         mla r1, r5, r2, r1
         vmov.32 r5, d25[0]
         vmov.32 r2, d17[0]
         vmov.32 d24[0], r3
         vmov.32 d22[0], fp
         fp = [var_30h]           // r13
         umull r0, r8, r2, r5
         mla r2, r2, lr, r8
         vmov.32 d25[0], r0
         vmov.32 r0, d17[1]
         mla r0, r0, r5, r2
         vmov.32 r2, d16[1]
         vmov.32 d25[1], r0
         r0 = [var_2ch]
         r0 -= 4
         mla r1, r2, ip, r1
         r2 = [var_20h]
         vmov.32 d24[1], r2
         r2 = [var_28h]
         vmov.32 d23[1], r2
         vshrn.i64 d25, q12, 0x20
         vmov.32 d22[1], r1
         vshrn.i64 d24, q11, 0x20
         vadd.i32 q11, q9, q12
         vst1.32 (d22, d23), [sl]!
         bne 0x4dff6              // likely

         goto loc_0x0004e0ca;
    loc_0x0004e0ca: // orphan
         r0 = [var_10h]
         r1 = [var_4h]
         ldrd r8, ip, [sp, 0x14]
         (a, b) = compare (r0, r1)
         ldrd r1, r0, [sp, 8]
         bne 0x4e118              // unlikely

         goto loc_0x0004e0da;
    loc_0x0004e0da: // orphan
         
         goto loc_0x0004df44;
    loc_0x0004df44: // orphan
         r0 += r5
         strd r0, r2, [sp, 0x2c]
         r2 = [r5 + 0xc0]         // segment.NOTE
         r0 = r5
         r1 = r8
         r3 = ip
         str.w lr, [sp, 0x18]
         fcn.000404e8 ()          // fcn.000404e8(0x0, 0x0, 0x134)
         r8 = r0
         r0 = [var_2ch_2]
         r2 = [r5 + 0xc0]         // segment.NOTE
         r1 = sb
         r3 = [r0]
         r0 = r5
         fcn.000404e8 ()          // fcn.000404e8(0x0, 0x0, 0x134)
         r2 = [var_30h_2]
         sb = r0
         lr = [var_18h_2]

         goto loc_0x0004def8;
    loc_0x0004def8: // orphan
         sym.imp.__errno ()
         str.w r8, [r0]

}

