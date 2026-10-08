// Natural comparison over cached lowercase UTF-16 names.
// Matches regex \\d+|\\D+ token ordering, arbitrary-size ASCII decimal runs,
// leading-zero equivalence and token-count tie. Caller breaks ties on originals.
.text
.p2align 2
.global media_natural16
.hidden media_natural16
.type media_natural16,%function
media_natural16:
    stp x19, x20, [sp, #-32]!
    stp x21, x22, [sp, #16]
    mov w4, #0
    mov w5, #0
1:  cmp w4, w1
    b.ge 12f
    cmp w5, w3
    b.ge 13f
    ldrh w6, [x0, w4, uxtw #1]
    ldrh w7, [x2, w5, uxtw #1]
    sub w8, w6, #48
    cmp w8, #9
    cset w8, ls
    sub w9, w7, #48
    cmp w9, #9
    cset w9, ls
    mov w10, w4
    mov w11, w5
2:  cmp w10, w1
    b.ge 3f
    ldrh w6, [x0, w10, uxtw #1]
    sub w12, w6, #48
    cmp w12, #9
    cset w12, ls
    cmp w12, w8
    b.ne 3f
    add w10, w10, #1
    b 2b
3:  cmp w11, w3
    b.ge 4f
    ldrh w7, [x2, w11, uxtw #1]
    sub w12, w7, #48
    cmp w12, #9
    cset w12, ls
    cmp w12, w9
    b.ne 4f
    add w11, w11, #1
    b 3b
4:  mov w19, w4
    mov w20, w5
    and w12, w8, w9
    cbz w12, 8f
    // Trim zeros, leaving one zero for an all-zero token.
    sub w21, w10, #1
    sub w22, w11, #1
5:  cmp w19, w21
    b.ge 6f
    ldrh w6, [x0, w19, uxtw #1]
    cmp w6, #48
    b.ne 6f
    add w19, w19, #1
    b 5b
6:  cmp w20, w22
    b.ge 7f
    ldrh w7, [x2, w20, uxtw #1]
    cmp w7, #48
    b.ne 7f
    add w20, w20, #1
    b 6b
7:  sub w6, w10, w19
    sub w7, w11, w20
    cmp w6, w7
    b.ne 14f
8:  cmp w19, w10
    b.ge 10f
    cmp w20, w11
    b.ge 11f
    ldrh w6, [x0, w19, uxtw #1]
    ldrh w7, [x2, w20, uxtw #1]
    cmp w6, w7
    b.ne 14f
    add w19, w19, #1
    add w20, w20, #1
    b 8b
10: cmp w20, w11
    b.lt 15f
    mov w4, w10
    mov w5, w11
    b 1b
11: mov w0, #1
    b 16f
12: cmp w5, w3
    b.lt 15f
    mov w0, #0
    b 16f
13: mov w0, #1
    b 16f
14: cset w0, gt
    csinv w0, w0, wzr, gt
    b 16f
15: mov w0, #-1
16: ldp x21, x22, [sp, #16]
    ldp x19, x20, [sp], #32
    ret
.section .note.GNU-stack,"",%progbits
