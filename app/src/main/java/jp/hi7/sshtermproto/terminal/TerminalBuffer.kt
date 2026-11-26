package jp.hi7.sshtermproto.terminal

/** Per-cell attribute bits stored in [TerminalRow.attrs]. */
object TerminalAttr {
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
    const val INVERSE = 16
    const val STRIKE = 32
    const val INVISIBLE = 64

    /** Marks the right half of a double-width character (never drawn itself). */
    const val WIDE_TAIL = 128
}

/**
 * One row of cells. Parallel IntArrays instead of a Cell object per column,
 * so scrolling a 80x40 screen allocates nothing.
 */
class TerminalRow(cols: Int) {
    var chars: IntArray = IntArray(cols) { SPACE }   // code points
    var fg: IntArray = IntArray(cols)                // see TerminalColors
    var bg: IntArray = IntArray(cols)
    var attrs: IntArray = IntArray(cols)             // TerminalAttr bits

    val size: Int get() = chars.size

    fun set(i: Int, cp: Int, fgColor: Int, bgColor: Int, attr: Int) {
        chars[i] = cp
        fg[i] = fgColor
        bg[i] = bgColor
        attrs[i] = attr
    }

    /** Blanks [from, to) using [bgColor] (background-colour-erase, like xterm). */
    fun clear(from: Int, to: Int, bgColor: Int) {
        val s = if (from < 0) 0 else from
        val e = if (to > size) size else to
        for (i in s until e) {
            chars[i] = SPACE
            fg[i] = 0
            bg[i] = bgColor
            attrs[i] = 0
        }
    }

    fun resize(newCols: Int) {
        val old = size
        if (newCols == old) return
        chars = chars.copyOf(newCols)
        fg = fg.copyOf(newCols)
        bg = bg.copyOf(newCols)
        attrs = attrs.copyOf(newCols)
        for (i in old until newCols) {
            chars[i] = SPACE
        }
    }

    /** ICH: shift cells right from [at], filling the gap with blanks. */
    fun insertCells(at: Int, n: Int, bgColor: Int) {
        val count = minOf(n, size - at)
        if (count <= 0) return
        for (arr in arrayOf(chars, fg, bg, attrs)) {
            System.arraycopy(arr, at, arr, at + count, size - at - count)
        }
        clear(at, at + count, bgColor)
    }

    /** DCH: shift cells left over [at], blanking the tail. */
    fun deleteCells(at: Int, n: Int, bgColor: Int) {
        val count = minOf(n, size - at)
        if (count <= 0) return
        for (arr in arrayOf(chars, fg, bg, attrs)) {
            System.arraycopy(arr, at + count, arr, at, size - at - count)
        }
        clear(size - count, size, bgColor)
    }

    companion object {
        const val SPACE = 0x20
    }
}

/**
 * A rows x cols character grid with cursor, scroll region and (optionally)
 * scrollback. The emulator owns two of these: the normal screen (with
 * scrollback) and the alternate screen (without).
 *
 * Not thread-safe: all access is expected on the UI thread.
 */
class TerminalBuffer(initCols: Int, initRows: Int, private val scrollbackLimit: Int) {

    var cols: Int = initCols
        private set
    var rows: Int = initRows
        private set

    private var lines: Array<TerminalRow> = Array(initRows) { TerminalRow(initCols) }
    private val scrollback = ArrayDeque<TerminalRow>()

    var cursorX: Int = 0
    var cursorY: Int = 0

    /** Inclusive scroll region rows (DECSTBM). */
    var scrollTop: Int = 0
        private set
    var scrollBottom: Int = initRows - 1
        private set

    /** True after writing in the last column: the wrap happens on the next char. */
    var wrapPending: Boolean = false

    val scrollbackSize: Int get() = scrollback.size

    /**
     * Row at [index]: 0 until rows is the live screen, negative reaches into
     * scrollback (-1 = most recent scrolled-off line). Null when out of range.
     * Scrollback rows may be a different width than [cols] after a resize.
     */
    fun getRow(index: Int): TerminalRow? {
        if (index >= 0) {
            return if (index < rows) lines[index] else null
        }
        val i = scrollback.size + index
        return if (i >= 0) scrollback[i] else null
    }

