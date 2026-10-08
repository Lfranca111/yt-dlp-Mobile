.text
.macro FUNC name
.p2align 2
.global \name
.hidden \name
.type \name,%function
\name:
.endm
// Lowercase UTF-16 haystack versus ASCII literal. Exact bounded first match.
FUNC media_find_ascii16
    mov w3, #0
1:  cmp w3, w1
    b.gt 5f
    mov w4, #0
2:  ldrb w5, [x2, w4, uxtw]
    cbz w5, 4f
    add w6, w3, w4
    cmp w6, w1
    b.ge 5f
    ldrh w7, [x0, w6, uxtw #1]
    cmp w5, w7
    b.ne 3f
    add w4, w4, #1
    b 2b
3:  add w3, w3, #1
    b 1b
4:  mov w0, w3
    ret
5:  mov w0, #-1
    ret
// Boundary predicate for the original [/_.?&=:-] stream-hint expressions.
.Lboundary:
    cmp w0, #47
    b.eq 1f
    cmp w0, #95
    b.eq 1f
    cmp w0, #46
    b.eq 1f
    cmp w0, #63
    b.eq 1f
    cmp w0, #38
    b.eq 1f
    cmp w0, #61
    b.eq 1f
    cmp w0, #58
    b.eq 1f
    cmp w0, #45
    b.eq 1f
    mov w0, #0
    ret
1:  mov w0, #1
    ret
// audio kind0/video kind1; scan bounded tokens without regex or temporary strings.
FUNC media_hint16
    stp x29, x30, [sp, #-80]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    stp x25, x26, [sp, #64]
    mov x19, x0
    mov w20, w1
    mov w21, w2
    mov w22, #0
1:  cmp w22, w20
    b.ge 9f
    cbz w22, 2f
    sub w3, w22, #1
    ldrh w0, [x19, w3, uxtw #1]
    bl .Lboundary
    cbz w0, 8f
2:  adr x23, .LaudioHints
    mov w24, #5
    cbz w21, 3f
    adr x23, .LvideoHints
    mov w24, #7
3:  mov w25, #0
4:  ldrb w3, [x23, w25, uxtw]
    cbz w3, 5f
    add w4, w22, w25
    cmp w4, w20
    b.ge 6f
    ldrh w5, [x19, w4, uxtw #1]
    cmp w3, w5
    b.ne 6f
    add w25, w25, #1
    b 4b
5:  add w26, w22, w25
    cmp w26, w20
    b.eq 10f
    ldrh w0, [x19, w26, uxtw #1]
    bl .Lboundary
    cbnz w0, 10f
6:  add x23, x23, #16
    subs w24, w24, #1
    b.ne 3b
    cbz w21, 8f
    // A video resolution token is exactly 3 or 4 ASCII digits followed by p.
    mov w25, #0
7:  add w4, w22, w25
    cmp w4, w20
    b.ge 8f
    ldrh w5, [x19, w4, uxtw #1]
    sub w3, w5, #48
    cmp w3, #9
    b.hi 11f
    add w25, w25, #1
    cmp w25, #4
    b.le 7b
    b 8f
11: cmp w25, #3
    b.lt 8f
    cmp w5, #112
    b.ne 8f
    add w4, w4, #1
    cmp w4, w20
    b.eq 10f
    ldrh w0, [x19, w4, uxtw #1]
    bl .Lboundary
    cbnz w0, 10f
8:  add w22, w22, #1
    b 1b
9:  mov w0, #0
    b 12f
10: mov w0, #1
12: ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x25, x26, [sp, #64]
    ldp x29, x30, [sp], #80
    ret
FUNC media_url_score
    stp x29, x30, [sp, #-64]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    str x23, [sp, #48]
    mov x19, x0
    mov w20, w1
    mov w21, w2
    mov x22, x3
    adr x2, .Lmaster
    bl media_find_ascii16
    cmp w0, #0
    mov x23, #5000
    b.ge 4f
    mov x0, x19
    mov w1, w20
    mov w2, #8
    bl media_extensions16
    mov x23, #4500
    cbnz w0, 4f
    mov x0, x19
    mov w1, w20
    mov w2, #16
    bl media_extensions16
    mov x23, #4250
    cbnz w0, 4f
    mov x0, x19
    mov w1, w20
    mov w2, #7
    bl media_extensions16
    mov x23, #4000
    cbnz w0, 4f
    mov x0, x19
    mov w1, w20
    adr x2, .Lplayback
    bl media_find_ascii16
    mov x23, #3750
    cmp w0, #0
    b.ge 4f
    mov x23, #3000
4:  mov x0, x19
    mov w1, w20
    adr x2, .Lhls
    bl media_find_ascii16
    cmp w0, #0
    b.lt 5f
    add x23, x23, #400
    b 7f
5:  mov x0, x19
    mov w1, w20
    adr x2, .Ldash
    bl media_find_ascii16
    cmp w0, #0
    b.lt 6f
    add x23, x23, #350
    b 7f
6:  mov x0, x19
    mov w1, w20
    adr x2, .Lvideos
    bl media_find_ascii16
    cmp w0, #0
    b.lt 7f
    add x23, x23, #300
7:  cbz w21, 8f
    mov x0, #10000
    add x23, x23, x0
8:  cmp x22, #0
    csel x22, x22, xzr, ge
    movz x0, #0x4240
    movk x0, #0xf, lsl #16 // 1000000
    udiv x22, x22, x0
    add x0, x23, x22
    ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldr x23, [sp, #48]
    ldp x29, x30, [sp], #64
    ret
// Result codes follow StreamKind enum: combined0,video1,audio2,unknown3.
FUNC media_stream_kind
    stp x29, x30, [sp, #-48]!
    stp x19, x20, [sp, #16]
    str x21, [sp, #32]
    mov x19, x0
    mov w20, w1
    mov w21, w2
    adr x3, .LaudioItags
1:  ldr w4, [x3], #4
    cmp w4, #-1
    b.eq 2f
    cmp w4, w21
    b.eq 10f
    b 1b
2:  mov w2, #0
    bl media_hint16
    cbnz w0, 10f
    adr x3, .LvideoItags
3:  ldr w4, [x3], #4
    cmp w4, #-1
    b.eq 4f
    cmp w4, w21
    b.eq 11f
    b 3b
4:  mov x0, x19
    mov w1, w20
    mov w2, #1
    bl media_hint16
    cbnz w0, 11f
    mov x0, x19
    mov w1, w20
    adr x2, .Lmaster
    bl media_find_ascii16
    cmp w0, #0
    b.ge 12f
    mov x0, x19
    mov w1, w20
    mov w2, #16
    bl media_extensions16
    cbnz w0, 12f
    mov x0, x19
    mov w1, w20
    adr x2, .LmanifestSlash
    bl media_find_ascii16
    cmp w0, #0
    b.ge 12f
    cmp w20, #9
    b.lt 13f
    sub w1, w20, #9
    add x0, x19, w1, uxtw #1
    mov w1, #9
    adr x2, .Lmanifest
    bl media_find_ascii16
    cbz w0, 12f
13: mov w0, #3
    b 14f
10: mov w0, #2
    b 14f
11: mov w0, #1
    b 14f
12: mov w0, #0
14: ldp x19, x20, [sp, #16]
    ldr x21, [sp, #32]
    ldp x29, x30, [sp], #48
    ret
.section .rodata
.Laudiostart:
.LaudioHints:
    .asciz "audio"
    .zero 10
    .asciz "aac"
    .zero 12
    .asciz "m4a"
    .zero 12
    .asciz "opus"
    .zero 11
    .asciz "mp3"
    .zero 12
.LvideoHints:
    .asciz "video"
    .zero 10
    .asciz "avc"
    .zero 12
    .asciz "h264"
    .zero 11
    .asciz "h265"
    .zero 11
    .asciz "hevc"
    .zero 11
    .asciz "vp9"
    .zero 12
    .asciz "av1"
    .zero 12
.Laudiotags:
.LaudioItags: .word 139,140,141,171,172,249,250,251,-1
.LvideoItags: .word 133,134,135,136,137,138,160,242,243,244,247,248,264,266,271,272,278,298,299,302,303,308,313,315,-1
.Lmaster: .asciz "master.m3u8"
.Lplayback: .asciz "/videoplayback"
.Lhls: .asciz "/hls/"
.Ldash: .asciz "/dash/"
.Lvideos: .asciz "/videos/"
.LmanifestSlash: .asciz "/manifest/"
.Lmanifest: .asciz "/manifest"
.section .note.GNU-stack,"",%progbits
