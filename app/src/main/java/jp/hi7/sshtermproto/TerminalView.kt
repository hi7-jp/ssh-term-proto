package jp.hi7.sshtermproto

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import jp.hi7.sshtermproto.terminal.CharWidth
import jp.hi7.sshtermproto.terminal.TerminalAttr
import jp.hi7.sshtermproto.terminal.TerminalColors
import jp.hi7.sshtermproto.terminal.TerminalEmulator
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Character-grid terminal view: draws a [TerminalEmulator]'s buffer with a
 * monospace font and turns key / IME input into bytes for the SSH session.
 *
 * ## Input design (chosen: dedicated key-input View, no EditText)
 * The old "one-line EditText + Send button" is gone. This view itself is the
 * text editor as far as the IME is concerned ([onCheckIsTextEditor] +
 * [onCreateInputConnection]):
 *  - Soft keyboard characters arrive via InputConnection.commitText and are
 *    forwarded immediately, one commit at a time (no Send button, no line
 *    buffer, so Tab completion / arrows / Ctrl+C all reach the remote shell).
 *  - Soft keyboard special keys (Enter, Backspace) and every hardware key
 *    arrive as KeyEvents and go through [handleKey].
 *  - Keys a phone keyboard lacks (Esc, Tab, Ctrl, arrows ...) are provided by
 *    the extra-key row in MainActivity, which calls [sendText] / [sendKey].
 *
 * Integration with the existing UI: [MainActivity] puts this view where the
 * ScrollView+TextView log was, wires [onInput] to the SSH output stream and
 * [onResize] to the PTY size notification. The connect form stays as it was
 * and is hidden by MainActivity while a session is active.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val DEFAULT_COLS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_TEXT_SP = 13f
        private const val BLINK_MS = 530L
    }

    val emulator = TerminalEmulator(DEFAULT_COLS, DEFAULT_ROWS)

    /** Bytes the user typed / the terminal replied; set by the activity. */
    var onInput: ((ByteArray) -> Unit)? = null

    /** Called when the character grid size changes (rotation, IME shown...). */
    var onResize: ((cols: Int, rows: Int, widthPx: Int, heightPx: Int) -> Unit)? = null

    /** Called when the one-shot Ctrl modifier is armed / consumed. */
    var onCtrlStateChanged: ((Boolean) -> Unit)? = null

    val cols: Int get() = emulator.buffer.cols
    val rows: Int get() = emulator.buffer.rows

    /** One-shot Ctrl for soft keyboards: applies to the next typed character. */
    var ctrlArmed: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                onCtrlStateChanged?.invoke(value)
            }
        }

    private val normalTypeface: Typeface = Typeface.MONOSPACE
    private val boldTypeface: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()
    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val charBuf = CharArray(2)

    private var textSizeSp = DEFAULT_TEXT_SP
    private var cellW = 1f
    private var cellH = 1f
    private var baseline = 0f
    private var lineThickness = 1f

    private var scrollOffset = 0   // lines scrolled back into history (0 = live)
    private var scrollAccum = 0f
    private var blinkOn = true
    private var composing = ""     // IME pre-edit text, drawn but not yet sent

    private val blinkRunnable = object : Runnable {
        override fun run() {
            blinkOn = !blinkOn
            invalidate()
            postDelayed(this, BLINK_MS)
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                requestFocus()
                showKeyboard()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                // Drag down = look further back in history.
                scrollAccum += distanceY
                val lines = (scrollAccum / cellH).toInt()
                if (lines != 0) {
                    scrollAccum -= lines * cellH
                    scrollHistory(-lines)
                }
                return true
            }
        }
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        applyTextSize()
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Feeds remote output (already decoded text) into the emulator. */
    fun feed(text: String) {
        emulator.feed(text)
        scrollOffset = min(scrollOffset, emulator.buffer.scrollbackSize)
        invalidate()
    }

    /** [clearScrollback] = true also drops the history (use for a brand-new session). */
    fun reset(clearScrollback: Boolean = false) {
        emulator.reset(clearScrollback)
        scrollOffset = 0
        composing = ""
        invalidate()
    }

    fun setTextSizeSp(sp: Float) {
        textSizeSp = sp
        applyTextSize()
        if (width > 0 && height > 0) updateGridSize(width, height)
        invalidate()
    }

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, 0)
    }

    /** Sends [text] as UTF-8, honouring the one-shot Ctrl modifier. */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        var t = text
        if (ctrlArmed) {
            ctrlArmed = false
            val code = ctrlCode(t[0])
            if (code >= 0) {
                t = code.toChar().toString() + t.substring(1)
            }
        }
        emit(t.toByteArray(Charsets.UTF_8))
    }

    /** Sends the escape sequence for a non-printing key (arrows, Home, F-keys ...). */
    fun sendKey(keyCode: Int) {
        val seq = keySequence(keyCode, shift = false, alt = false, ctrl = false)
        if (seq != null) sendText(seq)
    }

    /** Pastes the clipboard, wrapped for bracketed paste when the app asked for it. */
    fun paste() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        if (text.isEmpty()) return
        var body = text.replace("\r\n", "\n").replace('\n', '\r')
        val bytes = if (emulator.bracketedPaste) {
            // Strip ESC so pasted text can't smuggle in its own end-of-paste marker.
            body = body.replace("\u001b", "")
            "\u001b[200~$body\u001b[201~"
        } else {
            body
        }
        emit(bytes.toByteArray(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------
    // Layout / grid size
    // ------------------------------------------------------------------

    private fun applyTextSize() {
        textPaint.typeface = normalTypeface
        textPaint.textSize =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics)
        val fm = textPaint.fontMetrics
        cellW = max(1f, textPaint.measureText("M"))
        cellH = max(1f, ceil(fm.descent - fm.ascent))
        baseline = -fm.ascent
        lineThickness = max(1f, resources.displayMetrics.density)
        strokePaint.strokeWidth = lineThickness
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGridSize(w, h)
    }

    private fun updateGridSize(w: Int, h: Int) {
        val usableW = w - paddingLeft - paddingRight
        val usableH = h - paddingTop - paddingBottom
        val newCols = max(2, (usableW / cellW).toInt())
        val newRows = max(1, (usableH / cellH).toInt())
        if (newCols != cols || newRows != rows) {
            emulator.resize(newCols, newRows)
            scrollOffset = min(scrollOffset, emulator.buffer.scrollbackSize)
            onResize?.invoke(newCols, newRows, w, h)
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        blinkOn = true
        postDelayed(blinkRunnable, BLINK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private fun resetBlink() {
        blinkOn = true
        removeCallbacks(blinkRunnable)
        postDelayed(blinkRunnable, BLINK_MS)
    }

    override fun onDraw(canvas: Canvas) {
        val buf = emulator.buffer
        val defBg = TerminalColors.DEFAULT_BG
        canvas.drawColor(defBg)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val focused = hasFocus()
        val showCursor = emulator.cursorVisible && scrollOffset == 0
        var lastStyle = -1
        textPaint.typeface = normalTypeface
        textPaint.textSkewX = 0f

        for (r in 0 until buf.rows) {
            val row = buf.getRow(r - scrollOffset) ?: continue
            val y = top + r * cellH
            val n = min(buf.cols, row.size)
            for (c in 0 until n) {
                val attrs = row.attrs[c]
                if ((attrs and TerminalAttr.WIDE_TAIL) != 0) continue // painted with its head

                val wide = c + 1 < n && (row.attrs[c + 1] and TerminalAttr.WIDE_TAIL) != 0
                val cw = if (wide) cellW * 2 else cellW
                val x = left + c * cellW

                var fgC = TerminalColors.resolve(row.fg[c], TerminalColors.DEFAULT_FG)
                var bgC = TerminalColors.resolve(row.bg[c], defBg)
                if ((attrs and TerminalAttr.INVERSE) != 0) {
                    val t = fgC
                    fgC = bgC
                    bgC = t
                }

                val isCursor = showCursor && r == buf.cursorY && c == buf.cursorX
                if (isCursor && focused && blinkOn) {
                    // Block cursor = colours swapped relative to what is displayed.
                    val t = fgC
                    fgC = bgC
                    bgC = t
                }

                if (bgC != defBg) {
                    fillPaint.color = bgC
                    canvas.drawRect(x, y, x + cw, y + cellH, fillPaint)
                }

                val cp = row.chars[c]
                if (cp != 0x20 && (attrs and TerminalAttr.INVISIBLE) == 0) {
                    if ((attrs and TerminalAttr.DIM) != 0) {
                        fgC = (fgC and 0x00FFFFFF) or (0x99 shl 24)
                    }
                    val style = attrs and (TerminalAttr.BOLD or TerminalAttr.ITALIC)
                    if (style != lastStyle) {
                        textPaint.typeface =
                            if ((style and TerminalAttr.BOLD) != 0) boldTypeface else normalTypeface
                        textPaint.textSkewX = if ((style and TerminalAttr.ITALIC) != 0) -0.25f else 0f
                        lastStyle = style
                    }
                    textPaint.color = fgC
                    val len = Character.toChars(cp, charBuf, 0)
                    canvas.drawText(charBuf, 0, len, x, y + baseline, textPaint)
                }

                if ((attrs and TerminalAttr.UNDERLINE) != 0) {
                    fillPaint.color = fgC
                    canvas.drawRect(x, y + cellH - lineThickness, x + cw, y + cellH, fillPaint)
                }
                if ((attrs and TerminalAttr.STRIKE) != 0) {
                    fillPaint.color = fgC
                    val sy = y + cellH / 2
                    canvas.drawRect(x, sy, x + cw, sy + lineThickness, fillPaint)
                }

                if (isCursor && !focused) {
                    // Hollow box when the view doesn't have input focus.
                    strokePaint.color = TerminalColors.DEFAULT_FG
                    canvas.drawRect(x + 1f, y + 1f, x + cw - 1f, y + cellH - 1f, strokePaint)
                }
            }
        }

        drawComposing(canvas, left, top)
    }

    /** Draws IME pre-edit text (e.g. unconverted kana) at the cursor, underlined. */
    private fun drawComposing(canvas: Canvas, left: Float, top: Float) {
        if (composing.isEmpty() || scrollOffset != 0) return
        val buf = emulator.buffer
        var cx = buf.cursorX
        val y = top + buf.cursorY * cellH
        textPaint.typeface = normalTypeface
        textPaint.textSkewX = 0f
        var idx = 0
        while (idx < composing.length) {
            val cp = composing.codePointAt(idx)
            idx += Character.charCount(cp)
            val w = CharWidth.of(cp)
            if (w == 0) continue
            if (cx + w > buf.cols) break
            val x = left + cx * cellW
            fillPaint.color = 0xFF3A3A3A.toInt()
            canvas.drawRect(x, y, x + w * cellW, y + cellH, fillPaint)
            textPaint.color = TerminalColors.DEFAULT_FG
            val len = Character.toChars(cp, charBuf, 0)
            canvas.drawText(charBuf, 0, len, x, y + baseline, textPaint)
            fillPaint.color = TerminalColors.DEFAULT_FG
            canvas.drawRect(x, y + cellH - lineThickness, x + w * cellW, y + cellH, fillPaint)
            cx += w
        }
    }

    // ------------------------------------------------------------------
    // Touch: tap = focus + keyboard, drag = scrollback
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun scrollHistory(deltaLines: Int) {
        val next = (scrollOffset + deltaLines).coerceIn(0, emulator.buffer.scrollbackSize)
        if (next != scrollOffset) {
            scrollOffset = next
            invalidate()
        }
    }

    // ------------------------------------------------------------------
    // Keyboard / IME input
    // ------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // VISIBLE_PASSWORD + NO_SUGGESTIONS asks the IME for a plain key-by-key
        // keyboard (no autocorrect strip, no word composing), so each character
        // is committed -- and therefore sent -- the moment it is typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKey(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    /** Returns true when the key was turned into terminal input. */
    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val shift = event.isShiftPressed
        val alt = event.isAltPressed
        val ctrl = event.isCtrlPressed

        val special = keySequence(keyCode, shift, alt, ctrl)
        if (special != null) {
            sendText(special)
            return true
        }

        // Printable key: ask the key map for the character *without* Ctrl/Alt
        // applied, then apply them ourselves the way a terminal expects.
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val uc = event.getUnicodeChar(meta)
        if (uc <= 0) return false // modifier keys, Back, volume, dead keys ...

        var s = String(Character.toChars(uc))
        if (ctrl && uc < 0x10000) {
            val code = ctrlCode(uc.toChar())
            if (code >= 0) s = code.toChar().toString()
        }
        if (alt) s = "\u001b$s" // Alt = Meta = ESC prefix
        sendText(s)
        return true
    }

    /** xterm-style sequences for non-printing keys, or null if not one of them. */
    private fun keySequence(keyCode: Int, shift: Boolean, alt: Boolean, ctrl: Boolean): String? {
        val mods = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val app = emulator.appCursorKeys
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (alt) "\u001b\r" else "\r" // Alt+Enter = ESC CR (newline in many TUIs)
            KeyEvent.KEYCODE_DEL -> if (alt) "\u001b\u007f" else "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> tildeSeq(3, mods)
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001b[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001b"
            KeyEvent.KEYCODE_DPAD_UP -> cursorSeq('A', mods, app)
            KeyEvent.KEYCODE_DPAD_DOWN -> cursorSeq('B', mods, app)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cursorSeq('C', mods, app)
            KeyEvent.KEYCODE_DPAD_LEFT -> cursorSeq('D', mods, app)
            KeyEvent.KEYCODE_MOVE_HOME -> cursorSeq('H', mods, app)
            KeyEvent.KEYCODE_MOVE_END -> cursorSeq('F', mods, app)
            KeyEvent.KEYCODE_PAGE_UP -> tildeSeq(5, mods)
            KeyEvent.KEYCODE_PAGE_DOWN -> tildeSeq(6, mods)
            KeyEvent.KEYCODE_INSERT -> tildeSeq(2, mods)
            KeyEvent.KEYCODE_F1 -> ss3Seq('P', mods)
            KeyEvent.KEYCODE_F2 -> ss3Seq('Q', mods)
            KeyEvent.KEYCODE_F3 -> ss3Seq('R', mods)
            KeyEvent.KEYCODE_F4 -> ss3Seq('S', mods)
            KeyEvent.KEYCODE_F5 -> tildeSeq(15, mods)
            KeyEvent.KEYCODE_F6 -> tildeSeq(17, mods)
            KeyEvent.KEYCODE_F7 -> tildeSeq(18, mods)
            KeyEvent.KEYCODE_F8 -> tildeSeq(19, mods)
            KeyEvent.KEYCODE_F9 -> tildeSeq(20, mods)
            KeyEvent.KEYCODE_F10 -> tildeSeq(21, mods)
            KeyEvent.KEYCODE_F11 -> tildeSeq(23, mods)
            KeyEvent.KEYCODE_F12 -> tildeSeq(24, mods)
            else -> null
        }
    }

    private fun cursorSeq(ch: Char, mods: Int, app: Boolean): String = when {
        mods > 1 -> "\u001b[1;$mods$ch"
        app -> "\u001bO$ch"
        else -> "\u001b[$ch"
    }

    private fun tildeSeq(n: Int, mods: Int): String =
        if (mods > 1) "\u001b[$n;$mods~" else "\u001b[$n~"

    private fun ss3Seq(ch: Char, mods: Int): String =
        if (mods > 1) "\u001b[1;$mods$ch" else "\u001bO$ch"

    /** Control-code for Ctrl+[c], or -1 when Ctrl has no meaning for it. */
    private fun ctrlCode(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a' + 1
        in 'A'..'Z' -> c - 'A' + 1
        '@', ' ' -> 0
        '[' -> 27
        '\\' -> 28
        ']' -> 29
        '^' -> 30
        '_', '/' -> 31
        '?' -> 127
        else -> -1
    }

    private fun emit(bytes: ByteArray) {
        if (scrollOffset != 0) {
            scrollOffset = 0 // typing jumps back to the live screen
        }
        resetBlink()
        invalidate()
        onInput?.invoke(bytes)
    }

    /**
     * Bridges the soft keyboard. Only the methods an IME really uses are
     * implemented; there is no backing Editable because the "text" lives on the
     * remote side.
     */
    private inner class TerminalInputConnection(view: View) : BaseInputConnection(view, false) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = ""
            if (!text.isNullOrEmpty()) {
                sendText(text.toString().replace('\n', '\r'))
            }
            invalidate()
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // Pre-edit (e.g. kana before conversion) is shown but not sent yet.
            composing = text?.toString() ?: ""
            invalidate()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                val t = composing
                composing = ""
                sendText(t)
                invalidate()
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            if (composing.isNotEmpty()) {
                composing = composing.dropLast(beforeLength)
                invalidate()
            } else {
                repeat(beforeLength) { sendText("\u007f") }
            }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // Only a DOWN that became terminal input counts as handled, so keys
            // we don't use (volume, camera ...) aren't claimed. Don't call
            // super: it would re-dispatch to onKeyDown and send the key twice.
            if (event.action == KeyEvent.ACTION_DOWN) {
                return handleKey(event.keyCode, event)
            }
            return false
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendText("\r")
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? = ""
        override fun getSelectedText(flags: Int): CharSequence? = null
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
    }
}
