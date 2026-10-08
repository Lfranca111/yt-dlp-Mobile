.text
.macro FUNC name
.p2align 2
.global \name
.hidden \name
.type \name,%function
\name:
.endm
FUNC media_gap_kind
    cmp x0, #300
    b.lt 1f
    mov x1, #30000
    cmp x0, x1
    mov w0, #1
    b.le 3f
    mov w0, #2
    ret
1:  mov x1, #-300
    cmp x0, x1
    mov w0, #2
    b.le 3f
    mov w0, #0
3:  ret
FUNC media_retry
    cbnz w2, 2f
    mov x3, #400
    mul x0, x0, x3
    cmp x0, #400
    csel x0, x0, x3, ge
    mov x3, #1500
    cmp x0, x3
    csel x0, x0, x3, le
    cmp w1, #0
    b.le 4f
    b 3f
2:  mov x0, #900
3:  mov w3, #4
    cmp w1, #4
    csel w1, w1, w3, le
    lsl x0, x0, x1
    mov x3, #15000
    cmp x0, x3
    csel x0, x0, x3, le
4:  ret
FUNC media_digits16
    cbz w1, 2f
    mov w2, #0
1:  ldrh w3, [x0, w2, uxtw #1]
    sub w3, w3, #48
    cmp w3, #9
    b.hi 2f
    add w2, w2, #1
    cmp w2, w1
    b.lt 1b
    mov w0, #1
    ret
2:  mov w0, #0
    ret
// Exact site table, called after platform URI host validation and normalization.
FUNC media_site16
    adr x2, .Lsites
1:  ldrb w3, [x2, #63]
    cbz w3, 5f
    mov w4, #0
2:  ldrb w5, [x2, w4, uxtw]
    cbz w5, 3f
    cmp w4, w1
    b.ge 4f
    ldrh w6, [x0, w4, uxtw #1]
    cmp w5, w6
    b.ne 4f
    add w4, w4, #1
    b 2b
3:  cmp w4, w1
    b.ne 4f
    mov w0, w3
    ret
4:  add x2, x2, #64
    b 1b
5:  mov w0, #0
    ret
.section .rodata
.Lsites:
    .asciz "e621.net"
    .zero 54
    .byte 1
    .asciz "www.e621.net"
    .zero 50
    .byte 1
    .asciz "e6ai.net"
    .zero 54
    .byte 2
    .asciz "www.e6ai.net"
    .zero 50
    .byte 2
    .asciz "e926.net"
    .zero 54
    .byte 3
    .asciz "www.e926.net"
    .zero 50
    .byte 3
    .asciz "furbooru.org"
    .zero 50
    .byte 4
    .asciz "www.furbooru.org"
    .zero 46
    .byte 4
    .asciz "rule34.xxx"
    .zero 52
    .byte 5
    .asciz "www.rule34.xxx"
    .zero 48
    .byte 5
    .asciz "multporn.net"
    .zero 50
    .byte 6
    .asciz "www.multporn.net"
    .zero 46
    .byte 6
    .asciz "2.multporn.net"
    .zero 48
    .byte 6
    .asciz "tailspace.com"
    .zero 49
    .byte 7
    .asciz "www.tailspace.com"
    .zero 45
    .byte 7
    .asciz "yiffer.xyz"
    .zero 52
    .byte 8
    .asciz "www.yiffer.xyz"
    .zero 48
    .byte 8
    .asciz "luscious.net"
    .zero 50
    .byte 9
    .asciz "www.luscious.net"
    .zero 46
    .byte 9
    .asciz "members.luscious.net"
    .zero 42
    .byte 9
    .zero 64
.section .note.GNU-stack,"",%progbits