    fun moveCursor(x: Int, y: Int) {
        cursorX = x.coerceIn(0, cols - 1)
        cursorY = y.coerceIn(0, rows - 1)
        wrapPending = false
    }

    fun setScrollRegion(top: Int, bottom: Int) {
        scrollTop = top.coerceIn(0, rows - 1)
        scrollBottom = bottom.coerceIn(scrollTop, rows - 1)
    }

    fun resetScrollRegion() {
        scrollTop = 0
        scrollBottom = rows - 1
    }

    /** Writes one code point at the cursor, handling deferred wrap and wide chars. */
    fun putChar(cp: Int, width: Int, fg: Int, bg: Int, attrs: Int, autoWrap: Boolean, insert: Boolean) {
        var w = if (width == 2 && cols >= 2) 2 else 1
        if (wrapPending) {
            if (autoWrap) {
                cursorX = 0
                lineFeed(bg)
            }
            wrapPending = false
        }
        if (w == 2 && cursorX == cols - 1) {
            if (autoWrap) {
                // Not enough room for both halves: blank the last cell, wrap.
                lines[cursorY].clear(cursorX, cursorX + 1, bg)
                cursorX = 0
                lineFeed(bg)
            } else {
                w = 1
            }
        }
        val row = lines[cursorY]
        if (insert) {
            row.insertCells(cursorX, w, bg)
        }
        // Overwriting one half of an existing wide char would leave an orphan half.
        if (cursorX > 0 && (row.attrs[cursorX] and TerminalAttr.WIDE_TAIL) != 0) {
            row.clear(cursorX - 1, cursorX, bg)
        }
        if (w == 1 && cursorX + 1 < cols && (row.attrs[cursorX + 1] and TerminalAttr.WIDE_TAIL) != 0) {
            row.clear(cursorX + 1, cursorX + 2, bg)
        }
        row.set(cursorX, cp, fg, bg, attrs)
        if (w == 2) {
            row.set(cursorX + 1, TerminalRow.SPACE, fg, bg, attrs or TerminalAttr.WIDE_TAIL)
        }
        cursorX += w
        if (cursorX >= cols) {
            cursorX = cols - 1
            wrapPending = true
        }
    }

    /** IND / LF: move down, scrolling the region when at its bottom. */
    fun lineFeed(bg: Int) {
        wrapPending = false
        if (cursorY == scrollBottom) {
            scrollUp(1, bg)
        } else if (cursorY < rows - 1) {
            cursorY++
        }
    }

    /** RI: move up, scrolling the region down when at its top. */
    fun reverseLineFeed(bg: Int) {
        wrapPending = false
        if (cursorY == scrollTop) {
            scrollDown(1, bg)
        } else if (cursorY > 0) {
            cursorY--
        }
    }

    fun scrollUp(n: Int, bg: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        // Only whole-screen scrolling feeds scrollback; a TUI's inner region
        // (e.g. a status bar split) shouldn't pollute it.
        val toScrollback = scrollbackLimit > 0 && scrollTop == 0 && scrollBottom == rows - 1
        repeat(count) {
            val top = lines[scrollTop]
            for (i in scrollTop until scrollBottom) {
                lines[i] = lines[i + 1]
            }
            if (toScrollback) {
                scrollback.addLast(top)
                if (scrollback.size > scrollbackLimit) {
                    scrollback.removeFirst()
                }
                val fresh = TerminalRow(cols)
                fresh.clear(0, cols, bg)
                lines[scrollBottom] = fresh
            } else {
                top.clear(0, cols, bg)
                lines[scrollBottom] = top
            }
        }
    }

    fun scrollDown(n: Int, bg: Int) {
        val count = minOf(n, scrollBottom - scrollTop + 1)
        repeat(count) {
            val bottom = lines[scrollBottom]
            for (i in scrollBottom downTo scrollTop + 1) {
                lines[i] = lines[i - 1]
            }
            bottom.clear(0, cols, bg)
            lines[scrollTop] = bottom
        }
    }

