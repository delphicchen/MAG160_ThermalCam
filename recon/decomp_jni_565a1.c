// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        push (r8, sb, sl, fp)
        sp -= 0x4b0
        sp -= 4
        r4 = r0       // env
        r0 = [0x000565b8] // [0x56afc:4]=0x2425d0
        r5 = r3       // arg4
        r3 = var_3a8h
        r0 += pc      // reloc.__stack_chk_guard
        r1 = r2       // arg3
        r2 = r3
        mov.w r3, 0x104 // (pstr 0x000091f0) "eoContrast"
        r0 = [r0]
        r0 = [r0]
        [r7 - 0x24] = r0
        r0 = r4
        0x588e8 ()    // 0x588e8(0x0, 0x0, 0x10077ed0, 0x104)
        (a, b) = compare (r0, 0)
        beq.w 0x56ac4 // likely
        goto loc_0x000565d6;
    loc_0x00056ac4:
        // CODE XREF from sym.Java_cn_com_magnity_coresdk_MagDevice_LoadDDT @ 0x56ab8(x)
        mov.w sl, -1
        
    loc_0x00056ac8:
        r0 = [0x00056acc] // [0x56b8c:4]=0x2420ba // "\""
        r1 = [r7 - 0x24]
        r0 += pc      // reloc.__stack_chk_guard
        r0 = [r0]
        r0 = [r0]
        r0 -= r1
        itttt eq
        moveq r0, sl
        addeq.w sp, sp, 0x4b0
        sp += 4
        pop (r8, sb, sl, fp)
        it eq
        pop (r4, r5, r6, r7, pc)
        goto loc_0x00056ae8;
        goto loc_0x00056606;
        return r0;
    loc_0x00056606:
        r0 = r6
        0x53184 ()    // 0x53184(0x0, 0x0, 0x0, 0x0)
        r1 = str.clGetDeviceInfo // [0x56b00:4]=0x26d7e4 str.clGetDeviceInfo // "clGetDeviceInfo"
        sl = r0
        add.w r2, sl, sl, lsl 2
        [var_18h] = r2
        r1 += pc
        add.w r5, r1, r2, lsl 2
        ldr.w r0, [r1, r2, lsl 2]
        if (!r0) goto 0x5662c // likely
        goto loc_0x00056624;
    loc_0x0005662c:
        r0 = var_3a8h
        r1 = var_34h
        [r5] = r6
        rsym.MAG_ProbeDDT () // rsym.MAG_ProbeDDT(0x100783a8, 0x10078034, 0x0, 0x0)
        (a, b) = compare (r0, 0)
        beq.w 0x56aa8 // unlikely
        goto loc_0x0005663c;
    loc_0x00056aa8:
        r0 = [r5]
        if (!r0) goto 0x56ab4 // unlikely
        goto loc_0x00056aac;
    loc_0x00056ab4:
        r0 = 0
        [r5] = r0
        goto 0x56ac4
            goto loc_0x00056aac;
    loc_0x00056aac:
        0x52f8a ()    // 0x52f8a(0x0, 0x0, 0x0, 0x0)
        rsym._ZdlPv ()
        goto loc_0x0005664a;
        return r0;
    loc_0x000565d6: // orphan
         sb = [r7 + 8]
         bic r8, r5, 3
         r0 = 0x58                // 'X'
         cmp.w sb, 1
         it lt
         mov.w sb, 4              // (pstr 0x00010101) "OutputArray15getOGlBufferRefEv"
         cmp.w r8, 1
         it lt
         mov.w r8, 4              // (pstr 0x00010101) "OutputArray15getOGlBufferRefEv"
         rsym._Znwj ()
         r6 = r0
         0x52ed0 ()               // 0x52ed0(0x58, 0x0, 0x0, 0x0)
         r0 = [r6]
         (a, b) = compare (r0, 0)
         beq.w 0x56aba            // likely

    loc_0x00056aba: // orphan
         r0 = r6
         0x52f8a ()               // 0x52f8a(0x0, 0x0, 0x0, 0x0)
         rsym._ZdlPv ()

         goto loc_0x00056606;
    loc_0x0005663c: // orphan
         r1 = [var_3ch]
         r0 = r1 - 0x50
         cmp.w r0, 0x9d0
         bhi.w 0x56aa8            // likely

         goto loc_0x0005664a;
    loc_0x0005664a: // orphan
         r2 = [var_40h]
         r3 = 0x75d
         r0 = r2 - 0x3c
         (a, b) = compare (r0, r3)
         bhs.w 0x56aa8            // likely

         goto loc_0x0005665a;
    loc_0x0005665a: // orphan
         ldrd r0, r3, [r7, 0xc]
         (a, b) = compare (r3, 0)
         beq.w 0x56a50            // unlikely

    loc_0x00056a50: // orphan
         strd sb, sl, [sp]
         r3 = r8
         [var_8h] = r0
         r0 = r4
         0x53334 ()               // 0x53334(0x0, 0x0, 0x0, 0x0)
         if (!r0) 
         goto loc_0x00056a60;
    loc_0x00056a60: // orphan
         ldrd r0, r1, [sp, 0x3c]
         r2 = var_24h
         r3 = [0x00056a68]        // [0x56b84:4]=0xffffcb5f
         strd r0, r1, [sp, 0x1c]
         stm.w r2, (r0, r1, r8, sb)
         r1 = 0
         r3 += pc
         r0 = [r5]
         r2 = var_3a8h
         [sp] = r1
         r1 = var_1ch
         0x5324c ()               // 0x5324c(0x464c457f, 0x1007801c, 0x100783a8, 0x535d5)
         if (r0) 
    loc_0x00056a82: // orphan
         r0 = [0x00056a84]        // [0x56b88:4]=0x26d376 "ize.operator()() == Size(_cols, _rows)" // "ize.operator()() == Size(_cols, _rows)"
         r1 = [var_18h]
         r0 += pc
         add.w r6, r0, r1, lsl 2
         r0 = r4
         r1 = r6 + 4
         0x58d7e ()               // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x0, 0x2c3e04, 0x0, 0x0)
         r1 = r6 + 8
         r0 = r4
         0x58d7e ()               // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x0, 0x2c3e08, 0x0, 0x0)
         r1 = r6 + 0xc
         r0 = r4
         0x58d7e ()               // sym.Java_cn_com_magnity_coresdk_MagDevice_DislinkCamera+0x533a // 0x58d7e(0x0, 0x2c3e0c, 0x0, 0x0)

         goto loc_0x00056664;
    loc_0x00056664: // orphan
         [var_10h] = r5
         r1 = r3
         r0 = [r4]
         r2 = [r0 + 0x7c]
         r0 = r4
         r2 ()                    // 0xfffffffe(0x0, 0x0, -1, 0x0)
         fp = r0
         r0 = [r4]
         r2 = [0x0005667c]        // [0x56b04:4]=0x1f65cb
         r5 = pc + 0x48c
         r1 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "version" str.version
         r0 = r4
         r3 = r5
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc51, 0x56b08)
         r2 = r0
         r0 = [r4]
         r3 = [var_38h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = fp
         r2 = [0x000566a4]        // [0x56b0c:4]=0x1f64fa
         r3 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "fpaWidth" str.fpaWidth
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cba6, 0x56b08)
         r2 = r0
         r0 = [r4]
         r3 = [var_3ch]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = fp
         r2 = [0x000566c8]        // [0x56b10:4]=0x1f64df
         r3 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "fpaHeight" str.fpaHeight
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cbaf, 0x56b08)
         r2 = r0
         r0 = [r4]
         r3 = [var_40h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = fp
         r2 = [0x000566ec]        // [0x56b14:4]=0x1f6550
         r3 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "serialNumber" str.serialNumber
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc44, 0x56b08)
         r2 = r0
         r0 = [r4]
         r3 = [var_ach]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = fp
         r2 = [0x00056710]        // [0x56b18:4]=0x1f653d
         r5 = var_34h
         r3 = [0x00056718]        // [0x56b1c:4]=0x1f64a0
         r6 = [r0 + 0x178]
         r2 += pc                 // "cameraType" str.cameraType
         r3 += pc                 // "Ljava/lang/String//" str.Ljava_lang_String_
         r0 = r4
         str.w fp, [sp, 0x14]
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc59, 0x24cbbe)
         r1 = r5 + 0x30
         fp = r0
         r0 = r4
         0x587f4 ()               // 0x587f4(0x0, 0x10078064, 0x24cc59, 0x24cbbe)
         r6 = r0
         (a, b) = compare (r6, 0)
         je 0x56750               // likely

    loc_0x00056750: // orphan
         r0 = [r4]
         r2 = [0x00056758]        // [0x56b20:4]=0x1f6502
         r3 = [0x0005675c]        // [0x56b24:4]=0x1f6458
         r6 = [r0 + 0x178]
         r2 += pc                 // "cameraName" str.cameraName
         r1 = [var_14h]
         r3 += pc                 // "Ljava/lang/String//" str.Ljava_lang_String_
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc64, 0x24cbbe)
         r1 = r5 + 0x50
         fp = r0
         r0 = r4
         0x587f4 ()               // 0x587f4(0x0, 0x50, 0x24cc64, 0x24cbbe)
         r6 = r0
         (a, b) = compare (r6, 0)
         je 0x56794               // likely

    loc_0x00056794: // orphan
         r0 = [r4]
         r3 = 0x394               // "J" // 0x56b2c
         r2 = [0x0005679c]        // [0x56b28:4]=0x1f64cb
         r5 = [var_14h]
         r6 = [r0 + 0x178]
         r2 += pc                 // "fileTime" str.fileTime
         r0 = r4
         fp = [r7 + 0x10]
         r1 = r5
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc6f, 0x56b2c)
         r2 = r0
         r0 = [r4]
         ldrd r1, r3, [sp, 0xa4]
         r6 = [r0 + 0x1b8]
         r0 = r4
         strd r1, r3, [sp]
         r1 = fp
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x36c               // "F" // 0x56b34
         r2 = [0x000567c8]        // [0x56b30:4]=0x1f64a6
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "altitude" str.altitude
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc78, 0x56b34)
         r2 = r0
         r0 = [r4]
         r3 = [var_b0h]
         r1 = fp
         r6 = [r0 + 0x1bc]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x354               // "D" // 0x56b3c
         r2 = [0x000567ec]        // [0x56b38:4]=0x1f648d
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "latitude" str.latitude
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc81, 0x56b3c)
         r2 = r0
         r0 = [r4]
         vldr d16, [sp, 0xb4]
         r1 = fp
         r3 = [r0 + 0x1c0]
         r0 = r4
         vstr d16, [sp]
         r3 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, -1)
         r0 = [r4]
         r3 = 0x32c               // "D" // 0x56b3c
         r2 = [0x00056814]        // [0x56b40:4]=0x1f646e
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "longitude" str.longitude
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc8a, 0x56b3c)
         r2 = r0
         r0 = [r4]
         vldr d16, [sp, 0xbc]
         r1 = fp
         r3 = [r0 + 0x1c0]
         r0 = r4
         vstr d16, [sp]
         r3 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, -1)
         r0 = [r4]
         r3 = 0x2d0               // "I" // 0x56b08
         r2 = [0x0005683c]        // [0x56b44:4]=0x1f6450
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "paletteIndex" str.paletteIndex
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cc94, 0x56b08)
         r2 = r0
         r0 = [r4]
         r3 = [var_2f0h]
         r1 = fp
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x2ac               // "I" // 0x56b08
         r2 = [0x0005685c]        // [0x56b48:4]=0x1f643b
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "tempUnit" str.tempUnit
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cca1, 0x56b08)
         r2 = r0
         r0 = [r4]
         r3 = [var_2f4h]
         r1 = fp
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         fp = pc + 0x2b8
         r2 = [0x00056880]        // [0x56b4c:4]=0x1f641e
         r1 = r5
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "emissivity" str.emissivity
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccaa, 0x56b36)
         r2 = r0
         r0 = [r4]
         r3 = [var_2f8h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1bc]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x000568a4]        // [0x56b50:4]=0x1f6407
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "airTemp" str.airTemp
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccb5, 0x56b36)
         r2 = r0
         r0 = [r4]
         r1 = [r7 + 0x10]
         r3 = [var_2fch]
         r6 = [r0 + 0x1bc]
         r0 = r4
         fp = r1
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x26c               // "F" // 0x56b34
         r2 = [0x000568c8]        // [0x56b54:4]=0x1f63eb
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "taoAtm" str.taoAtm
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccbd, 0x56b34)
         r2 = r0
         r0 = [r4]
         r3 = [var_300h]
         r1 = fp
         r6 = [r0 + 0x1bc]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x24c               // "F" // 0x56b34
         r2 = [0x000568ec]        // [0x56b58:4]=0x1f63d0
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "taoFilter" str.taoFilter
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccc4, 0x56b34)
         r2 = r0
         r0 = [r4]
         r3 = [var_304h]
         r1 = fp
         r6 = [r0 + 0x1bc]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x228               // "F" // 0x56b34
         r2 = [0x0005690c]        // [0x56b5c:4]=0x1f63b8
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "objDistance" str.objDistance
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccce, 0x56b34)
         r2 = r0
         r0 = [r4]
         r3 = [var_308h]
         r1 = fp
         r6 = [r0 + 0x1bc]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r3 = 0x238               // "Z" // 0x56b64
         r2 = [0x00056930]        // [0x56b60:4]=0x1f63a2
         r1 = r5
         r6 = [r0 + 0x178]
         r2 += pc                 // "usingManualEnlarge" str.usingManualEnlarge
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccda, 0x56b64)
         r2 = r0
         r0 = [r4]
         ldrb.w r3, [sp, 0x30c]
         r1 = fp
         r6 = [r0 + 0x1a4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         fp = pc + 0x1b8
         r2 = [0x00056954]        // [0x56b68:4]=0x1f638d
         r1 = r5
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "lowerLimitTemp" str.lowerLimitTemp
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cced, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_310h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x00056978]        // [0x56b6c:4]=0x1f637a
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "upperLimitTemp" str.upperLimitTemp
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24ccfc, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_314h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x0005699c]        // [0x56b70:4]=0x1f6367
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "grayScale1" str.grayScale1
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cd0b, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_318h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x000569bc]        // [0x56b74:4]=0x1f6350
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "grayScale2" str.grayScale2
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cd16, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_31ch]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x000569e0]        // [0x56b78:4]=0x1f6339
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "autoEnlargeRange" str.autoEnlargeRange
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cd21, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_320h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x00056a00]        // [0x56b7c:4]=0x1f6328
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "brightOffset" str.brightOffset
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cd32, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_324h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [0x00056a24]        // [0x56b80:4]=0x1f6313
         r3 = fp
         r6 = [r0 + 0x178]
         r2 += pc                 // "contrastOffset" str.contrastOffset
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x0, 0x24cd3f, 0x56b0a)
         r2 = r0
         r0 = [r4]
         r3 = [var_328h]
         r1 = [r7 + 0x10]
         r6 = [r0 + 0x1b4]
         r0 = r4
         r6 ()                    // 0xfffffffe(0x0, 0x280003, 0x0, 0x0)
         r0 = [r4]
         r1 = r5
         r2 = [r0 + 0x5c]
         r0 = r4
         r2 ()                    // 0xfffffffe(0x0, 0x0, -1, 0x0)
         ldrd r1, r2, [sp, 0x3c]
         r0 = [r7 + 0xc]
         r5 = [var_10h]

         goto loc_0x0005677a;
    loc_0x0005677a: // orphan
         r0 = [r4]
         r2 = fp                  // r13
         r1 = [r7 + 0x10]
         r3 = r6
         ip = [r0 + 0x1a0]
         r0 = r4
         ip ()                    // 0xfffffffe(0x0, 0x280003, 0x10078000, 0x0)
         r0 = [r4]
         r1 = r6
         r2 = [r0 + 0x5c]
         r0 = r4
         r2 ()                    // 0xfffffffe(0x0, 0x0, -1, 0x0)

         goto loc_0x00056736;
    loc_0x00056736: // orphan
         r0 = [r4]
         r2 = fp                  // r13
         r1 = [r7 + 0x10]
         r3 = r6
         ip = [r0 + 0x1a0]
         r0 = r4
         ip ()                    // 0xfffffffe(0x0, 0x280003, 0x10078000, 0x0)
         r0 = [r4]
         r1 = r6
         r2 = [r0 + 0x5c]
         r0 = r4
         r2 ()                    // 0xfffffffe(0x0, 0x0, -1, 0x0)

         goto loc_0x00056624;
    loc_0x00056624: // orphan
         0x52f8a ()               // 0x52f8a(0x0, 0x0, 0x0, 0x0)
         rsym._ZdlPv ()

}

