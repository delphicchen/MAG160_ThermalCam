// callconv: r0 arm16 (r0, r1, r2, r3, stack);
void * jni_native (JNIEnv *env, jobject thiz) {
        ~   push (r4, r5, r6, r7, lr)
        r7 = var_ch
        [sp - 0x4]! = fp
        r5 = r0       // env
        r4 = r2       // arg3
        r0 = [r5]
        r1 = r4
        r2 = 0
        r3 = [r0 + 0x2a4]
        r0 = r5
        r3 ()         // 0xfffffffe(0x0, 0x0, 0x0, -1)
        r6 = r0
        rsym.MAG_SetStorageDir ()
        r0 = [r5]
        r1 = r4
        r2 = r6
        r3 = [r0 + 0x2a8]
        r0 = r5
        r3 ()         // 0xfffffffe(0x0, 0x0, 0x0, -1)
        r0 = 1
        fp = [sp] + 4 // r13
        pop (r4, r5, r6, r7, pc) // r13
}

