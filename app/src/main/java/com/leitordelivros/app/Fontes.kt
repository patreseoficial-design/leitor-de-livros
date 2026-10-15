package com.leitordelivros.app

import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * O conteúdo de um livro, dividido em capítulos. Cada capítulo é uma página HTML
 * servida para o WebView no endereço falso [BASE].
 */
interface Fonte {
    val capitulos: Int
    val sumario: List<Pair<String, Int>>
    fun urlCapitulo(i: Int): String
    /** Índice do capítulo deste endereço, ou -1 */
    fun indiceDe(url: String): Int
    /** Bytes e tipo de um arquivo do livro (capítulo, imagem, estilo...) */
    fun abrir(caminho: String): Pair<ByteArray, String>?
    fun fechar() {}

    companion object {
        const val BASE = "https://livro.local/"

        fun caminhoDe(url: String): String =
            URLDecoder.decode(url.removePrefix(BASE).substringBefore('#').substringBefore('?'), "UTF-8")
    }
}

private fun parser(entrada: InputStream): XmlPullParser = Xml.newPullParser().apply {
    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
    setInput(entrada, null)
}

private fun mimeDe(caminho: String) = when (caminho.substringAfterLast('.').lowercase()) {
    "xhtml", "html", "htm", "xml" -> "text/html"
    "css" -> "text/css"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "svg" -> "image/svg+xml"
    "webp" -> "image/webp"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

/** "OEBPS/text/" + "../images/a.jpg" = "OEBPS/images/a.jpg" */
private fun resolver(base: String, alvo: String): String {
    val semAncora = URLDecoder.decode(alvo.substringBefore('#'), "UTF-8")
    if (semAncora.startsWith("/")) return semAncora.removePrefix("/")
    val partes = base.trimEnd('/').split('/').filter { it.isNotEmpty() }.toMutableList()
    for (p in semAncora.split('/')) when (p) {
        ".." -> if (partes.isNotEmpty()) partes.removeAt(partes.size - 1)
        ".", "" -> {}
        else -> partes += p
    }
    return partes.joinToString("/")
}

private fun codificar(caminho: String) = caminho.split('/').joinToString("/") { android.net.Uri.encode(it) }

private fun esc(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

// ======================= EPUB =======================

class Epub(arquivo: File, rotuloCapitulo: String) : Fonte {

    private val zip = ZipFile(arquivo)
    private val espinha = mutableListOf<String>()
    override val sumario = mutableListOf<Pair<String, Int>>()

    companion object {
        private class Opf(val caminho: String, val pasta: String)

        private fun acharOpf(zip: ZipFile): Opf {
            var caminho: String? = null
            zip.getEntry("META-INF/container.xml")?.let { e ->
                zip.getInputStream(e).use { input ->
                    val p = parser(input)
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType == XmlPullParser.START_TAG && p.name == "rootfile") {
                            caminho = p.getAttributeValue(null, "full-path")
                            break
                        }
                    }
                }
            }
            val c = caminho ?: zip.entries().asSequence().map { it.name }.firstOrNull { it.endsWith(".opf") }
                ?: throw IllegalArgumentException("EPUB sem OPF")
            return Opf(c, if ('/' in c) c.substringBeforeLast('/') + "/" else "")
        }

        /** Título, autor e bytes da capa (se houver). */
        fun metadados(arquivo: File): Triple<String, String, ByteArray?>? {
            ZipFile(arquivo).use { zip ->
                val opf = acharOpf(zip)
                var titulo = ""
                var autor = ""
                var idCapa: String? = null
                val itens = HashMap<String, Pair<String, String>>() // id -> (href, tipo)
                var hrefCapa: String? = null
                zip.getInputStream(zip.getEntry(opf.caminho)).use { input ->
                    val p = parser(input)
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        if (p.eventType != XmlPullParser.START_TAG) continue
                        when (p.name.substringAfter(':')) {
                            "title" -> if (titulo.isEmpty()) titulo = p.nextText().trim()
                            "creator" -> if (autor.isEmpty()) autor = p.nextText().trim()
                            "meta" -> if (p.getAttributeValue(null, "name") == "cover") idCapa = p.getAttributeValue(null, "content")
                            "item" -> {
                                val id = p.getAttributeValue(null, "id") ?: ""
                                val href = p.getAttributeValue(null, "href") ?: continue
                                val tipo = p.getAttributeValue(null, "media-type") ?: ""
                                itens[id] = href to tipo
                                if (p.getAttributeValue(null, "properties")?.contains("cover-image") == true) hrefCapa = href
                            }
                        }
                    }
                }
                if (hrefCapa == null) hrefCapa = itens[idCapa]?.first
                if (hrefCapa == null) hrefCapa = itens.entries.firstOrNull {
                    it.key.contains("cover", true) && it.value.second.startsWith("image/")
                }?.value?.first
                val capa = hrefCapa?.let { h ->
                    zip.getEntry(resolver(opf.pasta, h))?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
                }
                return Triple(titulo, autor, capa)
            }
        }
    }

    init {
        val opf = acharOpf(zip)
        val itens = HashMap<String, String>()
        val idsEspinha = mutableListOf<String>()
        var hrefNav: String? = null
        var idNcx: String? = null
        zip.getInputStream(zip.getEntry(opf.caminho)).use { input ->
            val p = parser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name.substringAfter(':')) {
                    "item" -> {
                        val id = p.getAttributeValue(null, "id") ?: continue
                        val href = p.getAttributeValue(null, "href") ?: continue
                        itens[id] = href
                        if (p.getAttributeValue(null, "properties")?.split(' ')?.contains("nav") == true) hrefNav = href
                    }
                    "spine" -> idNcx = p.getAttributeValue(null, "toc")
                    "itemref" -> {
                        if (p.getAttributeValue(null, "linear") == "no") continue
                        p.getAttributeValue(null, "idref")?.let { idsEspinha += it }
                    }
                }
            }
        }
        idsEspinha.mapNotNullTo(espinha) { id -> itens[id]?.let { resolver(opf.pasta, it) } }
        if (espinha.isEmpty()) throw IllegalArgumentException("EPUB sem capítulos")

        // Sumário: EPUB 3 (nav.xhtml) ou EPUB 2 (toc.ncx)
        try {
            val nav = hrefNav
            val ncx = idNcx?.let { itens[it] }
            if (nav != null) lerNav(resolver(opf.pasta, nav))
            else if (ncx != null) lerNcx(resolver(opf.pasta, ncx))
        } catch (e: Exception) {
        }
        if (sumario.isEmpty()) {
            espinha.indices.forEach { sumario += String.format(rotuloCapitulo, it + 1) to it }
        }
    }

    private fun adicionarSumario(titulo: String, pastaArquivo: String, href: String) {
        val alvo = resolver(pastaArquivo, href)
        val i = espinha.indexOf(alvo)
        if (i >= 0 && titulo.isNotBlank()) sumario += titulo.trim().replace(Regex("\\s+"), " ") to i
    }

    private fun lerNav(caminho: String) {
        val pasta = if ('/' in caminho) caminho.substringBeforeLast('/') + "/" else ""
        zip.getInputStream(zip.getEntry(caminho) ?: return).use { input ->
            val p = parser(input)
            var naToc = false
            var profNav = 0
            var href: String? = null
            val texto = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "nav" -> {
                            profNav++
                            if (p.getAttributeValue(null, "epub:type") == "toc" || !naToc && sumario.isEmpty()) naToc = true
                        }
                        "a" -> if (naToc) { href = p.getAttributeValue(null, "href"); texto.setLength(0) }
                    }
                    XmlPullParser.TEXT -> if (href != null) texto.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "a" -> { href?.let { adicionarSumario(texto.toString(), pasta, it) }; href = null }
                        "nav" -> { profNav--; if (naToc && sumario.isNotEmpty()) return }
                    }
                }
            }
        }
    }

    private fun lerNcx(caminho: String) {
        val pasta = if ('/' in caminho) caminho.substringBeforeLast('/') + "/" else ""
        zip.getInputStream(zip.getEntry(caminho) ?: return).use { input ->
            val p = parser(input)
            var titulo = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "text" -> titulo = p.nextText()
                    "content" -> p.getAttributeValue(null, "src")?.let { adicionarSumario(titulo, pasta, it) }
                }
            }
        }
    }

    override val capitulos get() = espinha.size
    override fun urlCapitulo(i: Int) = Fonte.BASE + codificar(espinha[i.coerceIn(0, espinha.size - 1)])
    override fun indiceDe(url: String) = espinha.indexOf(Fonte.caminhoDe(url))

    override fun abrir(caminho: String): Pair<ByteArray, String>? {
        val e = zip.getEntry(caminho) ?: return null
        return zip.getInputStream(e).use { it.readBytes() } to mimeDe(caminho)
    }

    override fun fechar() = zip.close()
}

