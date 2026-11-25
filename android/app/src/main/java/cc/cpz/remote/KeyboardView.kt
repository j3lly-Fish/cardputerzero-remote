package cc.cpz.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

internal fun View.dp(v: Float) = v * resources.displayMetrics.density

/**
 * On-screen keyboard laid out like the Cardputer, plus a navigation row.
 * Modifiers are sticky: tap once = next key only, tap twice = locked, tap again = off.
 * Fn is a local layer: digits become F1-F12 and Backspace becomes Delete.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val send: (code: Int, down: Boolean) -> Unit,
    private val onImeKey: () -> Unit,
) : View(context) {

    enum class Mod(val code: Int) { SHIFT(L.LEFTSHIFT), CTRL(L.LEFTCTRL), ALT(L.LEFTALT), OPT(L.LEFTMETA), FN(0) }

    class Key(
        val label: String,
        val code: Int = 0,
        val shiftLabel: String? = null,
        val fnLabel: String? = null,
        val fnCode: Int = 0,
        val mod: Mod? = null,
        val ime: Boolean = false,
        val width: Float = 1f,
    ) {
        val rect = RectF()
    }

    private val rows: List<List<Key>> = buildRows()
    private val keys = rows.flatten()
    private val modState = IntArray(Mod.entries.size) // 0 off, 1 once, 2 locked
    private val down = SparseArray<Pair<Key, Int>>() // pointer id -> (key, code sent)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun buildRows(): List<List<Key>> {
        fun chars(plain: String, shifted: String) = plain.mapIndexed { i, c ->
            Key(c.toString(), KeyMap.fromChar(c)!!.code, shifted[i].toString())
        }
        val row1 = "`1234567890-=".mapIndexed { i, c ->
            val fn = when (i) {
                in 1..10 -> "F$i" to L.F1 + i - 1
                11 -> "F11" to L.F11
                12 -> "F12" to L.F12
                else -> null
            }
            Key(c.toString(), KeyMap.fromChar(c)!!.code, "~!@#$%^&*()_+"[i].toString(), fn?.first, fn?.second ?: 0)
        } + Key("⌫", L.BACKSPACE, fnLabel = "Del", fnCode = L.DELETE)
        val row2 = listOf(Key("Tab", L.TAB)) + chars("qwertyuiop[]\\", "QWERTYUIOP{}|")
        val row3 = listOf(Key("Fn", mod = Mod.FN), Key("⇧", mod = Mod.SHIFT)) +
            chars("asdfghjkl;'", "ASDFGHJKL:\"") + Key("Enter", L.ENTER)
        val row4 = listOf(Key("Ctrl", mod = Mod.CTRL), Key("Opt", mod = Mod.OPT), Key("Alt", mod = Mod.ALT)) +
            chars("zxcvbnm,./", "ZXCVBNM<>?") + Key("Space", L.SPACE)
        val w = 1.4f
        val row5 = listOf(
            Key("Esc", L.ESC, width = w), Key("⌨", ime = true, width = w),
            Key("Home", L.HOME, width = w), Key("End", L.END, width = w),
            Key("PgUp", L.PAGEUP, width = w), Key("PgDn", L.PAGEDOWN, width = w),
            Key("←", L.LEFT, width = w), Key("↑", L.UP, width = w),
            Key("↓", L.DOWN, width = w), Key("→", L.RIGHT, width = w),
        )
        return listOf(row1, row2, row3, row4, row5)
    }

    private fun isOn(m: Mod) = modState[m.ordinal] != 0

    /** Forget modifier state without sending anything (after a disconnect). */
    fun reset() {
        modState.fill(0)
        down.clear()
        invalidate()
    }

    private fun toggle(m: Mod) {
        val s = modState[m.ordinal]
        val ns = (s + 1) % 3
        if (m.code != 0) {
            if (s == 0) send(m.code, true)
            if (ns == 0) send(m.code, false)
        }
        modState[m.ordinal] = ns
    }

    private fun releaseOnceMods() {
        for (m in Mod.entries) {
            if (modState[m.ordinal] == 1) {
                if (m.code != 0) send(m.code, false)
                modState[m.ordinal] = 0
            }
        }
    }

    private fun press(pointer: Int, key: Key) {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when {
            key.mod != null -> toggle(key.mod)
            key.ime -> onImeKey()
            else -> {
                val code = if (isOn(Mod.FN) && key.fnCode != 0) key.fnCode else key.code
                send(code, true)
                down.put(pointer, key to code)
            }
        }
        invalidate()
    }

    private fun release(pointer: Int) {
        val (_, code) = down[pointer] ?: return
        down.remove(pointer)
        send(code, false)
        if (down.size() == 0) releaseOnceMods()
        invalidate()
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val slop = dp(2f)
        return keys.firstOrNull {
            x >= it.rect.left - slop && x <= it.rect.right + slop &&
                y >= it.rect.top - slop && y <= it.rect.bottom + slop
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                keyAt(e.getX(i), e.getY(i))?.let { press(e.getPointerId(i), it) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> release(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> (0 until down.size()).map { down.keyAt(it) }.forEach(::release)
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val want = min(dp(50f) * rows.size, resources.displayMetrics.heightPixels * 0.45f).toInt()
        setMeasuredDimension(w, resolveSize(want, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = dp(3f)
        val rowH = (h - gap * (rows.size + 1)) / rows.size
        rows.forEachIndexed { r, row ->
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            val unitW = (w - gap * (row.size + 1)) / units
            var x = gap
            val y = gap + r * (rowH + gap)
            for (k in row) {
                val kw = unitW * k.width
                k.rect.set(x, y, x + kw, y + rowH)
                x += kw + gap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BG)
        val pressed = HashSet<Key>()
        for (i in 0 until down.size()) pressed.add(down.valueAt(i).first)
        val shift = isOn(Mod.SHIFT)
        val fn = isOn(Mod.FN)
        val radius = dp(5f)
        for (k in keys) {
            keyPaint.color = when {
                k in pressed -> PRESSED
                k.mod != null && modState[k.mod.ordinal] == 1 -> MOD_ONCE
                k.mod != null && modState[k.mod.ordinal] == 2 -> MOD_LOCKED
                k.mod != null || k.ime || k.label.length > 1 -> SPECIAL
                else -> KEY
            }
            canvas.drawRoundRect(k.rect, radius, radius, keyPaint)
            val label = when {
                fn && k.fnLabel != null -> k.fnLabel
                shift && k.shiftLabel != null -> k.shiftLabel
                else -> k.label
            }
            textPaint.textSize = if (label.length == 1) {
                min(k.rect.height() * 0.45f, k.rect.width() * 0.7f)
            } else {
                min(k.rect.height() * 0.32f, k.rect.width() * 1.4f / label.length)
            }
            val baseline = k.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, k.rect.centerX(), baseline, textPaint)
        }
    }

    private companion object {
        val BG = Color.rgb(0x12, 0x14, 0x17)
        val KEY = Color.rgb(0x2C, 0x31, 0x38)
        val SPECIAL = Color.rgb(0x22, 0x26, 0x2C)
        val PRESSED = Color.rgb(0x2F, 0x80, 0xED)
        val MOD_ONCE = Color.rgb(0x2B, 0x5C, 0x9E)
        val MOD_LOCKED = Color.rgb(0xD9, 0x8E, 0x1F)
    }
}
