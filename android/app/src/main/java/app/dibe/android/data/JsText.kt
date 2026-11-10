package app.dibe.android.data

/**
 * JavaScript's whitespace (`String.trim`, regex `\s`), which differs from Kotlin's: it includes
 * U+FEFF and excludes U+001C..U+001F. Ports of desktop code use it so they cut text identically.
 */
internal const val JS_SPACES =
    "\t\n\u000B\u000C\r                  　﻿"

/** `\s` for a Kotlin regex. */
internal const val JS_SPACE = "[$JS_SPACES]"

/** `\S` for a Kotlin regex. */
internal const val JS_NON_SPACE = "[^$JS_SPACES]"

internal fun String.jsTrim(): String = trim { it in JS_SPACES }
