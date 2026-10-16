package com.leitordelivros.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View

/** Capa de livro em pé (2:3). Sem imagem, desenha um fundo colorido com o título. */
class CapaView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private companion object {
        val CORES = arrayOf(
            intArrayOf(0xFF2B4C7E.toInt(), 0xFF162544.toInt()),
            intArrayOf(0xFF7A3B2E.toInt(), 0xFF3A1A14.toInt()),
            intArrayOf(0xFF2E6B5A.toInt(), 0xFF12302A.toInt()),
            intArrayOf(0xFF6B4C8A.toInt(), 0xFF2E1F40.toInt()),
            intArrayOf(0xFF8A6A24.toInt(), 0xFF3D2E0D.toInt()),
            intArrayOf(0xFF3E5566.toInt(), 0xFF1A252E.toInt()),
        )
    }

    private var bitmap: Bitmap? = null
    private var titulo = ""
    private var autor = ""
    private val raio = resources.displayMetrics.density * 6
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pincelBorda = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x22FFFFFF
    }
    private val pincelLombada = Paint().apply { color = 0x33000000 }
    private val textoTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val textoAutor = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt() }
    private val area = RectF()
    private val matriz = Matrix()

    fun mostrar(bmp: Bitmap?, titulo: String, autor: String) {
        bitmap = bmp
        this.titulo = titulo
        this.autor = autor
        contentDescription = titulo
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec)
        else w * 3 / 2
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        area.set(0f, 0f, w, h)
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            // Corta a imagem para preencher (centerCrop) com cantos arredondados
            val escala = maxOf(w / bmp.width, h / bmp.height)
            matriz.setScale(escala, escala)
            matriz.postTranslate((w - bmp.width * escala) / 2, (h - bmp.height * escala) / 2)
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(matriz)
            pincel.shader = shader
            canvas.drawRoundRect(area, raio, raio, pincel)
        } else {
            val cores = CORES[Math.floorMod(titulo.hashCode(), CORES.size)]
            pincel.shader = LinearGradient(0f, 0f, w * 0.4f, h, cores[0], cores[1], Shader.TileMode.CLAMP)
            canvas.drawRoundRect(area, raio, raio, pincel)
            canvas.drawRect(0f, raio, w * 0.05f, h - raio, pincelLombada)
            desenharTexto(canvas, w, h)
        }
        canvas.drawRoundRect(area, raio, raio, pincelBorda)
    }

    private fun desenharTexto(canvas: Canvas, w: Float, h: Float) {
        val margem = w * 0.12f
        val largura = (w - margem * 1.6f).toInt().coerceAtLeast(1)
        textoTitulo.textSize = (w * 0.13f).coerceAtLeast(10f)
        textoAutor.textSize = (w * 0.08f).coerceAtLeast(8f)
        val l = layout(titulo, textoTitulo, largura, 5)
        canvas.save()
        canvas.translate(margem, h * 0.14f)
        l.draw(canvas)
        canvas.restore()
        if (autor.isNotBlank()) {
            val la = layout(autor, textoAutor, largura, 2)
            canvas.save()
            canvas.translate(margem, h - h * 0.08f - la.height)
            la.draw(canvas)
            canvas.restore()
        }
    }

    @Suppress("DEPRECATION")
    private fun layout(texto: String, p: TextPaint, largura: Int, linhas: Int): StaticLayout =
        if (Build.VERSION.SDK_INT >= 23) {
            StaticLayout.Builder.obtain(texto, 0, texto.length, p, largura)
                .setMaxLines(linhas).setEllipsize(TextUtils.TruncateAt.END).build()
        } else {
            StaticLayout(texto, p, largura, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)
        }
}
