#ifndef MEDIA_ARM64_H
#define MEDIA_ARM64_H
#include <stdint.h>
#include <stddef.h>
// Strings use Java/Kotlin UTF-16 code units, never modified UTF-8.
int media_find16(const uint16_t *, int, const uint16_t *, int, int);
int media_space16(unsigned);
int media_extensions16(const uint16_t *, int, unsigned);
int media_natural16(const uint16_t *, int, const uint16_t *, int);
int media_lines16(const uint16_t *, int, int *, int);
int media_tokens16(const uint16_t *, int, uint16_t *, int *);
int media_attribute16(const uint16_t *, int, const uint16_t *, int, int *);
int media_long16(const uint16_t *, int, int64_t *);
int media_jpeg(const uint8_t *, int);
int media_magic(const uint8_t *, int, int);
void media_hex(const uint8_t *, int, char *);
int media_pdf_literal(const uint16_t *, int, uint16_t *);
int64_t media_eta(float, float, int64_t);
#endif
