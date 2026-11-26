package jp.hi7.sshtermproto.terminal

/**
 * Minimal wcwidth: 0 for combining / zero-width code points, 2 for East Asian
 * wide + emoji, 1 otherwise. Ambiguous-width characters (box drawing, ●, ○ ...)
 * are treated as 1 cell, which is what most Western-locale TUIs assume.
 *
 * Without this, Japanese output would desync the cursor column and break the
 * box borders of full-screen TUIs.
 */
object CharWidth {

    fun of(cp: Int): Int {
        if (cp < 0x300) return 1
        if (isZeroWidth(cp)) return 0
        return if (isWide(cp)) 2 else 1
    }

    private fun isZeroWidth(cp: Int): Boolean =
        (cp in 0x0300..0x036F) ||
            (cp in 0x1AB0..0x1AFF) ||
            (cp in 0x1DC0..0x1DFF) ||
            (cp in 0x200B..0x200F) ||
            (cp in 0x202A..0x202E) ||
            cp == 0x2060 ||
            (cp in 0x20D0..0x20FF) ||
            (cp in 0xFE00..0xFE0F) ||
            cp == 0xFEFF ||
            (cp in 0xE0100..0xE01EF)

    private fun isWide(cp: Int): Boolean =
        (cp in 0x1100..0x115F) ||     // Hangul Jamo
            (cp in 0x2E80..0x303E) || // CJK radicals, punctuation (incl. U+3000)
            (cp in 0x3041..0x33FF) || // Hiragana, Katakana, CJK symbols
            (cp in 0x3400..0x4DBF) || // CJK Ext A
            (cp in 0x4E00..0x9FFF) || // CJK Unified
            (cp in 0xA000..0xA4CF) || // Yi
            (cp in 0xAC00..0xD7A3) || // Hangul syllables
            (cp in 0xF900..0xFAFF) || // CJK compatibility ideographs
            (cp in 0xFE30..0xFE6F) || // CJK compatibility forms
            (cp in 0xFF00..0xFF60) || // Fullwidth forms
            (cp in 0xFFE0..0xFFE6) ||
            (cp in 0x1F300..0x1F64F) || // emoji
            (cp in 0x1F680..0x1F6FF) ||
            (cp in 0x1F900..0x1F9FF) ||
            (cp in 0x1FA70..0x1FAFF) ||
            (cp in 0x20000..0x3FFFD)    // CJK Ext B..
}