    /** IL */
    fun insertLines(n: Int, bg: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val count = minOf(n, scrollBottom - cursorY + 1)
        repeat(count) {
            val bottom = lines[scrollBottom]
            for (i in scrollBottom downTo cursorY + 1) {
                lines[i] = lines[i - 1]
            }
            bottom.clear(0, cols, bg)
            lines[cursorY] = bottom
        }
        cursorX = 0
        wrapPending = false
    }

    /** DL */
    fun deleteLines(n: Int, bg: Int) {
        if (cursorY < scrollTop || cursorY > scrollBottom) return
        val count = minOf(n, scrollBottom - cursorY + 1)
        repeat(count) {
            val removed = lines[cursorY]
            for (i in cursorY until scrollBottom) {
                lines[i] = lines[i + 1]
            }
            removed.clear(0, cols, bg)
            lines[scrollBottom] = removed
        }
        cursorX = 0
        wrapPending = false
    }

    fun insertChars(n: Int, bg: Int) {
        lines[cursorY].insertCells(cursorX, n, bg)
        wrapPending = false
    }

    fun deleteChars(n: Int, bg: Int) {
        lines[cursorY].deleteCells(cursorX, n, bg)
        wrapPending = false
    }

    fun eraseChars(n: Int, bg: Int) {
        lines[cursorY].clear(cursorX, minOf(cols, cursorX + n), bg)
    }

    /** EL: 0 = cursor to end, 1 = start to cursor, 2 = whole line. */
    fun eraseInLine(mode: Int, bg: Int) {
        val row = lines[cursorY]
        when (mode) {
            0 -> row.clear(cursorX, cols, bg)
            1 -> row.clear(0, cursorX + 1, bg)
            2 -> row.clear(0, cols, bg)
        }
    }

    /** ED: 0 = cursor to end, 1 = start to cursor, 2 = all, 3 = all + scrollback. */
    fun eraseInDisplay(mode: Int, bg: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0, bg)
                for (y in cursorY + 1 until rows) lines[y].clear(0, cols, bg)
            }
            1 -> {
                for (y in 0 until cursorY) lines[y].clear(0, cols, bg)
                eraseInLine(1, bg)
            }
            2 -> clearAll(bg)
            3 -> {
                clearAll(bg)
                scrollback.clear()
            }
        }
    }

    fun clearAll(bg: Int) {
        for (row in lines) row.clear(0, cols, bg)
    }

    /** RIS for this buffer. Scrollback is intentionally kept. */
    fun fullReset() {
        lines = Array(rows) { TerminalRow(cols) }
        cursorX = 0
        cursorY = 0
        wrapPending = false
        resetScrollRegion()
    }

    /**
     * Resizes the grid without reflow (the remote app redraws on SIGWINCH).
     * Shrinking pushes the top lines into scrollback so the cursor line stays
     * visible; growing pulls them back, so showing/hiding the soft keyboard
     * round-trips cleanly.
     */
    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        val list = lines.toMutableList()
        var cy = cursorY
        if (newRows < rows) {
            // Drop lines below the cursor first, then scroll the top away.
            val removeBottom = minOf(rows - newRows, rows - 1 - cursorY)
            repeat(removeBottom) { list.removeAt(list.size - 1) }
            val pushTop = rows - newRows - removeBottom
            repeat(pushTop) {
                val r = list.removeAt(0)
                if (scrollbackLimit > 0) {
                    scrollback.addLast(r)
                    if (scrollback.size > scrollbackLimit) {
                        scrollback.removeFirst()
                    }
                }
                cy--
            }
        } else if (newRows > rows) {
            val grow = newRows - rows
            val pull = minOf(grow, scrollback.size)
            repeat(pull) {
                list.add(0, scrollback.removeLast())
                cy++
            }
            repeat(grow - pull) { list.add(TerminalRow(cols)) }
        }
        for (r in list) r.resize(newCols)
        lines = list.toTypedArray()
        cols = newCols
        rows = newRows
        cursorY = cy.coerceIn(0, rows - 1)
        cursorX = cursorX.coerceIn(0, cols - 1)
        wrapPending = false
        resetScrollRegion()
    }
}
