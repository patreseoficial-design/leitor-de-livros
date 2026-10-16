package com.leitordelivros.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val PEDIDO_IMPORTAR = 1
        const val ABA_INICIO = 0
        const val ABA_BIBLIOTECA = 1
        const val ABA_LOJA = 2
        const val TODOS = 0
        const val LENDO = 1
        const val FAVORITOS = 2
        const val PARA_LER = 3
        const val LIDOS = 4
    }

    private lateinit var conteudoInicio: LinearLayout
    private lateinit var grade: GridView
    private lateinit var chips: LinearLayout
    private lateinit var busca: EditText
    private lateinit var vazioBiblioteca: TextView
    private lateinit var paineis: List<View>

    private var aba = ABA_INICIO
    private var filtro = TODOS
    private var mostrados: List<Livro> = emptyList()
    private var importando = 0
    /** Voltar numa aba que não é o Início volta para o Início (Android 13+) */
    private var voltar: android.window.OnBackInvokedCallback? = null
    private val adaptador = Adaptador()
    private val fundo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val capas = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        telaInteira(findViewById(R.id.raiz))

        conteudoInicio = findViewById(R.id.conteudoInicio)
        grade = findViewById(R.id.grade)
        chips = findViewById(R.id.chips)
        busca = findViewById(R.id.busca)
        vazioBiblioteca = findViewById(R.id.vazioBiblioteca)
        paineis = listOf(findViewById(R.id.painelInicio), findViewById(R.id.painelBiblioteca), findViewById(R.id.painelLoja))

        grade.adapter = adaptador
        grade.emptyView = vazioBiblioteca
        grade.setOnItemClickListener { _, _, pos, _ -> ler(mostrados[pos]) }

        busca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filtrar()
        })
        findViewById<View>(R.id.btnImportar).setOnClickListener { importar() }
        findViewById<View>(R.id.navInicio).setOnClickListener { mudarAba(ABA_INICIO) }
        findViewById<View>(R.id.navBiblioteca).setOnClickListener { mudarAba(ABA_BIBLIOTECA) }
        findViewById<View>(R.id.navLoja).setOnClickListener { mudarAba(ABA_LOJA) }

        aba = savedInstanceState?.getInt("aba") ?: ABA_INICIO
        filtro = savedInstanceState?.getInt("filtro") ?: TODOS
        mudarAba(aba)

        // Livros antigos sem título/capa lidos ainda
        fundo.execute { try { Biblioteca.lerMetadados(this) { principal.post { atualizarTudo() } } } catch (e: Exception) { } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("aba", aba)
        outState.putInt("filtro", filtro)
    }

    override fun onResume() {
        super.onResume()
        atualizarTudo()
    }

    override fun onDestroy() {
        fundo.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (aba != ABA_INICIO) mudarAba(ABA_INICIO)
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun livros(): List<Livro> = synchronized(Biblioteca) { Biblioteca.todos(this).values.toList() }

    private fun atualizarTudo() {
        capas.evictAll()
        montarInicio()
        montarChips()
        filtrar()
        montarLoja()
    }

    // ---------- Abas ----------

    private fun mudarAba(nova: Int) {
        aba = nova
        paineis.forEachIndexed { i, v -> v.visibility = if (i == nova) View.VISIBLE else View.GONE }
        val itens = listOf(
            Triple(R.id.iconeInicio, R.id.textoInicio, ABA_INICIO),
            Triple(R.id.iconeBiblioteca, R.id.textoBiblioteca, ABA_BIBLIOTECA),
            Triple(R.id.iconeLoja, R.id.textoLoja, ABA_LOJA),
        )
        for ((icone, texto, qual) in itens) {
            val cor = getColor(if (qual == nova) R.color.destaque else R.color.texto2)
            findViewById<ImageView>(icone).imageTintList = android.content.res.ColorStateList.valueOf(cor)
            findViewById<TextView>(texto).setTextColor(cor)
        }
        findViewById<View>(R.id.btnImportar).visibility = if (nova == ABA_LOJA) View.GONE else View.VISIBLE
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val d = onBackInvokedDispatcher
            if (nova != ABA_INICIO && voltar == null) {
                voltar = android.window.OnBackInvokedCallback { mudarAba(ABA_INICIO) }.also {
                    d.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it)
                }
            } else if (nova == ABA_INICIO) {
                voltar?.let { d.unregisterOnBackInvokedCallback(it) }
                voltar = null
            }
        }
    }

    // ---------- Início ----------

    private fun montarInicio() {
        val c = conteudoInicio
        c.removeAllViews()
        val todos = livros()

        c.addView(TextView(this).apply {
            setText(R.string.inicio)
            textSize = 30f
            setTextColor(getColor(R.color.texto))
            paint.isFakeBoldText = true
            setPadding(dp(20), dp(18), dp(20), dp(14))
        })

        if (todos.isEmpty()) {
            c.addView(boasVindas())
            return
        }

        val lendo = todos.filter { it.progresso > 0f && !it.lido }.sortedByDescending { it.ultimaLeitura }
        lendo.firstOrNull()?.let { c.addView(cartaoContinuar(it)) }

        prateleira(c, getString(R.string.lendo_agora), lendo.drop(1), LENDO)
        prateleira(c, getString(R.string.para_ler), todos.filter { it.paraLer && !it.lido }, PARA_LER)
        prateleira(c, getString(R.string.favoritos), todos.filter { it.favorito }, FAVORITOS)
        prateleira(c, getString(R.string.adicionados), todos.sortedByDescending { it.data }.take(15), TODOS)
    }

    private fun boasVindas(): View {
        val caixa = layoutInflater.inflate(R.layout.cartao_continuar, conteudoInicio, false) as LinearLayout
        // Reaproveita o cartão: capa de exemplo e botão de importar
        caixa.findViewById<CapaView>(R.id.capaContinuar).mostrar(null, getString(R.string.app_name), "")
        caixa.findViewById<TextView>(R.id.tituloContinuar).setText(R.string.boas_vindas)
        caixa.findViewById<TextView>(R.id.autorContinuar).apply {
            setText(R.string.boas_vindas_texto)
            isSingleLine = false
        }
        caixa.findViewById<View>(R.id.capituloContinuar).visibility = View.GONE
        (caixa.findViewById<View>(R.id.progressoContinuar).parent as View).visibility = View.GONE
        caixa.findViewById<TextView>(R.id.btnLer).apply {
            setText(R.string.importar_curto)
            setOnClickListener { importar() }
        }
        caixa.findViewById<View>(R.id.btnOuvir).visibility = View.GONE
        // Esconde o "Continuar lendo" do topo
        ((caixa.getChildAt(1) as LinearLayout).getChildAt(0)).visibility = View.GONE
        return caixa
    }

    private fun cartaoContinuar(l: Livro): View {
        val v = layoutInflater.inflate(R.layout.cartao_continuar, conteudoInicio, false)
        v.findViewById<CapaView>(R.id.capaContinuar).mostrar(capa(l), l.titulo, l.autor)
        v.findViewById<TextView>(R.id.tituloContinuar).text = l.titulo
        v.findViewById<TextView>(R.id.autorContinuar).text = l.autor.ifBlank { getString(R.string.autor_desconhecido) }
        v.findViewById<TextView>(R.id.capituloContinuar).apply {
            text = l.rotulo
            visibility = if (l.rotulo.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<ProgressBar>(R.id.progressoContinuar).progress = (l.progresso * 1000).toInt()
        v.findViewById<TextView>(R.id.porcentagemContinuar).text = getString(R.string.progresso, (l.progresso * 100).toInt())
        v.findViewById<View>(R.id.btnLer).setOnClickListener { ler(l) }
        v.findViewById<View>(R.id.btnOuvir).apply {
            visibility = if (l.formato == "pdf") View.GONE else View.VISIBLE
            setOnClickListener { ouvir(l) }
        }
        v.setOnClickListener { ler(l) }
        return v
    }

    private fun prateleira(c: LinearLayout, titulo: String, itens: List<Livro>, filtroVerTudo: Int) {
        if (itens.isEmpty()) return
        val cabecalho = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(26), dp(12), dp(10))
        }
        cabecalho.addView(TextView(this).apply {
            text = titulo
            textSize = 20f
            setTextColor(getColor(R.color.texto))
            paint.isFakeBoldText = true
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cabecalho.addView(TextView(this).apply {
            setText(R.string.ver_tudo)
            textSize = 14f
            setTextColor(getColor(R.color.destaque))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener {
                filtro = filtroVerTudo
                montarChips()
                filtrar()
                mudarAba(ABA_BIBLIOTECA)
            }
        })
        c.addView(cabecalho)

        val rolagem = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
        }
        val linha = LinearLayout(this)
        for (l in itens) {
            val item = layoutInflater.inflate(R.layout.item_capa, linha, false)
            preencher(item, l)
            item.setOnClickListener { ler(l) }
            linha.addView(item, LinearLayout.LayoutParams(dp(128), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(14)
            })
        }
        rolagem.addView(linha)
        c.addView(rolagem)
    }

    // ---------- Biblioteca ----------

    private fun montarChips() {
        chips.removeAllViews()
        val opcoes = listOf(
            TODOS to R.string.todos,
            LENDO to R.string.lendo_agora,
            FAVORITOS to R.string.favoritos,
            PARA_LER to R.string.para_ler,
            LIDOS to R.string.lidos,
        )
        for ((valor, nome) in opcoes) {
            val sel = valor == filtro
            chips.addView(TextView(this).apply {
                setText(nome)
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(16), 0, dp(16), 0)
                setBackgroundResource(if (sel) R.drawable.chip_selecionado else R.drawable.chip)
                setTextColor(getColor(if (sel) R.color.destaque_escuro else R.color.texto))
                paint.isFakeBoldText = sel
                setOnClickListener {
                    filtro = valor
                    montarChips()
                    filtrar()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(8) })
        }
    }

    private fun filtrar() {
        val todos = livros()
        val base = when (filtro) {
            LENDO -> todos.filter { it.progresso > 0f && !it.lido }.sortedByDescending { it.ultimaLeitura }
            FAVORITOS -> todos.filter { it.favorito }
            PARA_LER -> todos.filter { it.paraLer }
            LIDOS -> todos.filter { it.lido }
            else -> todos.sortedByDescending { it.data }
        }.let { lista ->
            if (filtro == LENDO || filtro == TODOS) lista else lista.sortedBy { it.titulo.lowercase(Locale.getDefault()) }
        }
        val termo = busca.text.toString().trim()
        mostrados = if (termo.isEmpty()) base
        else base.filter { it.titulo.contains(termo, true) || it.autor.contains(termo, true) }
        adaptador.notifyDataSetChanged()
        vazioBiblioteca.setText(if (todos.isEmpty()) R.string.boas_vindas_texto else R.string.nenhum)
    }

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = mostrados.size
        override fun getItem(posicao: Int) = mostrados[posicao]
        override fun getItemId(posicao: Int) = posicao.toLong()

        override fun getView(posicao: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_capa, parent, false)
            preencher(v, mostrados[posicao])
            return v
        }
    }

    /** Preenche um item de capa (prateleira ou grade). */
    private fun preencher(v: View, l: Livro) {
        v.findViewById<CapaView>(R.id.capa).mostrar(capa(l), l.titulo, l.autor)
        v.findViewById<TextView>(R.id.tituloLivro).text = l.titulo
        v.findViewById<TextView>(R.id.autor).text = l.autor.ifBlank { l.formato.uppercase(Locale.ROOT) }
        v.findViewById<ProgressBar>(R.id.barraProgresso).apply {
            progress = (l.progresso * 1000).toInt()
            visibility = if (l.progresso > 0f && !l.lido) View.VISIBLE else View.INVISIBLE
        }
        v.findViewById<View>(R.id.btnMais).setOnClickListener { menu(it, l) }
    }

    private fun capa(livro: Livro): Bitmap? {
        if (livro.capa.isEmpty()) return null
        capas.get(livro.capa)?.let { return it }
        return BitmapFactory.decodeFile(livro.capa)?.also { capas.put(livro.capa, it) }
    }

    // ---------- Loja (em breve) ----------

    private fun montarLoja() {
        val pilha = findViewById<FrameLayout>(R.id.pilhaCapas)
        pilha.removeAllViews()
        val exemplos = livros().filter { it.capa.isNotEmpty() }.shuffled().take(3)
        val giros = floatArrayOf(-10f, 8f, 0f)
        val deslocamentos = intArrayOf(-70, 70, 0)
        for (i in 0 until 3) {
            val l = exemplos.getOrNull(i)
            val capa = CapaView(this)
            capa.mostrar(l?.let { capa(it) }, "", "")
            capa.rotation = giros[i]
            capa.translationX = dp(deslocamentos[i]).toFloat()
            capa.elevation = dp(4 + i * 4).toFloat()
            pilha.addView(capa, FrameLayout.LayoutParams(dp(160), dp(240), Gravity.CENTER))
        }
    }

    // ---------- Ações ----------

    private fun ler(livro: Livro) {
        startActivity(Intent(this, LeitorActivity::class.java).putExtra(LeitorActivity.EXTRA_CHAVE, livro.chave))
    }

    private fun ouvir(livro: Livro) {
        if (livro.formato == "pdf") {
            Toast.makeText(this, R.string.pdf_sem_audio, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, OuvirActivity::class.java).putExtra(OuvirActivity.EXTRA_CHAVE, livro.chave))
    }

    private fun menu(ancora: View, l: Livro) {
        val pm = PopupMenu(this, ancora)
        val m = pm.menu
        m.add(0, 1, 0, R.string.detalhes)
        m.add(0, 2, 0, R.string.ler)
        if (l.formato != "pdf") m.add(0, 3, 0, R.string.ouvir)
        m.add(0, 4, 0, if (l.favorito) R.string.tirar_favoritos else R.string.add_favoritos)
        m.add(0, 5, 0, if (l.paraLer) R.string.tirar_para_ler else R.string.add_para_ler)
        m.add(0, 6, 0, if (l.lido) R.string.desmarcar_lido else R.string.marcar_como_lido)
        m.add(0, 7, 0, R.string.compartilhar)
        m.add(0, 8, 0, R.string.remover)
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> detalhes(l)
                2 -> ler(l)
                3 -> ouvir(l)
                4 -> { l.favorito = !l.favorito; salvarEAtualizar() }
                5 -> { l.paraLer = !l.paraLer; salvarEAtualizar() }
                6 -> {
                    l.lido = !l.lido
                    if (l.lido) { l.paraLer = false; l.progresso = 1f }
                    salvarEAtualizar()
                }
                7 -> compartilhar(l)
                8 -> AlertDialog.Builder(this)
                    .setMessage(getString(R.string.remover_pergunta, l.titulo))
                    .setPositiveButton(R.string.remover) { _, _ ->
                        if (Ouvindo.chave == l.chave) ServicoOuvir.enviar(this, ServicoOuvir.ACAO_PARAR)
                        Biblioteca.remover(this, l)
                        Toast.makeText(this, R.string.removido, Toast.LENGTH_SHORT).show()
                        atualizarTudo()
                    }
                    .setNegativeButton(R.string.fechar, null)
                    .show()
            }
            true
        }
        pm.show()
    }

    private fun salvarEAtualizar() {
        Biblioteca.salvar(this)
        atualizarTudo()
    }

    private fun detalhes(l: Livro) {
        AlertDialog.Builder(this)
            .setTitle(l.titulo)
            .setMessage(getString(R.string.detalhes_texto,
                l.autor.ifBlank { getString(R.string.autor_desconhecido) },
                l.formato.uppercase(Locale.ROOT),
                formatarTamanho(l.tamanho).ifEmpty { "—" },
                (l.progresso * 100).toInt()))
            .setPositiveButton(R.string.ler) { _, _ -> ler(l) }
            .setNegativeButton(R.string.fechar, null)
            .show()
    }

    private fun compartilhar(l: Livro) {
        val uri = ProvedorLivros.uri(this, l) ?: l.uri.takeIf { it.scheme == "content" } ?: return
        val envio = Intent(Intent.ACTION_SEND)
            .setType(ProvedorLivros.mime(l.formato))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        envio.clipData = ClipData.newRawUri(l.titulo, uri)
        try {
            startActivity(Intent.createChooser(envio, getString(R.string.compartilhar_com)))
        } catch (e: Exception) {
        }
    }

    // ---------- Importar ----------

    private fun importar() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/epub+zip", "application/pdf", "application/x-fictionbook+xml", "text/plain",
                "application/octet-stream"
            ))
        try {
            startActivityForResult(i, PEDIDO_IMPORTAR)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.erro_importar, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIDO_IMPORTAR || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { uris += it } }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isEmpty()) return

        importando += uris.size
        Toast.makeText(this, R.string.importando, Toast.LENGTH_SHORT).show()
        val tipos = uris.map { contentResolver.getType(it) }
        fundo.execute {
            var ok = 0
            for ((i, uri) in uris.withIndex()) {
                try {
                    Biblioteca.importar(this, uri, tipos[i])
                    ok++
                } catch (e: Exception) {
                }
            }
            try { Biblioteca.lerMetadados(this) {} } catch (e: Exception) { }
            principal.post {
                importando -= uris.size
                if (isDestroyed) return@post
                Toast.makeText(this, if (ok > 0) R.string.importado else R.string.erro_importar, Toast.LENGTH_SHORT).show()
                if (ok > 0) mudarAba(ABA_INICIO)
                atualizarTudo()
            }
        }
    }
}
