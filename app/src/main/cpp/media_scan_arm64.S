// AAPCS64. Bounded UTF-16 scanners preserve Java code-unit indexing.
.text
.macro FUNC name
.p2align 2
.global \name
.hidden \name
.type \name,%function
\name:
.endm
// Kotlin Char.isWhitespace = Character.isWhitespace || Character.isSpaceChar.
FUNC media_space16
    cmp w0, #9
    b.lo 2f
    cmp w0, #13
    b.ls 1f
    cmp w0, #28
    b.lo 3f
    cmp w0, #32
    b.ls 1f
3:  cmp w0, #0xa0
    b.eq 1f
    mov w1, #0x1680
    cmp w0, w1
    b.eq 1f
    sub w1, w0, #0x2000
    cmp w1, #10
    b.ls 1f
    mov w1, #0x2028
    cmp w0, w1
    b.eq 1f
    mov w1, #0x2029
    cmp w0, w1
    b.eq 1f
    mov w1, #0x202f
    cmp w0, w1
    b.eq 1f
    mov w1, #0x205f
    cmp w0, w1
    b.eq 1f
    cmp w0, #0x3000
    b.eq 1f
2:  mov w0, #0
    ret
1:  mov w0, #1
    ret
// find16(haystack, length, needle, length, ignoreAsciiCase): first index/-1.
FUNC media_find16
    mov w5, #0
    sub w6, w1, w3
    cmp w6, #0
    b.lt 5f
1:  cmp w5, w6
    b.gt 5f
    mov w7, #0
