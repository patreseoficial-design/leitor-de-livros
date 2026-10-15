package com.leitordelivros.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class Livro(
    val chave: String,
    var titulo: String,
    var autor: String = "",
    val formato: String,
    var tamanho: Long = 0L,
    var data: Long = 0L,
    var capa: String = "",
    var progresso: Float = 0f,
    var capitulo: Int = 0,
    var posicao: Float = 0f,
    var favorito: Boolean = false,
    var paraLer: Boolean = false,
    var lido: Boolean = false,
    var ultimaLeitura: Long = 0L,
    var metaLida: Boolean = false,
) {
    val uri: Uri get() = Uri.parse(chave)

    fun json(): JSONObject = JSONObject()
        .put("chave", chave).put("titulo", titulo).put("autor", autor).put("formato", formato)
        .put("tamanho", tamanho).put("data", data).put("capa", capa).put("progresso", progresso.toDouble())
        .put("capitulo", capitulo).put("posicao", posicao.toDouble()).put("favorito", favorito)
        .put("paraLer", paraLer).put("lido", lido).put("ultimaLeitura", ultimaLeitura).put("metaLida", metaLida)

    companion object {
        val FORMATOS = listOf("epub", "fb2", "pdf", "txt")

        fun formatoDe(nome: String): String? =
            nome.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it in FORMATOS }

        fun deJson(o: JSONObject) = Livro(
            chave = o.getString("chave"),
            titulo = o.optString("titulo"),
            autor = o.optString("autor"),
            formato = o.optString("formato", "epub"),
            tamanho = o.optLong("tamanho"),
            data = o.optLong("data"),
            capa = o.optString("capa"),
            progresso = o.optDouble("progresso", 0.0).toFloat(),
            capitulo = o.optInt("capitulo"),
            posicao = o.optDouble("posicao", 0.0).toFloat(),
            favorito = o.optBoolean("favorito"),
            paraLer = o.optBoolean("paraLer"),
            lido = o.optBoolean("lido"),
            ultimaLeitura = o.optLong("ultimaLeitura"),
            metaLida = o.optBoolean("metaLida"),
        )
    }
}

/** Todos os livros conhecidos, guardados num arquivo JSON dentro do app. */
object Biblioteca {

    private var mapa: LinkedHashMap<String, Livro>? = null

    private fun arquivo(ctx: Context) = File(ctx.filesDir, "biblioteca.json")

