.text
.macro FUNC name
.p2align 2
.global \name
.hidden \name
.type \name,%function
\name:
.endm
// Unsigned decimal emission; minimum width w2, output x1, returns length.
FUNC media_decimal
    sub sp, sp, #32
    mov w3, #0
    mov x4, #10
1:  udiv x5, x0, x4
    msub x6, x5, x4, x0
    add w6, w6, #48
    strb w6, [sp, w3, uxtw]
    add w3, w3, #1
    mov x0, x5
    cbnz x0, 1b
    mov w5, #0
2:  cmp w3, w2
    b.ge 3f
    mov w6, #48
    strb w6, [x1], #1
    sub w2, w2, #1
    add w5, w5, #1
    b 2b
3:  add w0, w3, w5
4:  cbz w3, 5f
    sub w3, w3, #1
    ldrb w6, [sp, w3, uxtw]
    strb w6, [x1], #1
    b 4b
5:  add sp, sp, #32
    ret
FUNC media_xref
    stp x29, x30, [sp, #-64]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    str x23, [sp, #48]
    mov x19, x0
    mov w20, w1
    mov x21, x2
    mov x22, x2
    mov w23, #1
1:  cmp w23, w20
    b.ge 3f
    ldr x0, [x19, w23, uxtw #3]
    mov x1, x21
    mov w2, #10
    bl media_decimal
    add x21, x21, w0, uxtw
    adr x3, .LxrefSuffix
    mov w4, #10
2:  ldrb w5, [x3], #1
    strb w5, [x21], #1
    subs w4, w4, #1
    b.ne 2b
    add w23, w23, #1
    b 1b
3:  sub x0, x21, x22
    ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldr x23, [sp, #48]
    ldp x29, x30, [sp], #64
    ret
// PDF scaled page dimensions preserving Double scale then Int truncation.
FUNC media_pdf_size
    cmp w2, #6
    b.eq 1f
    cmp w2, #8
    b.ne 2f
1:  mov w4, w0
    mov w0, w1
    mov w1, w4
2:  cmp w0, w1
    csel w4, w0, w1, gt
    mov w5, #1
    cmp w4, #1
    csel w4, w4, w5, ge
    scvtf d0, w4
    mov w4, #1440
    scvtf d1, w4
    fdiv d1, d1, d0
    scvtf d0, w0
    scvtf d2, w1
    fmul d0, d0, d1
    fmul d2, d2, d1
    fcvtzs w0, d0
    fcvtzs w1, d2
    cmp w0, #1
    csel w0, w0, w5, ge
    cmp w1, #1
    csel w1, w1, w5, ge
    stp w0, w1, [x3]
    ret
// Exact HLS master serialization; URL validation stays at the Android adapter.
FUNC media_master16
    stp x29, x30, [sp, #-64]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    mov x19, x0
    mov w20, w1
    mov x21, x2
    mov w22, w3
    mov x23, x4
    mov x24, x4
    adr x0, .LmasterA
    bl .LappendAscii
    mov x0, x21
    mov w1, w22
    bl .Lappend16
    adr x0, .LmasterB
    bl .LappendAscii
    mov x0, x19
    mov w1, w20
    bl .Lappend16
    mov w0, #10
    strh w0, [x23], #2
    sub x0, x23, x24
    lsr x0, x0, #1
    ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x29, x30, [sp], #64
    ret
.LappendAscii:
    ldrb w1, [x0], #1
    cbz w1, 1f
    strh w1, [x23], #2
    b .LappendAscii
1:  ret
.Lappend16:
    cmp w1, #8
    b.lt 2f
    ld1 {v0.8h}, [x0], #16
    st1 {v0.8h}, [x23], #16
    sub w1, w1, #8
    b .Lappend16
2:  cbz w1, 1f
    ldrh w2, [x0], #2
    strh w2, [x23], #2
    sub w1, w1, #1
    b 2b
1:  ret
.section .rodata
.LxrefSuffix: .ascii " 00000 n \n"
.LmasterA: .asciz "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"recording-audio\",NAME=\"Audio\",DEFAULT=YES,AUTOSELECT=YES,URI=\""
.LmasterB: .asciz "\"\n#EXT-X-STREAM-INF:BANDWIDTH=2500000,AUDIO=\"recording-audio\"\n"
.section .note.GNU-stack,"",%progbits
