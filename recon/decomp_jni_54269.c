// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        push (r8, sb, fp)
        sp -= 0x30
        r4 = r0       // env
        r0 = [0x00054278] // [0x5444c:4]=0x24490e // (pstr 0x00017fe1) "d_Restore_WMMXD"
        r1 = str.type_id // [0x54450:4]=0x26fb80 str.type_id // "type_id"
        sb = r3       // arg4
        r0 += pc      // reloc.__stack_chk_guard
        r1 += pc
        r0 = [r0]
        r0 = [r0]
        [var_2ch] = r0
        add.w r0, r2, r2, lsl 2 // arg3
        ldr.w r0, [r1, r0, lsl 2]
        (a, b) = compare (r0, 0)
        beq.w 0x54430 // likely
        goto loc_0x00054292;
    loc_0x00054430:
        r0 = 0
        
    loc_0x00054432:
        // CODE XREF from sym.Java_cn_com_magnity_coresdk_MagDevice_GetFixPara @ 0x5442e(x)
        r1 = [0x00054434] // [0x54484:4]=0x244752
        r2 = [var_2ch]
        r1 += pc      // reloc.__stack_chk_guard
        r1 = [r1]
        r1 = [r1]
        r1 -= r2
        ittt eq
        addeq sp, 0x30
        pop (r8, sb, fp)
        pop (r4, r5, r6, r7, pc)
        goto loc_0x00054448;
        goto loc_0x000542aa;
        return r0;
    loc_0x000542aa:
        r0 = [r4]
        r8 = pc + 0x1a8
        r2 = [0x000542b4] // [0x54454:4]=0x1f8918
        r1 = r6
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fDistance" str.fDistance
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cbd6, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [sp]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x000542d8] // [0x5445c:4]=0x1f8900
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fEmissivity" str.fEmissivity
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cbe0, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_4h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x000542f8] // [0x54460:4]=0x1f88ea
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fTemp" str.fTemp
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cbec, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_8h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r2 = 0x14c    // "fRH" // 0x54464
        r1 = r6
        r3 = r8
        r5 = [r0 + 0x178]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x54464, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_ch_2]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x0005433c] // [0x54468:4]=0x1f88ae
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fVisDistance" str.fVisDistance
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cbf2, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_10h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x0005435c] // [0x5446c:4]=0x1f8899 // "."
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fRain" str.fRain
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cbff, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_14h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x00054380] // [0x54470:4]=0x1f887d // "0[E"
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fSnow" str.fSnow
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cc05, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_18h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x000543a0] // [0x54474:4]=0x1f8861
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fExtrapara1" str.fExtrapara1
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cc0b, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_1ch]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x000543c4] // [0x54478:4]=0x1f884b
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fExtrapara2" str.fExtrapara2
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cc17, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_20h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x000543e4] // [0x5447c:4]=0x1f8835
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fTaoAtm" str.fTaoAtm
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cc23, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_24h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [0x00054408] // [0x54480:4]=0x1f881b
        r3 = r8
        r5 = [r0 + 0x178]
        r2 += pc      // "fTaoFilter" str.fTaoFilter
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x24cc2b, 0x54458)
        r2 = r0
        r0 = [r4]
        r3 = [var_28h]
        r1 = sb
        r5 = [r0 + 0x1bc]
        r0 = r4
        r5 ()         // 0xfffffffe(0x0, 0x0, 0x0, 0x0)
        r0 = [r4]
        r1 = r6
        r2 = [r0 + 0x5c]
        r0 = r4
        r2 ()         // 0xfffffffe(0x0, 0x0, -1, 0x0)
        r0 = 1
        goto 0x54432
        break;
    loc_0x00054292: // orphan
         r1 = sp                  // r13
         0x53178 ()               // 0x53178(0x0, 0x10078000, 0x0, 0x0)
         r0 = [r4]
         r1 = sb
         r2 = [r0 + 0x7c]
         r0 = r4
         r2 ()                    // 0xfffffffe(0x0, 0x0, -1, 0x0)
         r6 = r0
         (a, b) = compare (r6, 0)
         beq.w 0x54430            // likely

}

