package com.leitordelivros.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ImageView
import java.util.concurrent.Executors

/** Mostra as páginas de um PDF numa lista com zoom. As páginas são desenhadas aos poucos. */
class VisorPdf(
    private val tela: Activity,
    descritor: ParcelFileDescriptor,
    private val aoMudarPagina: (atual: Int, total: Int) -> Unit,
) {
    private val renderizador = PdfRenderer(descritor)
    private val fila = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val proporcoes = FloatArray(renderizador.pageCount) { 1.414f }
    private val espaco = tela.dp(8)
    @Volatile private var fechado = false

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    val total get() = proporcoes.size

    val lista = ZoomListView(tela).apply {
        divider = null
        dividerHeight = 0
        setBackgroundColor(0xFFE9EBF0.toInt())
        isVerticalScrollBarEnabled = true
        isFastScrollEnabled = true
    }

    private val adaptador = object : BaseAdapter() {
        override fun getCount() = proporcoes.size
        override fun getItem(posicao: Int) = posicao
        override fun getItemId(posicao: Int) = posicao.toLong()

        override fun getView(posicao: Int, convertView: View?, parent: ViewGroup): View {
            val img = (convertView as? ImageView) ?: ImageView(tela).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(espaco, espaco, espaco, 0)
            }
            val largura = (parent.width - 2 * espaco).coerceAtLeast(1)
            img.layoutParams = AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (largura * proporcoes[posicao]).toInt() + espaco
            )
            img.tag = posicao
            val pronto = cache.get(posicao)
            if (pronto != null) {
                img.setImageBitmap(pronto)
                img.setBackgroundColor(Color.TRANSPARENT)
            } else {
                img.setImageDrawable(null)
                desenhar(posicao, largura) { bmp ->
                    if (img.tag == posicao) img.setImageBitmap(bmp)
                }
            }
            return img
        }
    }

    init {
        // Mede todas as páginas primeiro (para a lista saber a altura de cada uma)
        fila.execute {
            synchronized(renderizador) {
                for (i in 0 until renderizador.pageCount) {
                    if (fechado) return@execute
                    renderizador.openPage(i).use { proporcoes[i] = it.height.toFloat() / it.width }
                }
            }
            principal.post { if (!fechado) adaptador.notifyDataSetChanged() }
        }
        lista.adapter = adaptador
        lista.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {}
            override fun onScroll(view: AbsListView, primeiro: Int, visiveis: Int, total: Int) {
                // A página "atual" é a que ocupa o meio da tela
                val meio = view.height / 2
                var atual = primeiro
                for (i in 0 until visiveis) {
                    val filho = view.getChildAt(i) ?: continue
                    if (filho.top <= meio && filho.bottom >= meio) atual = primeiro + i
                }
                aoMudarPagina(atual + 1, proporcoes.size)
            }
        })
    }

    private val pedidos = HashSet<Int>()

    private fun desenhar(pagina: Int, largura: Int, pronto: (Bitmap) -> Unit) {
        if (!pedidos.add(pagina)) {
            // Já está na fila: quando terminar, a lista é redesenhada
            return
        }
        fila.execute {
            val bmp = try {
                synchronized(renderizador) {
                    if (fechado) null else renderizador.openPage(pagina).use { p ->
                        // Um pouco maior que a tela, para o zoom não ficar borrado
                        val w = (largura * 1.5f).toInt().coerceAtMost(2200)
                        val h = (w * p.height.toFloat() / p.width).toInt()
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            } catch (e: Throwable) {
                null
            }
            principal.post {
                pedidos.remove(pagina)
                if (bmp != null && !fechado) {
                    cache.put(pagina, bmp)
                    pronto(bmp)
                    adaptador.notifyDataSetChanged()
                }
            }
        }
    }

    fun irPara(pagina: Int) {
        lista.setSelection((pagina - 1).coerceIn(0, proporcoes.size - 1))
    }

    fun fechar() {
        fechado = true
        fila.execute {
            synchronized(renderizador) {
                try { renderizador.close() } catch (e: Exception) { }
            }
        }
        fila.shutdown()
        cache.evictAll()
    }
}
