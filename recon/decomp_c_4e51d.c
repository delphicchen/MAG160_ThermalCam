// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void sym.MAG_GetTemperatureProbe (int16_t arg1, int16_t arg2, int16_t arg3, int16_t arg_0h, int16_t arg_68h, int16_t arg_c0h, int16_t arg_218h, int16_t arg_21ch, int16_t arg_234h) {
        ~   push (r4, r5, r7, lr)
        r7 = var_8h
        (a, b) = compare (r0, 0x80) // arg1
        bhi 0x4e57c   // unlikely
        goto loc_0x0004e524;
    loc_0x0004e57c:
        r0 = 1
        r0 |= 0x8000 << 16
        pop (r4, r5, r7, pc)
        goto loc_0x0004e53c;
        return r0;
    loc_0x0004e53c:
        lr = [0x0004e544] // [0x4e588:4]=0x25081c
        lr += pc
        mla ip, r0, ip, lr
        ip = [ip + 4]
        cmp.w ip, 0
        je 0x4e57c    // likely
        goto loc_0x0004e550;
    loc_0x0004e550:
        mov.w lr, 0x498
        r5 = [0x0004e558] // [0x4e58c:4]=0x250802
        r4 = r0 * lr  // arg1
        r5 += pc
        r4 = [r5 + r4]
        if (!r4) goto 0x4e57c // likely
        goto loc_0x0004e560;
    loc_0x0004e560:
        r4 = [0x0004e564] // [0x4e590:4]=0x2507fa
        r4 += pc
        mla r0, r0, lr, r4
        r0 = [r0 + 0x4c] // arg1
        mla r1, r0, r2, r1
        r2 = r3       // arg4
        r0 = ip
        r3 = 0
        pop (r4, r5, r7, lr)
        goto 0x45014
        
    loc_0x00045014:
        // CODE XREF from sym.MAG_GetTemperatureProbe @ 0x4e578(x)
        // CODE XREF from sym.MAG_GetTemperatureProbe2 @ 0x4e5d6(x)
        // CALL XREFS from sym.MAG_EstimateUnderArmTempFromForeheadRect @ 0x503ec(x), 0x50442(x)
        push (r4, r5, r6, r7, lr)
        r7 = var_8h
        push (r8, sb, sl, fp)
        sp -= 0x14
        r6 = r3
        fp = r0
        (a, b) = compare (r6, 0)
        r5 = r2       // arg3
        r4 = r1       // arg2
        itt ne
        addne.w r0, fp, 0x21c
        sym.imp.pthread_mutex_lock ()
        r0 = [arg_218h]
        (a, b) = compare (r0, 0)
        ittt ne
        movwne r1, 0x7740
        r1 = [fp + r1]
        (a, b) = compare (r1, r4)
        bhi 0x45074   // unlikely
        goto loc_0x00045046;
        return r0;
    loc_0x00045046:
        r4 = 1
        (a, b) = compare (r0, 0)
        r4 |= 0xe4ae << 16
        it eq
        r4 += 4
        sym.imp.__errno ()
        r5 = 1
        (a, b) = compare (r6, 0)
        r5 |= 0x8000 << 16
        [r0] = r4
        je 0x4506a    // likely
        goto loc_0x00045062;
    loc_0x0004506a:
        // CODE XREF from sym.MAG_GetTemperatureProbe @ 0x45146(x)
        r0 = r5
        sp += 0x14
        pop (r8, sb, sl, fp)
        pop (r4, r5, r6, r7, pc)
        return r0;
    loc_0x00045074: // orphan
         r0 = r5 - 1
         (a, b) = compare (r0, 4)
         itte ls
         adrls r1, 0xcc           // 0x45148
         ldr.w r8, [r1, r0, lsl 2]
         mov.w r8, 3
         r0 = 0x1e2c              // ',\x1e'
         r5 = [fp + r0]
         r0 = r4
         r1 = r5
         rsym.__aeabi_uidiv ()
         mls r1, r0, r5, r4
         strd r4, r6, [sp, 4]
         ip = r1 + r8
         r2 = r1 - r8
         (a, b) = compare (r2, ip)
         ble 0x450ae              // likely

    loc_0x000450ae: // orphan
         mvns r1, r5
         r3 = 0x1e30              // '0\x1e'
         r1 = r8 * r1
         r3 += fp
         r6 = r0 + r8
         lr = r0 - r8
         [var_ch] = r5
         r5 = r5 << 1
         str.w ip, [sp, 0x10]
         r0 = r1 << 1
         add.w r8, r0, r4, lsl 1
         r0 = 0
         r1 = 0

         goto loc_0x0004510c;
    loc_0x0004510c: // orphan
         r4 = [var_10h]
         r8 += 2
         r2 = ip + 1
         (a, b) = compare (ip, r4)
         blt 0x450d4              // unlikely

    loc_0x000450d4: // orphan
         ip = r2
         cmp.w ip, 0
         blt 0x4510c              // unlikely

         goto loc_0x0004511a;
    loc_0x0004511a: // orphan
         // CODE XREF from sym.MAG_GetTemperatureProbe @ 0x450ac(x)
         rsym.__aeabi_uidiv ()
         r2 = [var_4h]
         uxth r1, r0
         r0 = fp                  // r13
         r3 = 1
         0x3f970 ()               // 0x3f970(0x10078000, 0x0, 0x0, 0x1)
         r5 = r0                  // r13
         r0 = [arg_68h]
         if (!r0) 
    loc_0x00045140: // orphan
         r0 = [var_8h_2]
         (a, b) = compare (r0, 0)
         bne 0x45062              // unlikely

    loc_0x00045062: // orphan
         r0 = arg_21ch
         sym.imp.pthread_mutex_unlock ()

    loc_0x00045146: // orphan
         
    loc_0x00045132: // orphan
         r2 = [arg_c0h]
         r0 = fp                  // r13
         r1 = r5
         fcn.000404e8 ()          // fcn.000404e8(0x10078000, 0x0, 0x0)
         r5 = r0                  // r13

         goto loc_0x000450dc;
    loc_0x000450dc: // orphan
         r2 = [var_ch]
         (a, b) = compare (ip, r2)
         (>=) 
         goto loc_0x000450e2;
    loc_0x000450e2: // orphan
         (a, b) = compare (lr, r6)
         bgt 0x4510c              // unlikely

         goto loc_0x000450e6;
    loc_0x000450e6: // orphan
         r2 = r8
         sb = lr

         goto loc_0x00045102;
    loc_0x00045102: // orphan
         r2 += r5
         sb = sl + 1
         (a, b) = compare (sl, r6)
         blt 0x450ea              // unlikely

    loc_0x000450ea: // orphan
         sl = sb
         cmp.w sl, 0
         blt 0x45102              // unlikely

         goto loc_0x000450f2;
    loc_0x000450f2: // orphan
         r4 = [r3]
         (a, b) = compare (sl, r4)
         itttt lt
         ldrlt.w r4, [fp, 0x234]
         ldrhlt r4, [r4, r2]
         r0 += r4
         r1 += 1

         goto loc_0x000450a8;
    loc_0x000450a8: // orphan
         r1 = 0
         r0 = 0
         
         goto loc_0x00045046;
}

