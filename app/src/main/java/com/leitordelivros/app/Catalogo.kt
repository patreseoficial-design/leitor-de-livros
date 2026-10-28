package com.leitordelivros.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Um livro grátis da Loja (domínio público, do Project Gutenberg). */
class ItemLoja(
    val id: Int,
    val titulo: String,
    val autor: String,
    val idioma: String,
    /** 0 = Infantil, 1 = Juvenil, 2 = Adulto */
    val faixa: Int,
    /** Índice no string-array generos_loja */
    val genero: Int,
)

/**
 * Lista de livros grátis da aba Loja. Vem pronta dentro do app (assets/catalogo.json,
 * gerada por tools/gerar_catalogo.py); o livro só é baixado quando a pessoa escolhe.
 */
object Catalogo {

    const val INFANTIL = 0
    const val JUVENIL = 1
    const val ADULTO = 2

    private var itens: List<ItemLoja>? = null

    @Synchronized
    fun itens(ctx: Context): List<ItemLoja> {
        itens?.let { return it }
        val lista = ArrayList<ItemLoja>()
        try {
            val texto = ctx.assets.open("catalogo.json").bufferedReader().use { it.readText() }
            val a = JSONObject(texto).getJSONArray("livros")
            for (i in 0 until a.length()) {
                val l: JSONArray = a.getJSONArray(i)
                lista += ItemLoja(l.getInt(0), l.getString(1), l.getString(2), l.getString(3), l.getInt(4), l.getInt(5))
            }
        } catch (e: Exception) {
        }
        itens = lista
        return lista
    }

    private fun chaveDe(ctx: Context, id: Int) = Uri.fromFile(arquivo(ctx, id)).toString()

    private fun arquivo(ctx: Context, id: Int) =
        File(File(ctx.filesDir, "livros").apply { mkdirs() }, "gutenberg-$id.epub")

    /** O livro já baixado para a biblioteca, se houver. */
    fun naBiblioteca(ctx: Context, item: ItemLoja): Livro? = Biblioteca.buscar(ctx, chaveDe(ctx, item.id))

    /**
     * Baixa o EPUB e coloca na biblioteca, com faixa etária e gênero.
     * Lento: chamar fora da thread principal.
     */
    fun baixar(ctx: Context, item: ItemLoja): Livro {
        naBiblioteca(ctx, item)?.let { return it }
        val destino = arquivo(ctx, item.id)
        val temp = File(destino.path + ".tmp")
        // Com imagens (EPUB 3), com imagens (EPUB 2) e só texto, nessa ordem
        val formatos = listOf("epub3.images", "epub.images", "epub.noimages")
        var erro: Exception? = null
        for (f in formatos) {
            try {
                baixarArquivo("https://www.gutenberg.org/ebooks/${item.id}.$f", temp)
                if (!pareceZip(temp)) throw IOException("não é EPUB")
                if (!temp.renameTo(destino)) throw IOException("não deu para salvar")
                erro = null
                break
            } catch (e: Exception) {
                erro = e
                temp.delete()
            }
        }
        erro?.let { throw it }

        val livro = Livro(
            chave = Uri.fromFile(destino).toString(),
            titulo = item.titulo,
            autor = item.autor,
            formato = "epub",
            tamanho = destino.length(),
            data = System.currentTimeMillis(),
            faixa = item.faixa,
            genero = item.genero,
        )
        synchronized(Biblioteca) {
            Biblioteca.todos(ctx)[livro.chave] = livro
            Biblioteca.salvar(ctx)
        }
        // Capa do próprio EPUB (o título fica o do catálogo, mais limpo)
        try {
            Biblioteca.lerMetadados(ctx) {}
            livro.titulo = item.titulo
            if (item.autor.isNotBlank()) livro.autor = item.autor
            Biblioteca.salvar(ctx)
        } catch (e: Exception) {
        }
        return livro
    }

    private fun conectar(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "LeitorDeLivros/1.0 (Android)")
        return c
    }

    private fun baixarArquivo(url: String, destino: File) {
        val c = conectar(url)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { e -> destino.outputStream().use { e.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (destino.length() == 0L) throw IOException("arquivo vazio")
    }

    private fun pareceZip(f: File): Boolean = try {
        f.inputStream().use { val b = ByteArray(2); it.read(b) == 2 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() }
    } catch (e: Exception) {
        false
    }

    // ---------- Capas da Loja ----------

    private val memoria = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    /** Livros sem capa no Gutenberg (não tenta de novo nesta sessão) */
    private val semCapa = HashSet<Int>()
    private val esperando = HashMap<Int, MutableList<(Bitmap) -> Unit>>()
    private val principal = Handler(Looper.getMainLooper())
    /** As capas pedidas por último (as que estão na tela) vêm primeiro */
    private val fila = object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)
    }
    private val baixadores = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, fila).apply { allowCoreThreadTimeOut(true) }

    fun capaNaMemoria(id: Int): Bitmap? = memoria.get(id)

    /** Busca a capa (do disco ou da internet) e chama [pronto] na thread principal. */
    fun capa(ctx: Context, id: Int, pronto: (Bitmap) -> Unit) {
        memoria.get(id)?.let { pronto(it); return }
        synchronized(esperando) {
            if (id in semCapa) return
            esperando[id]?.let { it += pronto; return }
            esperando[id] = mutableListOf(pronto)
        }
        val pasta = File(ctx.cacheDir, "capas_loja").apply { mkdirs() }
        baixadores.execute {
            val arq = File(pasta, "$id.jpg")
            var bmp: Bitmap? = null
            try {
                if (!arq.exists() || arq.length() == 0L) {
                    val temp = File(pasta, "$id.tmp")
                    baixarArquivo("https://www.gutenberg.org/cache/epub/$id/pg$id.cover.medium.jpg", temp)
                    temp.renameTo(arq)
                }
                bmp = decodificar(arq)
            } catch (e: Exception) {
            }
            val chamar = synchronized(esperando) {
                if (bmp == null) semCapa += id
                esperando.remove(id) ?: mutableListOf()
            }
            bmp?.let { b ->
                memoria.put(id, b)
                principal.post { chamar.forEach { it(b) } }
            }
        }
    }

    private fun decodificar(arq: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(arq.path, o)
        if (o.outWidth <= 0) {
            arq.delete()
            return null
        }
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= 200) amostra *= 2
        return BitmapFactory.decodeFile(arq.path, BitmapFactory.Options().apply { inSampleSize = amostra })
    }
}
