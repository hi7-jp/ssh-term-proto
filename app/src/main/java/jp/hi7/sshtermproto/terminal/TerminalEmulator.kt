package jp.hi7.sshtermproto.terminal

/**
 * A small, from-scratch VT100 / xterm-subset parser that drives two
 * [TerminalBuffer]s (normal + alternate screen).
 *
 * Written independently (no GPL terminal-emulator code) so the repository
 * can stay Apache-2.0.
 *
 * Input is already-decoded text (the caller decodes UTF-8), fed through
 * [feed]. Anything unrecognised is skipped without throwing; a bad sequence
 * can never wedge the parser because ESC / CAN / SUB always resync it.
 *
 * Replies the remote may be waiting for (cursor position report, device
 * attributes) are delivered through [responder].
 *
 * Not thread-safe: call everything from the UI thread.
 */
class TerminalEmulator(initialCols: Int, initialRows: Int) {

    companion object {
        private const val SCROLLBACK_LINES = 2000
        private const val MAX_PARAMS = 32
        private const val MAX_PARAM_VALUE = 99999

        // DEC Special Graphics, code points 0x60..0x7E.
        private const val DEC_GRAPHICS = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·"
    }

    /** Receives bytes-as-text the terminal wants to send back to the host. */
    var responder: ((String) -> Unit)? = null

    private val mainBuffer = TerminalBuffer(initialCols, initialRows, SCROLLBACK_LINES)
    private val altBuffer = TerminalBuffer(initialCols, initialRows, 0)

    /** The buffer currently shown (main or alternate screen). */
    var buffer: TerminalBuffer = mainBuffer
        private set

    val isAltScreen: Boolean get() = buffer === altBuffer

    // --- modes the view / key encoder needs to know about ---
    var cursorVisible: Boolean = true
        private set
    var appCursorKeys: Boolean = false
        private set
    var bracketedPaste: Boolean = false
        private set
    private var autoWrap = true
    private var insertMode = false

    // --- current rendition (SGR) ---
    private var curFg = TerminalColors.DEFAULT
    private var curBg = TerminalColors.DEFAULT
    private var curAttrs = 0

    // --- character sets (only DEC line drawing vs. ASCII) ---
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false

    private class SavedCursor(
        val x: Int,
        val y: Int,
        val fg: Int,
        val bg: Int,
        val attrs: Int,
        val g0: Boolean,
        val g1: Boolean,
        val shiftOut: Boolean
    )

    private var savedCursor: SavedCursor? = null      // DECSC / DECRC
    private var savedCursor1049: SavedCursor? = null  // ?1049h / ?1049l

    // --- parser state ---
    private enum class State { GROUND, ESCAPE, ESC_INTERMEDIATE, CSI, OSC, STRING }

