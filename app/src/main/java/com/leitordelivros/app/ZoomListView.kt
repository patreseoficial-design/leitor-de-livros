package com.leitordelivros.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ListView

/** Lista que dá zoom com dois dedos (pinça) e toque duplo, usada para as páginas do PDF. */
class ZoomListView(ctx: Context, attrs: AttributeSet? = null) : ListView(ctx, attrs) {

    private var escala = 1f
    private var dx = 0f
    private var dy = 0f

    private val pinca = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nova = (escala * d.scaleFactor).coerceIn(1f, 5f)
            val f = nova / escala
            dx = d.focusX - (d.focusX - dx) * f
            dy = d.focusY - (d.focusY - dy) * f
            escala = nova
            limitar()
            invalidate()
            return true
        }
    })

    private val gestos = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (escala > 1f) {
                escala = 1f
            } else {
                escala = 2.5f
                dx = e.x - e.x * escala
                dy = e.y - e.y * escala
            }
            limitar()
            invalidate()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distX: Float, distY: Float): Boolean {
            if (escala > 1f) {
                dx -= distX
                limitar()
                invalidate()
            }
            return false
        }
    })

    private fun limitar() {
        if (escala <= 1f) {
            escala = 1f
            dx = 0f
            dy = 0f
            return
        }
        dx = dx.coerceIn(width * (1 - escala), 0f)
        dy = dy.coerceIn(height * (1 - escala), 0f)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        pinca.onTouchEvent(ev)
        gestos.onTouchEvent(ev)
        if (pinca.isInProgress || ev.pointerCount > 1) {
            // Durante a pinça, a lista não rola
            val cancelar = MotionEvent.obtain(ev)
            cancelar.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancelar)
            cancelar.recycle()
            return true
        }
        if (escala == 1f) return super.dispatchTouchEvent(ev)
        // Converte o toque da tela para as coordenadas da lista sem zoom
        val copia = MotionEvent.obtain(ev)
        val m = Matrix()
        m.setTranslate(-dx, -dy)
        m.postScale(1 / escala, 1 / escala)
        copia.transform(m)
        val r = super.dispatchTouchEvent(copia)
        copia.recycle()
        return r
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.scale(escala, escala)
        super.dispatchDraw(canvas)
        canvas.restore()
    }
}