2:  cmp w7, w3
    b.ge 4f
    add w8, w5, w7
    ldrh w9, [x0, w8, uxtw #1]
    ldrh w10, [x2, w7, uxtw #1]
    cbz w4, 3f
    sub w11, w9, #65
    cmp w11, #25
    add w11, w9, #32
    csel w9, w11, w9, ls
    sub w11, w10, #65
    cmp w11, #25
    add w11, w10, #32
    csel w10, w11, w10, ls
3:  cmp w9, w10
    b.ne 6f
    add w7, w7, #1
    b 2b
6:  add w5, w5, #1
    b 1b
4:  mov w0, w5
    ret
5:  mov w0, #-1
    ret
// Delimited extension search. Mask: mp4=1,m4v=2,webm=4,m3u8=8,mpd=16.
FUNC media_extensions16
    stp x29, x30, [sp, #-64]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    mov x19, x0
    mov w20, w1
    mov w21, w2
    adr x22, .Lextensions
    mov w23, #0
    mov w24, #1
1:  tst w21, w24
    b.eq 4f
    mov w6, #0
2:  cmp w6, w20
    b.ge 4f
    ldrh w7, [x19, w6, uxtw #1]
    cmp w7, #46
    b.ne 3f
    mov w8, #0
    add w9, w6, #1
5:  ldrb w10, [x22, w8, uxtw]
    cbz w10, 6f
    cmp w9, w20
    b.ge 3f
    ldrh w11, [x19, w9, uxtw #1]
    sub w12, w11, #65
    cmp w12, #25
    add w12, w11, #32
    csel w11, w12, w11, ls
    cmp w10, w11
    b.ne 3f
    add w8, w8, #1
    add w9, w9, #1
    b 5b
6:  cmp w9, w20
    b.eq 7f
    ldrh w11, [x19, w9, uxtw #1]
    cmp w11, #63
    b.eq 7f
    cmp w11, #35
    b.eq 7f
3:  add w6, w6, #1
    b 2b
4:  add x22, x22, #5
    lsl w24, w24, #1
    add w23, w23, #1
    cmp w23, #5
    b.lt 1b
    mov w0, #0
    b 8f
7:  mov w0, #1
8:  ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x29, x30, [sp], #64
    ret
// Strict signed decimal conversion. Return 1 valid, 0 invalid, 2 Unicode fallback.
FUNC media_long16
    cbz w1, 9f
    mov w3, #0
    mov w4, #0
    ldrh w5, [x0]
    cmp w5, #45
    b.ne 1f
    mov w4, #1
    b 2f
1:  cmp w5, #43
    b.ne 3f
2:  mov w3, #1
    cmp w3, w1
    b.eq 9f
3:  mov x6, #0
    // floor(LONG_MAX / 10)
    movz x7, #0xcccc
    movk x7, #0xcccc, lsl #16
    movk x7, #0xcccc, lsl #32
    movk x7, #0x0ccc, lsl #48
4:  ldrh w5, [x0, w3, uxtw #1]
    cmp w5, #127
    b.hi 10f
    sub w5, w5, #48
    cmp w5, #9
    b.hi 9f
    cmp x6, x7
    b.hi 9f
    b.ne 5f
    add w8, w4, #7
    cmp w5, w8
    b.hi 9f
5:  mov x8, #10
    madd x6, x6, x8, x5
    add w3, w3, #1
    cmp w3, w1
    b.lt 4b
    cmp w4, #0
    cneg x6, x6, ne
    str x6, [x2]
    mov w0, #1
    ret
9:  mov w0, #0
    ret
10: mov w0, #2
    ret
// Lines -> (start,length) pairs. mode0 Kotlin CR/LF + Unicode trim;
// mode1 relay LF / CRLF + Java trim <= U+0020. No substring allocations here.
FUNC media_lines16
    stp x29, x30, [sp, #-80]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    stp x25, x26, [sp, #64]
    mov x19, x0
    mov w20, w1
    mov x21, x2
    mov w22, w3
    mov w23, #0
    mov w24, #0
1:  mov w25, w23
2:  cmp w23, w20
    b.ge 3f
    ldrh w0, [x19, w23, uxtw #1]
    cmp w0, #10
    b.eq 3f
    cbnz w22, 4f
    cmp w0, #13
    b.eq 3f
4:  add w23, w23, #1
    b 2b
3:  mov w26, w23
5:  cmp w25, w26
    b.ge 7f
    ldrh w0, [x19, w25, uxtw #1]
    cbz w22, 6f
    cmp w0, #32
    b.hi 7f
    b 8f
6:  bl media_space16
    cbz w0, 7f
8:  add w25, w25, #1
    b 5b
7:  cmp w26, w25
    b.le 11f
    sub w9, w26, #1
    ldrh w0, [x19, w9, uxtw #1]
    cbz w22, 9f
    cmp w0, #32
    b.hi 11f
    b 10f
9:  bl media_space16
    cbz w0, 11f
10: sub w26, w26, #1
    b 7b
11: sub w9, w26, w25
    stp w25, w9, [x21], #8
    add w24, w24, #1
    cmp w23, w20
    b.ge 13f
    ldrh w9, [x19, w23, uxtw #1]
    add w23, w23, #1
    cmp w9, #13
    b.ne 1b
    cmp w23, w20
    b.ge 1b
    ldrh w9, [x19, w23, uxtw #1]
    cmp w9, #10
    b.ne 1b
    add w23, w23, #1
    b 1b
13: mov w0, w24
    ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x25, x26, [sp, #64]
    ldp x29, x30, [sp], #80
    ret
// Shell-like command state machine; empty quoted tokens are omitted as before.
// Output buffer <= input length+1, spans <= 2*(input length+1).
FUNC media_tokens16
    stp x29, x30, [sp, #-96]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    stp x25, x26, [sp, #64]
    stp x27, x28, [sp, #80]
    mov x19, x0
    mov w20, w1
    mov x21, x2
    mov x22, x3
    mov w23, #0 // input index
    mov w24, #0 // output index
    mov w25, #0 // token start
    mov w26, #0 // quote
    mov w27, #0 // escape
    mov w28, #0 // token count
1:  cmp w23, w20
    b.ge 9f
    ldrh w0, [x19, w23, uxtw #1]
    add w23, w23, #1
    cbnz w27, 7f
    cmp w0, #92
    b.ne 2f
    cmp w26, #39
    b.eq 8f
    mov w27, #1
    b 1b
2:  cbz w26, 3f
    cmp w0, w26
    b.ne 8f
    mov w26, #0
    b 1b
3:  cmp w0, #39
    b.eq 4f
    cmp w0, #34
    b.ne 5f
4:  mov w26, w0
    b 1b
5:  mov w27, w0 // scratch code unit, preserved across whitespace call
    bl media_space16
    cbz w0, 6f
    mov w27, #0
    sub w9, w24, w25
    cbz w9, 1b
    stp w25, w9, [x22], #8
    add w28, w28, #1
    mov w25, w24
    b 1b
6:  mov w0, w27
7:  mov w27, #0
8:  strh w0, [x21, w24, uxtw #1]
    add w24, w24, #1
    b 1b
9:  cbnz w26, 11f
    cbz w27, 10f
    mov w0, #92
    strh w0, [x21, w24, uxtw #1]
    add w24, w24, #1
10: sub w9, w24, w25
    cbz w9, 12f
    stp w25, w9, [x22], #8
    add w28, w28, #1
12: mov w0, w28
    b 13f
11: mov w0, #-1
13: ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x25, x26, [sp, #64]
    ldp x27, x28, [sp, #80]
    ldp x29, x30, [sp], #96
    ret
// Match the original HLS attribute regex: boundary start/comma, case insensitive,
// quoted nonempty value OR nonempty unquoted up to comma. Scans after first colon.
FUNC media_attribute16
    stp x29, x30, [sp, #-64]!
    stp x19, x20, [sp, #16]
    stp x21, x22, [sp, #32]
    stp x23, x24, [sp, #48]
    mov x19, x0
    mov w20, w1
    mov x21, x2
    mov w22, w3
    mov x23, x4
    mov w24, #0
1:  cmp w24, w20
    b.ge 2f
    ldrh w5, [x19, w24, uxtw #1]
    add w24, w24, #1
    cmp w5, #58
    b.ne 1b
    b 3f
2:  mov w24, #0
3:  cmp w24, w20
    b.ge 12f
    mov w5, #0
4:  cmp w5, w22
    b.ge 6f
    add w6, w24, w5
    cmp w6, w20
    b.ge 12f
    ldrh w7, [x19, w6, uxtw #1]
    ldrh w8, [x21, w5, uxtw #1]
    sub w9, w7, #65
    cmp w9, #25
    add w9, w7, #32
    csel w7, w9, w7, ls
    sub w9, w8, #65
    cmp w9, #25
    add w9, w8, #32
    csel w8, w9, w8, ls
    cmp w7, w8
    b.ne 11f
    add w5, w5, #1
    b 4b
6:  add w6, w24, w22
    cmp w6, w20
    b.ge 12f
    ldrh w7, [x19, w6, uxtw #1]
    cmp w7, #61
    b.ne 11f
    add w6, w6, #1
    mov w8, w6
    cmp w6, w20
    b.ge 12f
    ldrh w7, [x19, w6, uxtw #1]
    cmp w7, #34
    b.ne 9f
    add w8, w6, #1
    mov w9, w8
7:  cmp w9, w20
    b.ge 9f
    ldrh w7, [x19, w9, uxtw #1]
    cmp w7, #34
    b.eq 8f
    add w9, w9, #1
    b 7b
8:  cmp w9, w8
    b.gt 10f
9:  mov w8, w6
    mov w9, w6
13: cmp w9, w20
    b.ge 14f
    ldrh w7, [x19, w9, uxtw #1]
    cmp w7, #44
    b.eq 14f
    add w9, w9, #1
    b 13b
14: cmp w9, w8
    b.eq 11f
10: sub w9, w9, w8
    stp w8, w9, [x23]
    mov w0, #1
    b 15f
11: cmp w24, w20
    b.ge 12f
    ldrh w7, [x19, w24, uxtw #1]
    add w24, w24, #1
    cmp w7, #44
    b.ne 11b
    b 3b
12: mov w0, #0
15: ldp x19, x20, [sp, #16]
    ldp x21, x22, [sp, #32]
    ldp x23, x24, [sp, #48]
    ldp x29, x30, [sp], #64
    ret
.section .rodata
.Lextensions:
    .asciz "mp4"
    .byte 0
    .asciz "m4v"
    .byte 0
    .asciz "webm"
    .asciz "m3u8"
    .asciz "mpd"
    .byte 0
.section .note.GNU-stack,"",%progbits
.text
// ASCII HTML attribute fast path, matching the existing quoted-value regex.
// Unicode-bearing tags use the original managed regex to retain \b semantics.
FUNC media_html_attribute16
    mov w5, #0
1:  cmp w5, w1
    b.ge 12f
    cbz w5, 2f
    sub w6, w5, #1
    ldrh w7, [x0, w6, uxtw #1]
    sub w8, w7, #48
    cmp w8, #9
    b.ls 11f
    orr w7, w7, #32
    sub w8, w7, #97
    cmp w8, #25
    b.ls 11f
    cmp w7, #95
    b.eq 11f
2:  mov w6, #0
3:  cmp w6, w3
    b.ge 4f
    add w7, w5, w6
    cmp w7, w1
    b.ge 12f
    ldrh w8, [x0, w7, uxtw #1]
    ldrh w9, [x2, w6, uxtw #1]
    sub w10, w8, #65
    cmp w10, #25
    add w10, w8, #32
    csel w8, w10, w8, ls
    sub w10, w9, #65
    cmp w10, #25
    add w10, w9, #32
    csel w9, w10, w9, ls
    cmp w8, w9
    b.ne 11f
    add w6, w6, #1
    b 3b
4:  add w6, w5, w3
5:  cmp w6, w1
    b.ge 12f
    ldrh w7, [x0, w6, uxtw #1]
    cmp w7, #32
    b.eq 6f
    sub w8, w7, #9
    cmp w8, #4
    b.hi 7f
6:  add w6, w6, #1
    b 5b
7:  cmp w7, #61
    b.ne 11f
    add w6, w6, #1
8:  cmp w6, w1
    b.ge 12f
    ldrh w7, [x0, w6, uxtw #1]
    cmp w7, #32
    b.eq 9f
    sub w8, w7, #9
    cmp w8, #4
    b.hi 10f
9:  add w6, w6, #1
    b 8b
10: cmp w7, #34
    b.eq 13f
    cmp w7, #39
    b.ne 11f
13: add w6, w6, #1
    mov w8, w6
14: cmp w8, w1
    b.ge 11f
    ldrh w7, [x0, w8, uxtw #1]
    cmp w7, #34
    b.eq 15f
    cmp w7, #39
    b.eq 15f
    add w8, w8, #1
    b 14b
15: cmp w8, w6
    b.eq 11f
    sub w8, w8, w6
    stp w6, w8, [x4]
    mov w0, #1
    ret
11: add w5, w5, #1
    b 1b
12: mov w0, #0
    ret
// GET request target span. Return target length, -1 for non-GET/missing required space.
FUNC media_http_path16
    cmp w1, #4
    b.lt 9f
    ldrh w3, [x0]
    cmp w3, #71
    b.ne 9f
    ldrh w3, [x0, #2]
    cmp w3, #69
    b.ne 9f
    ldrh w3, [x0, #4]
    cmp w3, #84
    b.ne 9f
    ldrh w3, [x0, #6]
    cmp w3, #32
    b.ne 9f
    mov w3, #4
1:  cmp w3, w1
    b.ge 2f
    ldrh w4, [x0, w3, uxtw #1]
    cmp w4, #32
    b.eq 3f
    add w3, w3, #1
    b 1b
2:  cbnz w2, 9f
3:  sub w0, w3, #4
    ret
9:  mov w0, #-1
    ret
.section .note.GNU-stack,"",%progbits
