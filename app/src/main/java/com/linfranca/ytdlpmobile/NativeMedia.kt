package com.linfranca.ytdlpmobile

/** ARM64 kernels, with portable behavior on other ABIs and local JVM tests.
 * Android framework access, URI/Unicode semantics and cancellation stay managed.
 * Native allocations/errors are not swallowed. Batch APIs avoid per-comparison JNI.
 */
internal object NativeMedia {
    @JvmField val enabled: Boolean = try {
        System.loadLibrary("media_arm64")
        true
    } catch (_: UnsatisfiedLinkError) { false } catch (_: SecurityException) { false }

    @JvmStatic private external fun findNative(text: String, needle: String, ignore: Boolean): Int
    @JvmStatic private external fun extensionsNative(text: String, mask: Int): Boolean
    @JvmStatic private external fun urlPartsNative(host: String, path: String, query: String, mode: Int): Boolean
    @JvmStatic private external fun linesNative(text: String, mode: Int): IntArray
    @JvmStatic private external fun tokensNative(text: String): Array<String>
    @JvmStatic private external fun attributesNative(lines: Array<String>, key: String): Array<String?>
    @JvmStatic private external fun orderNative(original: Array<String>, lower: Array<String>): IntArray
    @JvmStatic private external fun longNative(text: String): LongArray
    @JvmStatic private external fun jpegNative(bytes: ByteArray): Int
    @JvmStatic private external fun magicNative(bytes: ByteArray, count: Int, kind: Int): Boolean
    @JvmStatic private external fun hexNative(bytes: ByteArray): String
    @JvmStatic private external fun literalNative(text: String): String
    @JvmStatic private external fun etaNative(progress: Float, start: Float, elapsed: Long): Long

    @JvmStatic private external fun gapNative(delta: Long): Int
    @JvmStatic private external fun retryNative(target: Int, failures: Int, exception: Boolean): Long
    @JvmStatic private external fun digitsNative(text: String): Boolean
    @JvmStatic private external fun siteNative(host: String): Int
    @JvmStatic private external fun xrefNative(offsets: LongArray): ByteArray
    @JvmStatic private external fun pdfSizeNative(w: Int, h: Int, rotation: Int): IntArray
    @JvmStatic private external fun masterNative(video: String, audio: String): String

    @JvmStatic fun gapKind(delta: Long): Int = if (enabled) gapNative(delta) else
        if (delta in 300L..30_000L) 1 else if (delta > 30_000L || delta <= -300L) 2 else 0
    @JvmStatic fun retry(target: Int, failures: Int, exception: Boolean): Long {
        if (enabled) return retryNative(target, failures, exception)
        val base = if (exception) 900L else (target * 400L).coerceIn(400L, 1500L)
        return if (exception || failures > 0) (base * (1L shl minOf(4, failures))).coerceAtMost(15_000L) else base
    }
    fun asciiDigits(text: String): Boolean = if (enabled) digitsNative(text) else
        text.isNotEmpty() && text.all { it in '0'..'9' }
    fun site(host: String): Int = if (enabled) siteNative(host) else when (host) {
        "e621.net", "www.e621.net" -> 1
        "e6ai.net", "www.e6ai.net" -> 2
        "e926.net", "www.e926.net" -> 3
        "furbooru.org", "www.furbooru.org" -> 4
        "rule34.xxx", "www.rule34.xxx" -> 5
        "multporn.net", "www.multporn.net", "2.multporn.net" -> 6
        "tailspace.com", "www.tailspace.com" -> 7
        "yiffer.xyz", "www.yiffer.xyz" -> 8
        "luscious.net", "www.luscious.net", "members.luscious.net" -> 9
        else -> 0
    }
    fun xref(offsets: LongArray): ByteArray = if (enabled) xrefNative(offsets) else buildString {
        for (id in 1 until offsets.size) append(offsets[id].toString().padStart(10, '0')).append(" 00000 n \n")
    }.toByteArray(Charsets.US_ASCII)
    fun pdfSize(w: Int, h: Int, rotation: Int): IntArray {
        if (enabled) return pdfSizeNative(w, h, rotation)
        val width = if (rotation == 6 || rotation == 8) h else w
        val height = if (rotation == 6 || rotation == 8) w else h
        val factor = 1440.0 / maxOf(width, height).coerceAtLeast(1)
        return intArrayOf((width * factor).toInt().coerceAtLeast(1), (height * factor).toInt().coerceAtLeast(1))
    }
    fun master(video: String, audio: String): String? = if (enabled) masterNative(video, audio) else null

    @JvmStatic private external fun scoreNative(text: String, active: Boolean, captured: Long): Long
    @JvmStatic private external fun kindNative(text: String, itag: Int): Int
    fun score(text: String, active: Boolean, captured: Long): Long? = if (enabled) scoreNative(text, active, captured) else null
    fun streamKind(text: String, itag: Int): Int? = if (enabled) kindNative(text, itag) else null