    private var state = State.GROUND
    private val params = IntArray(MAX_PARAMS)
    private val colonAfter = BooleanArray(MAX_PARAMS)
    private var paramCount = 0
    private var curParam = -1
    private var csiPrefix = 0
    private var csiIntermediate = 0
    private var escIntermediate = 0
    private var pendingHigh = 0 // high surrogate waiting for its low half

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    fun feed(text: CharSequence) {
        for (i in 0 until text.length) {
            val ch = text[i]
            try {
                if (pendingHigh != 0) {
                    if (Character.isLowSurrogate(ch)) {
                        val cp = Character.toCodePoint(pendingHigh.toChar(), ch)
                        pendingHigh = 0
                        process(cp)
                        continue
                    }
                    pendingHigh = 0 // lone high surrogate: drop it, handle ch normally
                }
                if (Character.isHighSurrogate(ch)) {
                    pendingHigh = ch.code
                    continue
                }
                if (Character.isLowSurrogate(ch)) continue // lone low surrogate
                process(ch.code)
            } catch (e: Exception) {
                // Never let a malformed sequence escape; resync to ground.
                state = State.GROUND
                pendingHigh = 0
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        mainBuffer.resize(cols, rows)
        altBuffer.resize(cols, rows)
    }

    /**
     * RIS: full reset. The normal screen's scrollback is kept unless
     * [clearScrollback] is set (a remote `ESC c` keeps it; a new session
     * clears it).
     */
    fun reset(clearScrollback: Boolean = false) {
        state = State.GROUND
        pendingHigh = 0
        buffer = mainBuffer
        mainBuffer.fullReset(clearScrollback)
        altBuffer.fullReset(clearScrollback)
        resetAttrs()
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        cursorVisible = true
        appCursorKeys = false
        bracketedPaste = false
        autoWrap = true
        insertMode = false
        savedCursor = null
        savedCursor1049 = null
    }

    // ------------------------------------------------------------------
    // Parser core
    // ------------------------------------------------------------------

    private fun process(cp: Int) {
        // ESC and CAN/SUB act in every state, so a corrupt sequence can't stick.
        if (cp == 0x1B) {
            state = State.ESCAPE // also terminates OSC / string (ESC \ = ST)
            escIntermediate = 0
            return
        }
        if (cp == 0x18 || cp == 0x1A) {
            state = State.GROUND
            return
        }
        if (state == State.OSC || state == State.STRING) {
            if (cp == 0x07) state = State.GROUND // BEL terminates OSC
            return // OSC / DCS / APC payloads are ignored
        }
        if (cp < 0x20) {
            control(cp) // C0 controls execute even in the middle of a CSI
            return
        }
        when (state) {
            State.GROUND -> ground(cp)
            State.ESCAPE -> escape(cp)
            State.ESC_INTERMEDIATE -> escIntermediateState(cp)
            State.CSI -> csi(cp)
            else -> state = State.GROUND
        }
    }

    private fun control(cp: Int) {
        val b = buffer
        when (cp) {
            0x08 -> { // BS
                b.cursorX = maxOf(0, b.cursorX - 1)
                b.wrapPending = false
            }
            0x09 -> tabForward() // HT
            0x0A, 0x0B, 0x0C -> b.lineFeed(curBg) // LF / VT / FF
            0x0D -> { // CR
                b.cursorX = 0
                b.wrapPending = false
            }
            0x0E -> shiftOut = true  // SO
            0x0F -> shiftOut = false // SI
            else -> { /* BEL and the rest: ignored on purpose */ }
        }
    }

    private fun ground(cp: Int) {
        if (cp == 0x7F || cp in 0x80..0x9F) return
        var c = cp
        val graphics = if (shiftOut) g1Graphics else g0Graphics
        if (graphics && c in 0x60..0x7E) {
            c = DEC_GRAPHICS[c - 0x60].code
        }
        val w = CharWidth.of(c)
        if (w == 0) return // combining marks are dropped (no grapheme clusters)
        buffer.putChar(c, w, curFg, curBg, curAttrs, autoWrap, insertMode)
    }

    private fun escape(cp: Int) {
        when (cp) {
            '['.code -> {
                state = State.CSI
                paramCount = 0
                curParam = -1
                csiPrefix = 0
                csiIntermediate = 0
            }
            ']'.code -> state = State.OSC
            'P'.code, 'X'.code, '^'.code, '_'.code -> state = State.STRING
            in 0x20..0x2F -> {
                escIntermediate = cp
                state = State.ESC_INTERMEDIATE
            }
            else -> {
                state = State.GROUND
                escDispatch(cp)
            }
        }
    }

    private fun escIntermediateState(cp: Int) {
        if (cp in 0x20..0x2F) {
            escIntermediate = cp
            return
        }
        state = State.GROUND
        when (escIntermediate) {
            '('.code -> g0Graphics = (cp == '0'.code)
            ')'.code -> g1Graphics = (cp == '0'.code)
            else -> { /* #8 (DECALN), %G etc.: ignored */ }
        }
    }

    private fun escDispatch(cp: Int) {
        val b = buffer
        when (cp) {
            '7'.code -> savedCursor = captureCursor()
            '8'.code -> restoreSavedCursor()
            'D'.code -> b.lineFeed(curBg)
            'E'.code -> {
                b.cursorX = 0
                b.lineFeed(curBg)
            }
            'M'.code -> b.reverseLineFeed(curBg)
            'c'.code -> reset()
            else -> { /* '=', '>', 'H', '\\' ...: ignored */ }
        }
    }

    private fun csi(cp: Int) {
        when {
            cp in 0x30..0x39 -> {
                val base = if (curParam < 0) 0 else curParam
                curParam = minOf(base * 10 + (cp - 0x30), MAX_PARAM_VALUE)
            }
            cp == ';'.code -> pushParam(false)
            cp == ':'.code -> pushParam(true)
            cp in 0x3C..0x3F -> { // < = > ?  private-mode prefix
                if (paramCount == 0 && curParam < 0 && csiPrefix == 0) {
                    csiPrefix = cp
                }
            }
            cp in 0x20..0x2F -> csiIntermediate = cp
            cp in 0x40..0x7E -> {
                state = State.GROUND
                if (curParam >= 0 || paramCount > 0) pushParam(false)
                dispatchCsi(cp)
            }
            else -> state = State.GROUND // 0x7F / non-ASCII inside CSI: abort
        }
    }

    private fun pushParam(colon: Boolean) {
        if (paramCount < MAX_PARAMS) {
            params[paramCount] = curParam
            colonAfter[paramCount] = colon
            paramCount++
        }
        curParam = -1
    }

    /** Parameter [i], or [def] when missing / empty / 0. */
    private fun p(i: Int, def: Int): Int =
        if (i < paramCount && params[i] > 0) params[i] else def

    // ------------------------------------------------------------------
    // CSI dispatch
    // ------------------------------------------------------------------

    private fun dispatchCsi(fin: Int) {
        if (csiPrefix != 0) {
            dispatchPrivateCsi(fin)
            return
        }
        if (csiIntermediate != 0) {
            if (csiIntermediate == '!'.code && fin == 'p'.code) softReset()
            return // DECSCUSR (SP q), DECRQM ($ p) etc.: ignored
        }
        val b = buffer
        when (fin) {
            'A'.code -> cursorUp(p(0, 1))
            'B'.code, 'e'.code -> cursorDown(p(0, 1))
            'C'.code, 'a'.code -> b.moveCursor(b.cursorX + p(0, 1), b.cursorY)
            'D'.code -> b.moveCursor(b.cursorX - p(0, 1), b.cursorY)
            'E'.code -> {
                cursorDown(p(0, 1))
                b.moveCursor(0, b.cursorY)
            }
            'F'.code -> {
                cursorUp(p(0, 1))
                b.moveCursor(0, b.cursorY)
            }
            'G'.code, '`'.code -> b.moveCursor(p(0, 1) - 1, b.cursorY)
            'd'.code -> b.moveCursor(b.cursorX, p(0, 1) - 1)
            'H'.code, 'f'.code -> b.moveCursor(p(1, 1) - 1, p(0, 1) - 1)
            'I'.code -> repeat(minOf(p(0, 1), b.cols)) { tabForward() }
            'Z'.code -> repeat(minOf(p(0, 1), b.cols)) { tabBackward() }
            'J'.code -> b.eraseInDisplay(p(0, 0), curBg)
            'K'.code -> b.eraseInLine(p(0, 0), curBg)
            'L'.code -> b.insertLines(p(0, 1), curBg)
            'M'.code -> b.deleteLines(p(0, 1), curBg)
            'P'.code -> b.deleteChars(p(0, 1), curBg)
            '@'.code -> b.insertChars(p(0, 1), curBg)
            'X'.code -> b.eraseChars(p(0, 1), curBg)
            'S'.code -> b.scrollUp(p(0, 1), curBg)
            'T'.code -> {
                if (paramCount <= 1) b.scrollDown(p(0, 1), curBg)
            }
            'm'.code -> sgr()
            'r'.code -> setScrollRegion()
            's'.code -> {
                if (paramCount == 0) savedCursor = captureCursor()
            }
            'u'.code -> restoreSavedCursor()
            'n'.code -> deviceStatusReport()
            'c'.code -> {
                if (p(0, 0) == 0) respond("\u001b[?1;2c") // DA1: "VT100 with AVO"
            }
            'h'.code -> {
                if (p(0, 0) == 4) insertMode = true
            }
            'l'.code -> {
                if (p(0, 0) == 4) insertMode = false
            }
            else -> { /* unknown: ignored */ }
        }
    }

    private fun dispatchPrivateCsi(fin: Int) {
        when (csiPrefix) {
            '?'.code -> when (fin) {
                'h'.code -> for (i in 0 until paramCount) setPrivateMode(params[i], true)
                'l'.code -> for (i in 0 until paramCount) setPrivateMode(params[i], false)
                else -> { /* ?u (kitty kbd), ?$p (DECRQM) ...: ignored */ }
            }
            '>'.code -> {
                // CSI > 4;2 m (modifyOtherKeys) and friends are ignored on
                // purpose: they must NOT be mistaken for SGR.
                if (fin == 'c'.code) respond("\u001b[>0;0;0c") // DA2
            }
            else -> { /* '<' and '=' prefixes: ignored */ }
        }
    }

    private fun setPrivateMode(mode: Int, on: Boolean) {
        when (mode) {
            1 -> appCursorKeys = on
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchScreen(on, false)
            1049 -> switchScreen(on, true)
            2004 -> bracketedPaste = on
            else -> { /* mouse modes, focus events, sync output ...: ignored */ }
        }
    }

    private fun switchScreen(alt: Boolean, saveRestoreCursor: Boolean) {
        if (alt) {
            if (buffer === altBuffer) return
            if (saveRestoreCursor) savedCursor1049 = captureCursor()
            altBuffer.resetScrollRegion()
            altBuffer.moveCursor(mainBuffer.cursorX, mainBuffer.cursorY)
            if (saveRestoreCursor) altBuffer.clearAll(TerminalColors.DEFAULT)
            buffer = altBuffer
        } else {
            if (buffer !== altBuffer) return
            buffer = mainBuffer
            if (saveRestoreCursor) {
                savedCursor1049?.let { applyCursor(it) }
            }
        }
    }

    private fun cursorUp(n: Int) {
        val b = buffer
        val top = if (b.cursorY >= b.scrollTop) b.scrollTop else 0
        b.moveCursor(b.cursorX, maxOf(top, b.cursorY - n))
    }

    private fun cursorDown(n: Int) {
        val b = buffer
        val bottom = if (b.cursorY <= b.scrollBottom) b.scrollBottom else b.rows - 1
        b.moveCursor(b.cursorX, minOf(bottom, b.cursorY + n))
    }

    private fun tabForward() {
        val b = buffer
        var x = b.cursorX + 1
        while (x % 8 != 0) x++
        b.moveCursor(minOf(x, b.cols - 1), b.cursorY)
    }

    private fun tabBackward() {
        val b = buffer
        var x = b.cursorX - 1
        while (x > 0 && x % 8 != 0) x--
        b.moveCursor(maxOf(0, x), b.cursorY)
    }

    private fun setScrollRegion() {
        val b = buffer
        val top = p(0, 1) - 1
        val bottom = minOf(p(1, b.rows), b.rows) - 1
        if (top < bottom) {
            b.setScrollRegion(top, bottom)
            b.moveCursor(0, 0)
        }
    }

    private fun deviceStatusReport() {
        when (p(0, 0)) {
            5 -> respond("\u001b[0n")
            6 -> respond("\u001b[${buffer.cursorY + 1};${buffer.cursorX + 1}R")
        }
    }

    private fun respond(s: String) {
        responder?.invoke(s)
    }

    // ------------------------------------------------------------------
    // Cursor save / restore, soft reset
    // ------------------------------------------------------------------

    private fun captureCursor(): SavedCursor =
        SavedCursor(buffer.cursorX, buffer.cursorY, curFg, curBg, curAttrs, g0Graphics, g1Graphics, shiftOut)

    private fun applyCursor(s: SavedCursor) {
        buffer.moveCursor(s.x, s.y)
        curFg = s.fg
        curBg = s.bg
        curAttrs = s.attrs
        g0Graphics = s.g0
        g1Graphics = s.g1
        shiftOut = s.shiftOut
    }

    private fun restoreSavedCursor() {
        val s = savedCursor
        if (s != null) {
            applyCursor(s)
        } else {
            buffer.moveCursor(0, 0)
        }
    }

    private fun softReset() {
        resetAttrs()
        cursorVisible = true
        appCursorKeys = false
        autoWrap = true
        insertMode = false
        g0Graphics = false
        g1Graphics = false
        shiftOut = false
        savedCursor = null
        buffer.resetScrollRegion()
    }

    // ------------------------------------------------------------------
    // SGR
    // ------------------------------------------------------------------

    private fun resetAttrs() {
        curFg = TerminalColors.DEFAULT
        curBg = TerminalColors.DEFAULT
        curAttrs = 0
    }

    private fun setColor(foreground: Boolean, color: Int) {
        if (foreground) curFg = color else curBg = color
    }

    private fun sgr() {
        if (paramCount == 0) {
            resetAttrs()
            return
        }
        var i = 0
        while (i < paramCount) {
            // A run of params joined by ':' is one sub-parameter group (i..j).
            var j = i
            while (j < paramCount - 1 && colonAfter[j]) j++
            if (j > i) {
                sgrColonGroup(i, j)
                i = j + 1
                continue
            }

            val code = if (params[i] < 0) 0 else params[i]
            when (code) {
                0 -> resetAttrs()
                1 -> curAttrs = curAttrs or TerminalAttr.BOLD
                2 -> curAttrs = curAttrs or TerminalAttr.DIM
                3 -> curAttrs = curAttrs or TerminalAttr.ITALIC
                4, 21 -> curAttrs = curAttrs or TerminalAttr.UNDERLINE
                7 -> curAttrs = curAttrs or TerminalAttr.INVERSE
                8 -> curAttrs = curAttrs or TerminalAttr.INVISIBLE
                9 -> curAttrs = curAttrs or TerminalAttr.STRIKE
                22 -> curAttrs = curAttrs and (TerminalAttr.BOLD or TerminalAttr.DIM).inv()
                23 -> curAttrs = curAttrs and TerminalAttr.ITALIC.inv()
                24 -> curAttrs = curAttrs and TerminalAttr.UNDERLINE.inv()
                27 -> curAttrs = curAttrs and TerminalAttr.INVERSE.inv()
                28 -> curAttrs = curAttrs and TerminalAttr.INVISIBLE.inv()
                29 -> curAttrs = curAttrs and TerminalAttr.STRIKE.inv()
                in 30..37 -> curFg = TerminalColors.palette(code - 30)
                39 -> curFg = TerminalColors.DEFAULT
                in 40..47 -> curBg = TerminalColors.palette(code - 40)
                49 -> curBg = TerminalColors.DEFAULT
                in 90..97 -> curFg = TerminalColors.palette(code - 90 + 8)
                in 100..107 -> curBg = TerminalColors.palette(code - 100 + 8)
                38, 48 -> {
                    // Semicolon form: 38;5;n  or  38;2;r;g;b
                    val mode = if (i + 1 < paramCount) params[i + 1] else -1
                    if (mode == 5 && i + 2 < paramCount) {
                        setColor(code == 38, TerminalColors.palette(params[i + 2].coerceIn(0, 255)))
                        i += 2
                    } else if (mode == 2 && i + 4 < paramCount) {
                        setColor(
                            code == 38,
                            TerminalColors.rgb(
                                params[i + 2].coerceIn(0, 255),
                                params[i + 3].coerceIn(0, 255),
                                params[i + 4].coerceIn(0, 255)
                            )
                        )
                        i += 4
                    } else {
                        i = paramCount // malformed: swallow the rest
                    }
                }
                else -> { /* blink (5/6), fonts, ...: ignored */ }
            }
            i++
        }
    }

    /** Colon sub-parameters: 38:5:n, 38:2:[cs]:r:g:b, 4:n (underline style). */
    private fun sgrColonGroup(i: Int, j: Int) {
        val code = params[i]
        when (code) {
            38, 48 -> {
                val mode = params[i + 1]
                if (mode == 5 && j >= i + 2) {
                    setColor(code == 38, TerminalColors.palette(params[i + 2].coerceIn(0, 255)))
                } else if (mode == 2) {
                    // 38:2:r:g:b (4 values after) or 38:2:<colourspace>:r:g:b (5 values)
                    val start = if (j - i >= 5) i + 3 else i + 2
                    if (j >= start + 2) {
                        setColor(
                            code == 38,
                            TerminalColors.rgb(
                                params[start].coerceIn(0, 255),
                                params[start + 1].coerceIn(0, 255),
                                params[start + 2].coerceIn(0, 255)
                            )
                        )
                    }
                }
            }
            4 -> {
                curAttrs = if (params[i + 1] == 0) {
                    curAttrs and TerminalAttr.UNDERLINE.inv()
                } else {
                    curAttrs or TerminalAttr.UNDERLINE
                }
            }
            else -> { /* ignored */ }
        }
    }
}
