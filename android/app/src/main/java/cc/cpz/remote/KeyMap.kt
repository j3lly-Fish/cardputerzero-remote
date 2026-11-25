package cc.cpz.remote

import android.view.KeyEvent

/** Linux input key codes (linux/input-event-codes.h). */
object L {
    const val ESC = 1
    const val BACKSPACE = 14
    const val TAB = 15
    const val ENTER = 28
    const val LEFTCTRL = 29
    const val LEFTSHIFT = 42
    const val RIGHTSHIFT = 54
    const val LEFTALT = 56
    const val SPACE = 57
    const val CAPSLOCK = 58
    const val F1 = 59 // F1..F10 = 59..68
    const val F11 = 87
    const val F12 = 88
    const val RIGHTCTRL = 97
    const val SYSRQ = 99
    const val RIGHTALT = 100
    const val HOME = 102
    const val UP = 103
    const val PAGEUP = 104
    const val LEFT = 105
    const val RIGHT = 106
    const val END = 107
    const val DOWN = 108
    const val PAGEDOWN = 109
    const val INSERT = 110
    const val DELETE = 111
    const val LEFTMETA = 125
    const val RIGHTMETA = 126
}

/** A Linux key code, optionally needing Shift held (for symbols like '!'). */
data class LKey(val code: Int, val shift: Boolean = false)

object KeyMap {
    private val chars = HashMap<Char, LKey>().apply {
        fun row(start: Int, s: String) = s.forEachIndexed { i, c -> put(c, LKey(start + i)) }
        row(2, "1234567890-=")
        row(16, "qwertyuiop[]")
        row(30, "asdfghjkl;'`")
        row(44, "zxcvbnm,./")
        put('\\', LKey(43))
        put(' ', LKey(L.SPACE))
        put('\n', LKey(L.ENTER))
        put('\t', LKey(L.TAB))
        val plain = "1234567890-=[];'`,./\\"
        val shifted = "!@#$%^&*()_+{}:\"~<>?|"
        plain.forEachIndexed { i, c -> put(shifted[i], LKey(getValue(c).code, true)) }
        for (c in 'a'..'z') put(c.uppercaseChar(), LKey(getValue(c).code, true))
    }

    /** Linux key for a typed character (US layout), or null if it can't be typed. */
    fun fromChar(c: Char): LKey? = chars[c]

    private val android = HashMap<Int, LKey>().apply {
        for (i in 0 until 26) put(KeyEvent.KEYCODE_A + i, chars.getValue('a' + i))
        for (i in 0..9) put(KeyEvent.KEYCODE_0 + i, chars.getValue('0' + i))
        for (i in 0 until 10) put(KeyEvent.KEYCODE_F1 + i, LKey(L.F1 + i))
        val simple = mapOf(
            KeyEvent.KEYCODE_F11 to L.F11, KeyEvent.KEYCODE_F12 to L.F12,
            KeyEvent.KEYCODE_SPACE to L.SPACE, KeyEvent.KEYCODE_ENTER to L.ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER to L.ENTER, KeyEvent.KEYCODE_DPAD_CENTER to L.ENTER,
            KeyEvent.KEYCODE_DEL to L.BACKSPACE, KeyEvent.KEYCODE_FORWARD_DEL to L.DELETE,
            KeyEvent.KEYCODE_TAB to L.TAB, KeyEvent.KEYCODE_ESCAPE to L.ESC,
            KeyEvent.KEYCODE_GRAVE to 41, KeyEvent.KEYCODE_MINUS to 12, KeyEvent.KEYCODE_EQUALS to 13,
            KeyEvent.KEYCODE_LEFT_BRACKET to 26, KeyEvent.KEYCODE_RIGHT_BRACKET to 27,
            KeyEvent.KEYCODE_BACKSLASH to 43, KeyEvent.KEYCODE_SEMICOLON to 39,
            KeyEvent.KEYCODE_APOSTROPHE to 40, KeyEvent.KEYCODE_COMMA to 51,
            KeyEvent.KEYCODE_PERIOD to 52, KeyEvent.KEYCODE_SLASH to 53,
            KeyEvent.KEYCODE_SHIFT_LEFT to L.LEFTSHIFT, KeyEvent.KEYCODE_SHIFT_RIGHT to L.RIGHTSHIFT,
            KeyEvent.KEYCODE_CTRL_LEFT to L.LEFTCTRL, KeyEvent.KEYCODE_CTRL_RIGHT to L.RIGHTCTRL,
            KeyEvent.KEYCODE_ALT_LEFT to L.LEFTALT, KeyEvent.KEYCODE_ALT_RIGHT to L.RIGHTALT,
            KeyEvent.KEYCODE_META_LEFT to L.LEFTMETA, KeyEvent.KEYCODE_META_RIGHT to L.RIGHTMETA,
            KeyEvent.KEYCODE_CAPS_LOCK to L.CAPSLOCK, KeyEvent.KEYCODE_SYSRQ to L.SYSRQ,
            KeyEvent.KEYCODE_DPAD_UP to L.UP, KeyEvent.KEYCODE_DPAD_DOWN to L.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to L.LEFT, KeyEvent.KEYCODE_DPAD_RIGHT to L.RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME to L.HOME, KeyEvent.KEYCODE_MOVE_END to L.END,
            KeyEvent.KEYCODE_PAGE_UP to L.PAGEUP, KeyEvent.KEYCODE_PAGE_DOWN to L.PAGEDOWN,
            KeyEvent.KEYCODE_INSERT to L.INSERT,
        )
        for ((k, v) in simple) put(k, LKey(v))
        put(KeyEvent.KEYCODE_AT, chars.getValue('@'))
        put(KeyEvent.KEYCODE_POUND, chars.getValue('#'))
        put(KeyEvent.KEYCODE_STAR, chars.getValue('*'))
        put(KeyEvent.KEYCODE_PLUS, chars.getValue('+'))
    }

    /** Linux key for an Android key code (phone IME or a Bluetooth/USB keyboard). */
    fun fromAndroid(keyCode: Int): LKey? = android[keyCode]

    fun isModifier(keyCode: Int) = KeyEvent.isModifierKey(keyCode) || keyCode == KeyEvent.KEYCODE_CAPS_LOCK

    fun isShift(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
}
