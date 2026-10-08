.text
.macro FUNC name
.p2align 2
.global \name
.hidden \name
.type \name,%function
\name:
.endm
// JPEG component marker scanner. Preserves the existing accepted SOF markers.
FUNC media_jpeg
    cmp w1, #4
    b.lt 9f
    ldrb w2, [x0]
    ldrb w3, [x0, #1]
    cmp w2, #255
    b.ne 9f
    cmp w3, #216
    b.ne 9f
    mov w2, #2
1:  add w3, w2, #9
    cmp w3, w1
    b.ge 9f
    ldrb w4, [x0, w2, uxtw]
    cmp w4, #255
    b.ne 9f
    add w4, w2, #1
    ldrb w4, [x0, w4, uxtw]
    cmp w4, #218
    b.eq 9f
    cmp w4, #217
    b.eq 9f
    add w5, w2, #2
    ldrb w6, [x0, w5, uxtw]
    add w5, w5, #1
    ldrb w7, [x0, w5, uxtw]
    orr w6, w7, w6, lsl #8
    cmp w6, #2
    b.lt 9f
    add w5, w2, #2
    add w5, w5, w6
    cmp w5, w1
    b.gt 9f
    cmp w4, #192
    b.lt 2f
    cmp w4, #195
    b.le 8f
    cmp w4, #198
    b.eq 8f
    cmp w4, #199
    b.eq 8f
2:  mov w2, w5
    b 1b
8:  ldrb w0, [x0, w3, uxtw]
    ret
9:  mov w0, #3
    ret
// magic(bytes,count,type): 1 ftyp,2 EBML,3 Ogg,4 MP3,5 TS,6 TS packets,7 ELF.
FUNC media_magic
    cmp w2, #6
    b.eq 6f
    cmp w2, #7
    b.eq 7f
    cmp w1, #8
    b.lt 9f
    cmp w2, #1
    b.eq 1f
    cmp w2, #2
    b.eq 2f
    cmp w2, #3
    b.eq 3f
    cmp w2, #4
    b.eq 4f
    cmp w2, #5
    b.eq 5f
    b 9f
1:  ldr w3, [x0, #4]
    movz w4, #0x7466
    movk w4, #0x7079, lsl #16
    b 10f
2:  ldr w3, [x0]
    movz w4, #0x451a
    movk w4, #0xa3df, lsl #16
    b 10f
3:  ldr w3, [x0]
    movz w4, #0x674f
    movk w4, #0x5367, lsl #16
    b 10f
4:  ldrb w3, [x0]
    cmp w3, #255
    b.ne 11f
    ldrb w3, [x0, #1]
    and w3, w3, #0xe0
    mov w4, #0xe0
    b 10f
11: cmp w3, #73
    b.ne 9f
    ldrb w3, [x0, #1]
    cmp w3, #68
    b.ne 9f
    ldrb w3, [x0, #2]
    mov w4, #51
    b 10f
5:  ldrb w3, [x0]
    mov w4, #71
    b 10f
6:  cmp w1, #377
    b.lt 9f
    ldrb w3, [x0]
    cmp w3, #71
    b.ne 9f
    ldrb w3, [x0, #188]
    cmp w3, #71
    b.ne 9f
    ldrb w3, [x0, #376]
    mov w4, #71
    b 10f
7:  cmp w1, #20
    b.lt 9f
    ldr w3, [x0]
    movz w4, #0x457f
    movk w4, #0x464c, lsl #16
10: cmp w3, w4
    cset w0, eq
    ret
9:  mov w0, #0
    ret
// Hexadecimal serializer with NEON 16-byte batches; no overread or overwrite.
FUNC media_hex
    adr x3, .Lhex
    ld1 {v3.16b}, [x3]
    movi v4.16b, #15
1:  cmp w1, #16
    b.lt 2f
    ld1 {v0.16b}, [x0], #16
    ushr v1.16b, v0.16b, #4
    and v2.16b, v0.16b, v4.16b
    tbl v1.16b, {v3.16b}, v1.16b
    tbl v2.16b, {v3.16b}, v2.16b
    st2 {v1.16b, v2.16b}, [x2], #32
    sub w1, w1, #16
    b 1b
2:  cbz w1, 3f
    ldrb w4, [x0], #1
    lsr w5, w4, #4
    and w4, w4, #15
    ldrb w5, [x3, w5, uxtw]
    ldrb w4, [x3, w4, uxtw]
    strb w5, [x2], #1
    strb w4, [x2], #1
    sub w1, w1, #1
    b 2b
3:  ret
FUNC media_pdf_literal
    mov w3, #0
    mov w4, #0
1:  cmp w3, w1
    b.ge 3f
    ldrh w5, [x0, w3, uxtw #1]
    cmp w5, #92
    b.eq 2f
    cmp w5, #40
    b.eq 2f
    cmp w5, #41
    b.ne 4f
2:  mov w6, #92
    strh w6, [x2, w4, uxtw #1]
    add w4, w4, #1
4:  strh w5, [x2, w4, uxtw #1]
    add w4, w4, #1
    add w3, w3, #1
    b 1b
3:  mov w0, w4
    ret
// ETA kernel; state/reset remains with the Kotlin download adapter.
// progress s0, starting progress s1, elapsed milliseconds x0. -1 means no sample.
FUNC media_eta
    cmp x0, #2000
    b.lt 9f
    fsub s2, s0, s1
    mov w1, #0xcccd
    movk w1, #0x3e4c, lsl #16 // 0.2f
    fmov s3, w1
    fcmp s2, s3
    b.lt 9f
    fcvt d2, s2
    scvtf d3, x0
    mov w1, #1000
    scvtf d4, w1
    fdiv d3, d3, d4
    fdiv d2, d2, d3
    fcmp d2, #0.0
    b.le 9f
    mov w1, #100
    scvtf s3, w1
    fsub s3, s3, s0
    fcvt d3, s3
    fdiv d3, d3, d2
    frintm d4, d3
    fsub d5, d3, d4
    fmov d6, #0.5
    fcmp d5, d6
    b.lt 8f
    fmov d6, #1.0
    fadd d4, d4, d6
8:  fcvtzs x0, d4
    cmp x0, #0
    csel x0, x0, xzr, ge
    ret
9:  mov x0, #-1
    ret
.section .rodata
.Lhex: .ascii "0123456789ABCDEF"
.section .note.GNU-stack,"",%progbits
