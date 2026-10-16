package com.leitordelivros.app

/** Transforma o HTML de um capítulo em trechos de texto para ler em voz alta. */
object Paragrafos {

    private const val MAXIMO = 1500
    private val SEM_TEXTO = Regex("(?is)<(head|script|style|svg)\\b.*?</\\1\\s*>")
    private val BLOCO = Regex("(?i)</?(p|div|h[1-6]|li|br|tr|td|blockquote|section|article|title|dd|dt|figcaption|pre|hr)\\b[^>]*>")
    private val TAG = Regex("<[^>]*>")
    private val ENTIDADE = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val ESPACO = Regex("[ \\t\\u00A0\\u200B]+")
    private val FRASE = Regex("(?<=[.!?…;:])\\s+")
    private val NOMES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "laquo" to "«", "raquo" to "»",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’", "shy" to "",
    )

    fun de(html: String): List<String> {
        val texto = html.replace(SEM_TEXTO, " ").replace(BLOCO, "\n").replace(TAG, "")
        val decodificado = ENTIDADE.replace(texto) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { cp(it) }
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { cp(it) }
                else -> NOMES[e]
            } ?: m.value
        }
        val r = mutableListOf<String>()
        for (linha in decodificado.split('\n')) {
            val limpo = linha.replace(ESPACO, " ").trim()
            if (limpo.none { it.isLetterOrDigit() }) continue
            if (limpo.length <= MAXIMO) r += limpo else r += dividir(limpo)
        }
        return r
    }

    private fun cp(codigo: Int): String? =
        if (Character.isValidCodePoint(codigo)) String(Character.toChars(codigo)) else null

    /** Parágrafos enormes viram pedaços menores, cortando entre frases. */
    private fun dividir(texto: String): List<String> {
        val r = mutableListOf<String>()
        val atual = StringBuilder()
        for (frase in texto.split(FRASE)) {
            var f = frase
            while (f.length > MAXIMO) {
                val corte = f.lastIndexOf(' ', MAXIMO).takeIf { it > 0 } ?: MAXIMO
                if (atual.isNotEmpty()) { r += atual.toString(); atual.setLength(0) }
                r += f.substring(0, corte).trim()
                f = f.substring(corte).trim()
            }
            if (atual.length + f.length + 1 > MAXIMO && atual.isNotEmpty()) {
                r += atual.toString()
                atual.setLength(0)
            }
            if (atual.isNotEmpty()) atual.append(' ')
            atual.append(f)
        }
        if (atual.isNotEmpty()) r += atual.toString()
        return r.filter { it.isNotBlank() }
    }
}