    @Synchronized
    fun todos(ctx: Context): LinkedHashMap<String, Livro> {
        mapa?.let { return it }
        val m = LinkedHashMap<String, Livro>()
        try {
            val f = arquivo(ctx)
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) Livro.deJson(a.getJSONObject(i)).let { m[it.chave] = it }
            }
        } catch (e: Exception) {
        }
        mapa = m
        return m
    }

    fun buscar(ctx: Context, chave: String): Livro? = todos(ctx)[chave]

    @Synchronized
    fun salvar(ctx: Context) {
        val a = JSONArray()
        todos(ctx).values.forEach { a.put(it.json()) }
        val temp = File(ctx.filesDir, "biblioteca.json.tmp")
        temp.writeText(a.toString())
        if (!temp.renameTo(arquivo(ctx))) {
            arquivo(ctx).writeText(a.toString())
            temp.delete()
        }
    }

    private fun ocultos(ctx: Context): MutableSet<String> =
        ctx.getSharedPreferences("biblioteca", Context.MODE_PRIVATE).getStringSet("ocultos", emptySet())!!.toMutableSet()

    @Synchronized
    fun remover(ctx: Context, livro: Livro) {
        todos(ctx).remove(livro.chave)
        // Não volta na próxima busca do armazenamento
        val o = ocultos(ctx).apply { add(livro.chave) }
        ctx.getSharedPreferences("biblioteca", Context.MODE_PRIVATE).edit().putStringSet("ocultos", o).apply()
        if (livro.capa.isNotEmpty()) File(livro.capa).delete()
        salvar(ctx)
    }

    /** Coloca (ou acha) um livro aberto por outro app ou pelo seletor de arquivos. */
    @Synchronized
    fun registrar(ctx: Context, uri: Uri, mime: String?): Livro {
        todos(ctx)[uri.toString()]?.let { return it }
        val o = ocultos(ctx)
        if (o.remove(uri.toString())) {
            ctx.getSharedPreferences("biblioteca", Context.MODE_PRIVATE).edit().putStringSet("ocultos", o).apply()
        }
        var nome = uri.lastPathSegment?.substringAfterLast('/') ?: "livro"
        var tamanho = 0L
        if (uri.scheme == "file") {
            val f = File(uri.path ?: "")
            nome = f.name
            tamanho = f.length()
        } else try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val iNome = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val iTam = c.getColumnIndex(OpenableColumns.SIZE)
                    if (iNome >= 0 && !c.isNull(iNome)) nome = c.getString(iNome)
                    if (iTam >= 0 && !c.isNull(iTam)) tamanho = c.getLong(iTam)
                }
            }
        } catch (e: Exception) {
        }
        val formato = Livro.formatoDe(nome) ?: when {
            mime == null -> "txt"
            mime.contains("epub") -> "epub"
            mime.contains("fictionbook") -> "fb2"
            mime.contains("pdf") -> "pdf"
            else -> "txt"
        }
        val livro = Livro(uri.toString(), nome.substringBeforeLast('.'), formato = formato, tamanho = tamanho,
            data = System.currentTimeMillis())
        todos(ctx)[livro.chave] = livro
        salvar(ctx)
        return livro
    }

    /** Procura livros no armazenamento e acrescenta os novos (não mexe no progresso dos que já existem). */
    fun procurar(ctx: Context) {
        val achados = HashMap<String, File>()
        fun andar(pasta: File, profundidade: Int) {
            if (profundidade > 14) return
            for (f in pasta.listFiles() ?: return) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (profundidade == 0 && f.name == "Android") continue
                    andar(f, profundidade + 1)
                } else {
                    val formato = Livro.formatoDe(f.name) ?: continue
                    if (formato == "txt" || f.length() == 0L) continue // .txt só pelo "Abrir livro"
                    achados[Uri.fromFile(f).toString()] = f
                }
            }
        }
        andar(Environment.getExternalStorageDirectory(), 0)
        val escondidos = ocultos(ctx)
        synchronized(this) {
            val m = todos(ctx)
            // Tira os arquivos que sumiram do celular
            m.values.filter { it.chave.startsWith("file:") && it.chave !in achados && !File(it.uri.path ?: "").exists() }
                .forEach { m.remove(it.chave) }
            for ((chave, f) in achados) {
                if (chave in m || chave in escondidos) continue
                m[chave] = Livro(chave, f.nameWithoutExtension, formato = Livro.formatoDe(f.name)!!,
                    tamanho = f.length(), data = f.lastModified())
            }
            salvar(ctx)
        }
    }

    /** Lê título, autor e capa dos livros que ainda não foram lidos. Chama [aoAtualizar] de vez em quando. */
    fun lerMetadados(ctx: Context, aoAtualizar: () -> Unit) {
        val pendentes = synchronized(this) { todos(ctx).values.filter { !it.metaLida } }
        var n = 0
        for (livro in pendentes) {
            try {
                val arq = arquivoLocal(ctx, livro.uri, livro.formato)
                when (livro.formato) {
                    "epub" -> Epub.metadados(arq)?.let { (titulo, autor, capa) ->
                        if (titulo.isNotBlank()) livro.titulo = titulo
                        livro.autor = autor
                        if (capa != null) livro.capa = salvarCapa(ctx, livro, capa)
                    }
                    "fb2" -> Fb2.metadados(arq)?.let { (titulo, autor, capa) ->
                        if (titulo.isNotBlank()) livro.titulo = titulo
                        livro.autor = autor
                        if (capa != null) livro.capa = salvarCapa(ctx, livro, capa)
                    }
                    "pdf" -> capaPdf(ctx, livro, arq)
                }
            } catch (e: Throwable) {
            }
            livro.metaLida = true
            if (++n % 5 == 0) {
                salvar(ctx)
                aoAtualizar()
            }
        }
        if (n > 0) {
            salvar(ctx)
            aoAtualizar()
        }
    }

    private fun arquivoCapa(ctx: Context, livro: Livro): File {
        val pasta = File(ctx.filesDir, "capas").apply { mkdirs() }
        return File(pasta, Integer.toHexString(livro.chave.hashCode()) + ".jpg")
    }

    private fun salvarCapa(ctx: Context, livro: Livro, bytes: ByteArray): String {
        val opcoes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opcoes)
        var amostra = 1
        while (opcoes.outWidth / (amostra * 2) >= 240) amostra *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra })
            ?: return ""
        val destino = arquivoCapa(ctx, livro)
        destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return destino.path
    }

    private fun capaPdf(ctx: Context, livro: Livro, arq: File) {
        ParcelFileDescriptor.open(arq, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (r.pageCount == 0) return
                r.openPage(0).use { p ->
                    val w = 240
                    val h = (w * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val destino = arquivoCapa(ctx, livro)
                    destino.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    bmp.recycle()
                    livro.capa = destino.path
                }
            }
        }
    }

    /** Arquivo local do livro (os que vêm de outros apps são copiados para o cache uma vez). */
    fun arquivoLocal(ctx: Context, uri: Uri, formato: String): File {
        if (uri.scheme == "file") return File(uri.path!!)
        val pasta = File(ctx.cacheDir, "livros").apply { mkdirs() }
        val destino = File(pasta, Integer.toHexString(uri.toString().hashCode()) + "." + formato)
        if (!destino.exists() || destino.length() == 0L) {
            val temp = File(pasta, destino.name + ".tmp")
            ctx.contentResolver.openInputStream(uri)!!.use { e -> temp.outputStream().use { e.copyTo(it) } }
            temp.renameTo(destino)
        }
        return destino
    }
}

/** Desenha atrás das barras do sistema e afasta o conteúdo delas (e do teclado). */
@Suppress("DEPRECATION")
fun Activity.telaInteira(raiz: View, afastar: Boolean = true) {
    if (Build.VERSION.SDK_INT >= 30) {
        window.setDecorFitsSystemWindows(false)
    } else {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    if (!afastar) return
    raiz.setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val teclado = insets.getInsets(WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, teclado.bottom))
        } else {
            v.setPadding(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom
            )
        }
        insets
    }
}

fun Context.dp(valor: Int) = (valor * resources.displayMetrics.density).toInt()

fun formatarTamanho(bytes: Long): String {
    val kb = bytes / 1024.0
    return when {
        bytes <= 0 -> ""
        kb < 1024 -> String.format(Locale.getDefault(), "%.0f KB", kb)
        else -> String.format(Locale.getDefault(), "%.1f MB", kb / 1024)
    }
}
