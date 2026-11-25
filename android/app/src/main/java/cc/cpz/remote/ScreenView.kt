package cc.cpz.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.min

/**
 * Shows the device's LCD, scaled up with crisp pixels. It is also the target
 * for the phone's soft keyboard, which (with TYPE_NULL) sends plain key events.
 */
class ScreenView(context: Context) : View(context) {
    var frame: Bitmap? = null
        set(v) {
            field = v
            invalidate()
        }

    var placeholder = "Not connected"
        set(v) {
            field = v
            invalidate()
        }

    private val pixelPaint = Paint().apply { isFilterBitmap = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textAlign = Paint.Align.CENTER
        textSize = 16f * resources.displayMetrics.density
    }
    private val dst = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return BaseInputConnection(this, false)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = frame
        if (b == null) {
            canvas.drawText(placeholder, width / 2f, height / 2f, textPaint)
            return
        }
        val scale = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        canvas.drawBitmap(b, null, dst, pixelPaint)
    }
}