    @JvmStatic private external fun htmlAttributesNative(tags: Array<String>, key: String): Array<String?>
    @JvmStatic private external fun httpPathNative(text: String, requireSpace: Boolean): String
    private val htmlPatterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    fun htmlAttributes(tags: Array<String>, key: String): Array<String?> {
        val pattern = htmlPatterns.getOrPut(key) {
            Regex("""\b${Regex.escape(key)}\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        }
        if (!enabled) return Array(tags.size) { pattern.find(tags[it])?.groupValues?.get(1) }
        val asciiIndices = tags.indices.filter { i -> tags[i].all { it.code < 128 } }
        val native = htmlAttributesNative(Array(asciiIndices.size) { tags[asciiIndices[it]] }, key)
        val result = arrayOfNulls<String>(tags.size)
        asciiIndices.forEachIndexed { i, index -> result[index] = native[i] }
        tags.indices.filter { i -> tags[i].any { it.code >= 128 } }.forEach { i ->
            result[i] = pattern.find(tags[i])?.groupValues?.get(1)
        }
        return result
    }
    fun htmlAttribute(tag: String, key: String): String? = htmlAttributes(arrayOf(tag), key)[0]
    @JvmStatic fun httpPath(text: String, requireSpace: Boolean): String {
        if (enabled) return httpPathNative(text, requireSpace)
        if (!text.startsWith("GET ")) return ""
        val end = text.indexOf(' ', 4)
        if (end < 0 && requireSpace) return ""
        return text.substring(4, if (end < 0) text.length else end)
    }

    fun find(text: String, needle: String, ignoreCase: Boolean = false): Int =
        if (enabled && (!ignoreCase || text.all { it.code < 128 } && needle.all { it.code < 128 }))
            findNative(text, needle, ignoreCase) else text.indexOf(needle, ignoreCase = ignoreCase)

    fun extensions(text: String, mask: Int): Boolean = if (enabled) extensionsNative(text, mask) else {
        val names = arrayOf("mp4", "m4v", "webm", "m3u8", "mpd")
        names.indices.any { i -> mask and (1 shl i) != 0 && extensionPatterns[i].containsMatchIn(text) }
    }
    private val extensionPatterns = arrayOf("mp4", "m4v", "webm", "m3u8", "mpd")
        .map { Regex("\\.$it(?:$|[?#])", RegexOption.IGNORE_CASE) }

    fun directParts(host: String, path: String, query: String): Boolean? =
        if (enabled) urlPartsNative(host, path, query, 0) else null
    fun controlParts(path: String, query: String): Boolean? =
        if (enabled) urlPartsNative("", path, query, 1) else null

    /** Java relay uses <=32 trim and LF separators; Kotlin playlists use Unicode trim and CR/LF. */
    @JvmStatic fun lines(text: String, relay: Boolean): Array<String> {
        if (!enabled) return if (relay) text.split(Regex("\\r?\\n"))
            .map { it.trim { char -> char <= ' ' } }.toTypedArray()
            else text.lineSequence().map(String::trim).toList().toTypedArray()
        val spans = linesNative(text, if (relay) 1 else 0)
        return Array(spans.size / 2) { i -> text.substring(spans[i * 2], spans[i * 2] + spans[i * 2 + 1]) }
    }

    fun attributes(lines: Array<String>, key: String): Array<String?> =
        if (enabled) attributesNative(lines, key) else {
            val pattern = Regex("(?:^|,)" + Regex.escape(key) + "=(?:\"([^\"]+)\"|([^,]+))", RegexOption.IGNORE_CASE)
            Array(lines.size) { i -> pattern.find(lines[i].substringAfter(':'))?.let {
                it.groups[1]?.value ?: it.groups[2]?.value } }
        }

    fun tokens(input: String): List<String>? = if (enabled) tokensNative(input).asList() else null

    fun order(names: List<String>): IntArray? = if (enabled && names.none { name -> name.any { it.code > 127 && it.isDigit() } }) {
        val originals = names.toTypedArray()
        orderNative(originals, Array(originals.size) { originals[it].lowercase() })
    } else null

    @JvmStatic fun decimal(text: String): Long? {
        if (!enabled) return text.toLongOrNull()
        val result = longNative(text)
        return when (result[0]) { 1L -> result[1]; 2L -> text.toLongOrNull(); else -> null }
    }

    fun jpegComponents(bytes: ByteArray): Int? = if (enabled) jpegNative(bytes) else null
    fun magic(bytes: ByteArray, count: Int, kind: Int): Boolean {
        if (count < 0 || count > bytes.size) return false
        if (enabled) return magicNative(bytes, count, kind)
        fun byte(i: Int) = bytes[i].toInt() and 255
        if (kind == 6) return count >= 377 && byte(0) == 71 && byte(188) == 71 && byte(376) == 71
        if (kind == 7) return count >= 20 && byte(0) == 127 && byte(1) == 69 && byte(2) == 76 && byte(3) == 70
        if (count < 8) return false
        return when (kind) {
            1 -> byte(4) == 102 && byte(5) == 116 && byte(6) == 121 && byte(7) == 112
            2 -> byte(0) == 26 && byte(1) == 69 && byte(2) == 223 && byte(3) == 163
            3 -> byte(0) == 79 && byte(1) == 103 && byte(2) == 103 && byte(3) == 83
            4 -> byte(0) == 73 && byte(1) == 68 && byte(2) == 51 || byte(0) == 255 && byte(1) and 224 == 224
            5 -> byte(0) == 71
            else -> false
        }
    }

    fun hex(bytes: ByteArray): String = if (enabled) hexNative(bytes) else {
        val digits = "0123456789ABCDEF"
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b -> val n = b.toInt() and 255; out[2*i] = digits[n ushr 4]; out[2*i+1] = digits[n and 15] }
        String(out)
    }
    fun pdfLiteral(text: String): String = if (enabled) literalNative(text) else buildString(text.length) {
        text.forEach { if (it == '\\' || it == '(' || it == ')') append('\\'); append(it) }
    }
    fun eta(progress: Float, start: Float, elapsed: Long): Long? =
        if (enabled && progress.isFinite() && start.isFinite()) etaNative(progress, start, elapsed).takeIf { it >= 0L } else null
}
