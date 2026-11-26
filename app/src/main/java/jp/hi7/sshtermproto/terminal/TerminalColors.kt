package jp.hi7.sshtermproto.terminal

/**
 * Colour handling for the terminal grid.
 *
 * Each cell stores its colour as a single Int so a row needs no per-cell
 * objects:
 *   0                  -> "default" (resolved to the theme fg/bg at draw time)
 *   0x01_000000 | idx  -> palette index 0..255
 *   0xFF_RRGGBB        -> 24-bit colour (already ARGB, usable as-is)
 */
object TerminalColors {
    const val DEFAULT = 0
    private const val PALETTE_TAG = 0x01

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or rgb

    // Default text is light grey (not the old all-green log look): TUIs such
    // as Claude Code / Codex rely on the default fg being distinguishable
    // from the ANSI green they use for highlights.
    val DEFAULT_FG: Int = opaque(0xE5E5E5)
    val DEFAULT_BG: Int = opaque(0x000000)

    // ANSI 0..7 (normal) followed by 8..15 (bright).
    private val ANSI16 = intArrayOf(
        0x000000, 0xCD3131, 0x0DBC79, 0xE5E510,
        0x2472C8, 0xBC3FBC, 0x11A8CD, 0xE5E5E5,
        0x666666, 0xF14C4C, 0x23D18B, 0xF5F543,
        0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF
    )

    private val palette256: IntArray = buildPalette()

    private fun buildPalette(): IntArray {
        val p = IntArray(256)
        for (i in 0 until 16) {
            p[i] = opaque(ANSI16[i])
        }
        // 6x6x6 colour cube (16..231)
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            val r = levels[i / 36]
            val g = levels[(i / 6) % 6]
            val b = levels[i % 6]
            p[16 + i] = opaque((r shl 16) or (g shl 8) or b)
        }
        // 24-step grey ramp (232..255)
        for (i in 0 until 24) {
            val v = 8 + i * 10
            p[232 + i] = opaque((v shl 16) or (v shl 8) or v)
        }
        return p
    }

    fun palette(index: Int): Int = (PALETTE_TAG shl 24) or (index and 0xFF)

    fun rgb(r: Int, g: Int, b: Int): Int =
        opaque(((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF))

    /** Turns a stored cell colour into an ARGB value for Paint. */
    fun resolve(color: Int, default: Int): Int {
        if (color == DEFAULT) return default
        if ((color ushr 24) == PALETTE_TAG) return palette256[color and 0xFF]
        return color
    }
}