// ======================= Capítulos gerados (FB2 e TXT) =======================

open class FonteMemoria(private val paginas: List<String>, override val sumario: List<Pair<String, Int>>) : Fonte {
    override val capitulos get() = paginas.size
    override fun urlCapitulo(i: Int) = Fonte.BASE + "cap${i.coerceIn(0, paginas.size - 1)}.html"
    override fun indiceDe(url: String): Int =
        Regex("^cap(\\d+)\\.html$").find(Fonte.caminhoDe(url))?.groupValues?.get(1)?.toIntOrNull() ?: -1

    override fun abrir(caminho: String): Pair<ByteArray, String>? {
        val i = indiceDe(Fonte.BASE + caminho)
        if (i !in paginas.indices) return null
        val html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>${paginas[i]}</body></html>"
        return html.toByteArray() to "text/html"
    }
}

object Fb2 {

    private class Lido(val titulo: String, val autor: String, val idCapa: String?, val binarios: Map<String, Pair<String, String>>)

    private fun ler(arquivo: File, soDescricao: Boolean, html: ((XmlPullParser) -> Unit)? = null): Lido {
        var titulo = ""
        val nomes = mutableListOf<String>()
        var idCapa: String? = null
        val binarios = HashMap<String, Pair<String, String>>()
        var naDescricao = false
        var noAutor = false
        var autorPronto = false
        arquivo.inputStream().buffered().use { input ->
            val p = parser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "description" -> naDescricao = true
                        "book-title" -> if (naDescricao && titulo.isEmpty()) titulo = p.nextText().trim()
                        "author" -> if (naDescricao && !autorPronto) noAutor = true
                        "first-name", "middle-name", "last-name" -> if (noAutor) nomes += p.nextText().trim()
                        "image" -> if (naDescricao && idCapa == null) idCapa = href(p)
                        "body" -> {
                            if (soDescricao && idCapa == null) break
                            html?.invoke(p)
                        }
                        "binary" -> {
                            val id = p.getAttributeValue(null, "id") ?: ""
                            val tipo = p.getAttributeValue(null, "content-type") ?: "image/jpeg"
                            if (!soDescricao || id == idCapa) binarios[id] = tipo to p.nextText()
                        }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG) when (p.name) {
                    "description" -> naDescricao = false
                    "author" -> if (noAutor) { noAutor = false; autorPronto = true }
                }
            }
        }
        return Lido(titulo, nomes.filter { it.isNotEmpty() }.joinToString(" "), idCapa, binarios)
    }

    private fun href(p: XmlPullParser): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).endsWith("href")) return p.getAttributeValue(i).removePrefix("#")
        }
        return null
    }

    fun metadados(arquivo: File): Triple<String, String, ByteArray?>? {
        val l = ler(arquivo, soDescricao = true)
        val capa = l.idCapa?.let { l.binarios[it] }?.let {
            try { Base64.decode(it.second, Base64.DEFAULT) } catch (e: Exception) { null }
        }
        return Triple(l.titulo, l.autor, capa)
    }

    /** Converte o FB2 em capítulos HTML (uma seção principal = um capítulo). */
    fun fonte(arquivo: File, rotuloCapitulo: String): Fonte {
        // 1ª passada: imagens (ficam no fim do arquivo)
        val binarios = ler(arquivo, soDescricao = false).binarios
        val paginas = mutableListOf<String>()
        val sumario = mutableListOf<Pair<String, Int>>()

        arquivo.inputStream().buffered().use { input ->
            val p = parser(input)
            var noCorpo = false
            var corpoNotas = false
            var nivel = 0
            var atual = StringBuilder()
            var tituloCap: StringBuilder? = null
            var noTitulo = false
            var parNoTitulo = 0
            fun fecharCapitulo(inicioDeSecao: Boolean = false) {
                // O que vem antes da 1ª seção (nome do livro, epígrafe) entra junto no 1º capítulo
                if (inicioDeSecao && paginas.isEmpty() && !atual.contains("<section>")) {
                    tituloCap = null
                    return
                }
                if (atual.isNotBlank()) {
                    val nome = tituloCap?.toString()?.trim()?.ifEmpty { null }
                        ?: String.format(rotuloCapitulo, paginas.size + 1)
                    sumario += nome to paginas.size
                    paginas += atual.toString()
                }
                atual = StringBuilder()
                tituloCap = null
            }
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "body" -> {
                            noCorpo = true
                            corpoNotas = p.getAttributeValue(null, "name") == "notes"
                            if (corpoNotas) fecharCapitulo()
                        }
                        "section" -> if (noCorpo) {
                            nivel++
                            if (nivel == 1 && !corpoNotas) fecharCapitulo(inicioDeSecao = true)
                            atual.append("<section>")
                        }
                        "title" -> if (noCorpo) {
                            noTitulo = true
                            parNoTitulo = 0
                            if (nivel <= 1 && tituloCap == null) tituloCap = StringBuilder()
                            atual.append(if (nivel <= 1) "<h2>" else "<h3>")
                        }
                        "subtitle" -> if (noCorpo) atual.append("<h4>")
                        "p", "v" -> if (noCorpo) {
                            if (noTitulo) { if (parNoTitulo++ > 0) atual.append("<br>") } else atual.append("<p>")
                        }
                        "emphasis" -> if (noCorpo) atual.append("<em>")
                        "strong" -> if (noCorpo) atual.append("<strong>")
                        "empty-line" -> if (noCorpo) atual.append("<br>")
                        "epigraph", "cite" -> if (noCorpo) atual.append("<blockquote>")
                        "poem", "stanza" -> if (noCorpo) atual.append("<div style=\"margin:1em 2em\">")
                        "text-author" -> if (noCorpo) atual.append("<p style=\"text-align:right\"><em>")
                        "image" -> if (noCorpo) href(p)?.let { id ->
                            binarios[id]?.let { (tipo, dados) ->
                                atual.append("<p style=\"text-align:center\"><img src=\"data:$tipo;base64,")
                                    .append(dados.replace(Regex("\\s"), "")).append("\"></p>")
                            }
                        }
                        "binary" -> noCorpo = false
                    }
                    XmlPullParser.TEXT -> if (noCorpo) {
                        atual.append(esc(p.text))
                        if (noTitulo) tituloCap?.append(p.text)?.append(' ')
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "body" -> { noCorpo = false; nivel = 0 }
                        "section" -> if (noCorpo) { atual.append("</section>"); nivel-- }
                        "title" -> if (noCorpo) { noTitulo = false; atual.append(if (nivel <= 1) "</h2>" else "</h3>") }
                        "subtitle" -> if (noCorpo) atual.append("</h4>")
                        "p", "v" -> if (noCorpo && !noTitulo) atual.append("</p>")
                        "emphasis" -> if (noCorpo) atual.append("</em>")
                        "strong" -> if (noCorpo) atual.append("</strong>")
                        "epigraph", "cite" -> if (noCorpo) atual.append("</blockquote>")
                        "poem", "stanza" -> if (noCorpo) atual.append("</div>")
                        "text-author" -> if (noCorpo) atual.append("</em></p>")
                    }
                }
            }
            fecharCapitulo()
        }
        if (paginas.isEmpty()) throw IllegalArgumentException("FB2 vazio")
        return FonteMemoria(paginas, sumario)
    }
}

object Txt {

    fun lerTexto(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** Divide o texto em partes de uns 60 mil caracteres, sem cortar parágrafos. */
    fun fonte(arquivo: File, rotuloParte: String): Fonte {
        val texto = lerTexto(arquivo.readBytes()).replace("\r\n", "\n")
        val paginas = mutableListOf<String>()
        val atual = StringBuilder()
        for (par in texto.split(Regex("\n\\s*\n|\n(?=\\s{2,})"))) {
            val limpo = par.trim()
            if (limpo.isEmpty()) continue
            atual.append("<p>").append(esc(limpo).replace("\n", "<br>")).append("</p>")
            if (atual.length > 60_000) {
                paginas += atual.toString()
                atual.setLength(0)
            }
        }
        if (atual.isNotEmpty() || paginas.isEmpty()) paginas += atual.toString()
        val sumario = paginas.indices.map { String.format(rotuloParte, it + 1) to it }
        return FonteMemoria(paginas, sumario)
    }
}
