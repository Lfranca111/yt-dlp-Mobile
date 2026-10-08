package com.linfranca.ytdlpmobile

object CommandTokenizer {
    private val urlPattern = Regex("https?://.*", RegexOption.IGNORE_CASE)
    private val blocked = setOf(
        "-o", "--output", "-P", "--paths", "--config-location", "--config-locations",
        "--exec", "--exec-before-download", "--cookies", "--cookies-from-browser"
    )
    private val requiresValue = setOf(
        "-f", "--format", "--proxy", "--extractor-args", "--add-headers",
        "--user-agent", "--referer", "--sub-langs", "--audio-format",
        "--audio-quality", "--merge-output-format", "--concurrent-fragments"
    )

    fun parse(input: String): List<String> {
        if (input.isBlank()) return emptyList()

        val result = NativeMedia.tokens(input) ?: tokenizeManaged(input)

        val tokens = if (result.firstOrNull()?.substringAfterLast('/') == "yt-dlp") {
            result.drop(1)
        } else result

        tokens.forEachIndexed { index, token ->
            val option = token.substringBefore('=')
            require(option !in blocked) {
                if (option.startsWith("--cookies")) {
                    "Use the app's Import cookies.txt button instead of $option."
                } else {
                    "$option is managed by the app and cannot be set manually."
                }
            }
            require(!token.matches(urlPattern)) {
                "Put the link in the link box, not in custom arguments."
            }
            if (option in requiresValue && '=' !in token) {
                val value = tokens.getOrNull(index + 1)
                require(!value.isNullOrBlank() && !value.startsWith("-")) {
                    "$option needs a value."
                }
            }
        }
        return tokens
    }

    private fun tokenizeManaged(input: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false

        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }

        input.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != null && char == quote -> quote = null
                quote == null && (char == '\'' || char == '"') -> quote = char
                quote == null && char.isWhitespace() -> flush()
                else -> current.append(char)
            }
        }

        require(quote == null) { "Custom arguments contain an unclosed quote." }
        if (escaped) current.append('\\')
        flush()

        return result
    }

}
