package com.leitordelivros.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/** Lê um livro: EPUB, FB2 e TXT numa página que rola; PDF em páginas com zoom. */
class LeitorActivity : Activity() {

    private companion object {
        const val PREFS = "leitura"
        val TEMAS = arrayOf(
            arrayOf("#FFFFFF", "#1F1F1F", "#1565C0"), // claro
            arrayOf("#F6EEDB", "#4A3B28", "#8A5A12"), // sépia
            arrayOf("#121212", "#D2D2D2", "#F7A928"), // escuro
        )
    }

    private lateinit var livro: Livro
    private lateinit var conteudo: FrameLayout
    private lateinit var carregando: TextView
    private lateinit var barraCima: View
    private lateinit var barraBaixo: View
    private lateinit var capituloAtual: TextView
    private lateinit var porcentagem: TextView
    private lateinit var seek: SeekBar
    private lateinit var selo: TextView

    private var fonte: Fonte? = null
    private var web: WebView? = null
    private var pdf: VisorPdf? = null
    private var descritor: ParcelFileDescriptor? = null

    private var capitulo = 0
    private var fracao = 0f
    private var fracaoPendente: Float? = null
    private var barrasVisiveis = true

    private val fundo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leitor)

        val uri = intent.data ?: run { finish(); return }
        livro = Biblioteca.registrar(this, uri, intent.type)
        capitulo = livro.capitulo
        fracao = livro.posicao

        conteudo = findViewById(R.id.conteudo)
        carregando = findViewById(R.id.carregando)
        barraCima = findViewById(R.id.barraCima)
        barraBaixo = findViewById(R.id.barraBaixo)
        capituloAtual = findViewById(R.id.capituloAtual)
        porcentagem = findViewById(R.id.porcentagemLeitor)
        seek = findViewById(R.id.seekLivro)
        selo = findViewById(R.id.selo)

        telaInteira(findViewById(R.id.raiz), afastar = false)
        findViewById<View>(R.id.raiz).setOnApplyWindowInsetsListener { _, insets ->
            val (topo, baixo) = if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                b.top to b.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.stableInsetTop to insets.stableInsetBottom
            }
            barraCima.setPadding(dp(4), topo, dp(4), 0)
            barraBaixo.setPadding(dp(16), dp(8), dp(16), baixo + dp(12))
            insets
        }

        findViewById<TextView>(R.id.tituloLeitor).text = livro.titulo
        findViewById<View>(R.id.btnVoltar).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSumario).setOnClickListener { mostrarSumario() }
        findViewById<View>(R.id.btnAparencia).setOnClickListener { mostrarAparencia() }
        seek.max = 1000
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, valor: Int, doUsuario: Boolean) {
                if (doUsuario) porcentagem.text = getString(R.string.progresso, valor / 10)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = pularPara(s.progress / 1000f)
        })

        if (livro.formato == "pdf") abrirPdf() else abrirTexto()
    }

    override fun onPause() {
        super.onPause()
        guardarPosicao()
    }

    override fun onDestroy() {
        pdf?.fechar()
        try { descritor?.close() } catch (e: Exception) { }
        web?.destroy()
        val f = fonte
        fundo.execute { try { f?.fechar() } catch (e: Exception) { } }
        fundo.shutdown()
        super.onDestroy()
    }

    private fun erro(msg: Int) {
        carregando.visibility = View.VISIBLE
        carregando.setText(msg)
    }

    // ---------- Posição ----------

    private val total get() = fonte?.capitulos ?: pdf?.total ?: 1

    private fun progressoGeral(): Float =
        if (livro.formato == "pdf") (capitulo + 1f) / total
        else ((capitulo + fracao) / total).coerceIn(0f, 1f)

    private fun medirFracao() {
        val w = web ?: return
        val altura = w.contentHeight * resources.displayMetrics.density - w.height
        if (altura > 0) fracao = (w.scrollY / altura).coerceIn(0f, 1f)
    }

    private fun guardarPosicao() {
        if (fonte == null && pdf == null) return
        medirFracao()
        livro.capitulo = capitulo
        livro.posicao = fracao
        livro.progresso = progressoGeral()
        livro.ultimaLeitura = System.currentTimeMillis()
        Biblioteca.salvar(this)
    }

    private fun atualizarBarra() {
        val p = progressoGeral()
        if (!seek.isPressed) seek.progress = (p * 1000).toInt()
        porcentagem.text = getString(R.string.progresso, (p * 100).toInt())
        capituloAtual.text = if (livro.formato == "pdf") getString(R.string.pagina_de, capitulo + 1, total)
        else fonte?.sumario?.lastOrNull { it.second <= capitulo }?.first ?: ""
    }

    private fun pularPara(p: Float) {
        if (livro.formato == "pdf") {
            pdf?.irPara((p * total).toInt() + 1)
            return
        }
        val alvo = p * total
        val cap = alvo.toInt().coerceIn(0, total - 1)
        irPara(cap, (alvo - cap).coerceIn(0f, 1f))
    }

    // ---------- PDF ----------

    private fun abrirPdf() {
        findViewById<View>(R.id.btnSumario).visibility = View.GONE
        findViewById<View>(R.id.btnAparencia).visibility = View.GONE
        fundo.execute {
            val fd = try {
                ParcelFileDescriptor.open(Biblioteca.arquivoLocal(this, livro.uri, "pdf"), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                null
            }
            principal.post {
                if (isDestroyed) { try { fd?.close() } catch (e: Exception) { }; return@post }
                if (fd == null) { erro(R.string.erro_abrir); return@post }
                descritor = fd
                val visor = try {
                    VisorPdf(this, fd) { atual, _ ->
                        capitulo = atual - 1
                        atualizarBarra()
                    }
                } catch (e: SecurityException) {
                    erro(R.string.pdf_senha); return@post
                } catch (e: Exception) {
                    erro(R.string.erro_abrir); return@post
                }
                pdf = visor
                carregando.visibility = View.GONE
                visor.lista.clipToPadding = false
                visor.lista.setPadding(0, barraCima.height, 0, barraBaixo.height)
                conteudo.addView(visor.lista)
                visor.irPara(capitulo + 1)
                atualizarBarra()
            }
        }
    }

    // ---------- EPUB, FB2 e TXT ----------

    private fun abrirTexto() {
        fundo.execute {
            val f = try {
                val arq = Biblioteca.arquivoLocal(this, livro.uri, livro.formato)
                when (livro.formato) {
                    "epub" -> Epub(arq, getString(R.string.capitulo))
                    "fb2" -> Fb2.fonte(arq, getString(R.string.capitulo))
                    else -> Txt.fonte(arq, getString(R.string.parte))
                }
            } catch (e: Throwable) {
                null
            }
            principal.post {
                if (isDestroyed) return@post
                if (f == null) { erro(R.string.erro_abrir); return@post }
                fonte = f
                capitulo = capitulo.coerceIn(0, f.capitulos - 1)
                criarWebView()
                irPara(capitulo, fracao)
                principal.postDelayed({ mostrarBarras(false) }, 1200)
            }
        }
    }

    private fun criarWebView() {
        val w = WebView(this)
        w.settings.javaScriptEnabled = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.setSupportZoom(false)
        w.settings.textZoom = prefs.getInt("letra", 100)
        w.setBackgroundColor(android.graphics.Color.parseColor(TEMAS[tema()][0]))
        w.webViewClient = Cliente()
        w.isVerticalScrollBarEnabled = false

        val gestos = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (w.hitTestResult.type != WebView.HitTestResult.UNKNOWN_TYPE) return false // tocou num link
                when {
                    e.x < w.width / 3f -> virarPagina(false)
                    e.x > w.width * 2 / 3f -> virarPagina(true)
                    else -> mostrarBarras(!barrasVisiveis)
                }
                return true
            }
        })
        w.setOnTouchListener { _, ev -> gestos.onTouchEvent(ev); false }
        w.setOnScrollChangeListener { _, _, _, _, _ ->
            medirFracao()
            atualizarBarra()
        }
        web = w
        conteudo.addView(w)
        carregando.visibility = View.GONE
    }

    private fun tema() = prefs.getInt("tema", 0).coerceIn(0, 2)

    private fun irPara(cap: Int, fr: Float) {
        val f = fonte ?: return
        capitulo = cap.coerceIn(0, f.capitulos - 1)
        fracao = fr
        fracaoPendente = fr
        web?.loadUrl(f.urlCapitulo(capitulo))
        atualizarBarra()
    }

    /** Toque na lateral (ou botão de volume): anda uma tela. No fim do capítulo, vai para o próximo. */
    private fun virarPagina(frente: Boolean) {
        val w = web ?: return
        val passo = w.height - dp(48)
        if (frente) {
            if (w.canScrollVertically(1)) w.scrollBy(0, passo)
            else if (capitulo < total - 1) irPara(capitulo + 1, 0f)
        } else {
            if (w.canScrollVertically(-1)) w.scrollBy(0, -passo)
            else if (capitulo > 0) irPara(capitulo - 1, 1f)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (web != null && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { virarPagina(true); return true }
        if (web != null && keyCode == KeyEvent.KEYCODE_VOLUME_UP) { virarPagina(false); return true }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    private fun mostrarBarras(mostrar: Boolean) {
        barrasVisiveis = mostrar
        barraCima.visibility = if (mostrar) View.VISIBLE else View.GONE
        barraBaixo.visibility = if (mostrar) View.VISIBLE else View.GONE
        // Tela cheia para ler, sem a barra de status
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (mostrar) c.show(WindowInsets.Type.systemBars())
            else {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.systemBars())
            }
        } else {
            val base = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            window.decorView.systemUiVisibility = if (mostrar) base
            else base or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /** CSS do tema e botões de capítulo colocados em cada capítulo. */
    private fun prepararCapitulo(html: String, i: Int): String {
        val (fundoCor, textoCor, linkCor) = TEMAS[tema()].let { Triple(it[0], it[1], it[2]) }
        val familia = if (prefs.getInt("fonte", 0) == 0) "Georgia, 'Noto Serif', serif" else "sans-serif"
        val estilo = """
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
            html,body{background:$fundoCor !important;color:$textoCor !important}
            body{font-family:$familia !important;line-height:1.65 !important;margin:0 !important;
                 padding:28px 20px 16px !important;word-wrap:break-word;text-align:justify;hyphens:auto}
            body *{color:inherit !important;background-color:transparent !important;font-family:inherit !important;max-width:100% !important}
            img,svg,image{max-width:100% !important;height:auto !important}
            a{color:$linkCor !important}
            h1,h2,h3{text-align:center;line-height:1.3}
            .__nav{display:flex;justify-content:space-between;align-items:center;margin:40px 0 56px;text-align:center}
            .__nav a{display:inline-block;padding:10px 18px;border:1px solid $linkCor;border-radius:22px;text-decoration:none}
            .__fim{width:100%;font-size:1.2em}
            </style>
        """.trimIndent()
        val anterior = if (i > 0) "<a href=\"leitor://anterior\">${getString(R.string.anterior)}</a>" else "<span></span>"
        val proximo = if (i < total - 1) "<a href=\"leitor://proximo\">${getString(R.string.proximo)}</a>"
        else "<div class=\"__fim\"><p>${getString(R.string.fim_livro)}</p><a href=\"leitor://lido\">${getString(R.string.marcar_como_lido)}</a></div>"
        val nav = "<div class=\"__nav\">$anterior$proximo</div>"

        var r = html
        val fimHead = r.indexOf("</head>", ignoreCase = true)
        r = if (fimHead >= 0) r.substring(0, fimHead) + estilo + r.substring(fimHead)
        else estilo + r
        val fimBody = r.lastIndexOf("</body>", ignoreCase = true)
        r = if (fimBody >= 0) r.substring(0, fimBody) + nav + r.substring(fimBody) else r + nav
        return r
    }

    private inner class Cliente : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val f = fonte ?: return null
            if (!url.startsWith(Fonte.BASE)) {
                // Nada de internet: o livro só usa o que tem dentro dele
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            val (bytes, mime) = try { f.abrir(Fonte.caminhoDe(url)) } catch (e: Exception) { null }
                ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            val i = f.indiceDe(url)
            if (mime == "text/html" && i >= 0) {
                val html = prepararCapitulo(String(bytes, Charsets.UTF_8), i)
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
            }
            return WebResourceResponse(mime, if (mime.startsWith("text/")) "utf-8" else null, ByteArrayInputStream(bytes))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            when {
                url.scheme == "leitor" -> when (url.host) {
                    "anterior" -> irPara(capitulo - 1, 0f)
                    "proximo" -> irPara(capitulo + 1, 0f)
                    "lido" -> {
                        livro.lido = true
                        livro.paraLer = false
                        Biblioteca.salvar(this@LeitorActivity)
                        Toast.makeText(this@LeitorActivity, R.string.marcar_lido, Toast.LENGTH_SHORT).show()
                    }
                }
                url.toString().startsWith(Fonte.BASE) -> {
                    val i = fonte?.indiceDe(url.toString()) ?: -1
                    if (i >= 0 && i != capitulo) {
                        capitulo = i
                        fracao = 0f
                    }
                    return false
                }
                else -> try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: Exception) {
                }
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            val i = fonte?.indiceDe(url) ?: -1
            if (i >= 0) capitulo = i
        }

        override fun onPageFinished(view: WebView, url: String) {
            val fr = fracaoPendente ?: return
            fracaoPendente = null
            // Espera o texto ter altura para voltar ao ponto onde parou
            fun tentar(vezes: Int) {
                val altura = view.contentHeight * resources.displayMetrics.density - view.height
                if (altura <= 0 && vezes > 0) {
                    principal.postDelayed({ tentar(vezes - 1) }, 80)
                    return
                }
                view.scrollTo(0, (fr * altura.coerceAtLeast(0f)).toInt())
                fracao = fr
                atualizarBarra()
            }
            tentar(15)
        }
    }

    // ---------- Sumário e aparência ----------

    private fun mostrarSumario() {
        val f = fonte ?: return
        val itens = f.sumario
        if (itens.isEmpty()) return
        val atual = itens.indexOfLast { it.second <= capitulo }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.sumario)
            .setSingleChoiceItems(itens.map { it.first }.toTypedArray(), atual) { d, qual ->
                d.dismiss()
                irPara(itens[qual].second, 0f)
            }
            .setNegativeButton(R.string.fechar, null)
            .show()
    }

    private fun mostrarAparencia() {
        val w = web ?: return
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun rotulo(texto: Int) = TextView(this).apply {
            setText(texto)
            textSize = 13f
            setPadding(0, dp(12), 0, dp(6))
        }
        fun linha(vararg botoes: Pair<String, () -> Unit>) = LinearLayout(this).apply {
            for ((texto, acao) in botoes) {
                addView(Button(this@LeitorActivity).apply {
                    text = texto
                    isAllCaps = false
                    setOnClickListener { acao() }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        val tamanho = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            text = "${w.settings.textZoom}%"
        }
        fun mudarLetra(delta: Int) {
            val novo = (w.settings.textZoom + delta).coerceIn(60, 250)
            w.settings.textZoom = novo
            prefs.edit().putInt("letra", novo).apply()
            tamanho.text = "$novo%"
        }
        fun recarregar() {
            medirFracao()
            w.setBackgroundColor(android.graphics.Color.parseColor(TEMAS[tema()][0]))
            irPara(capitulo, fracao)
        }

        caixa.addView(rotulo(R.string.tamanho_letra))
        val linhaTamanho = linha("A−" to { mudarLetra(-10) }, "A+" to { mudarLetra(10) })
        linhaTamanho.addView(tamanho, 1, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        caixa.addView(linhaTamanho)
        caixa.addView(rotulo(R.string.tema))
        caixa.addView(linha(
            getString(R.string.claro) to { prefs.edit().putInt("tema", 0).apply(); recarregar() },
            getString(R.string.sepia) to { prefs.edit().putInt("tema", 1).apply(); recarregar() },
            getString(R.string.escuro) to { prefs.edit().putInt("tema", 2).apply(); recarregar() },
        ))
        caixa.addView(rotulo(R.string.fonte))
        caixa.addView(linha(
            getString(R.string.serifa) to { prefs.edit().putInt("fonte", 0).apply(); recarregar() },
            getString(R.string.sem_serifa) to { prefs.edit().putInt("fonte", 1).apply(); recarregar() },
        ))
        AlertDialog.Builder(this)
            .setTitle(R.string.aparencia)
            .setView(caixa)
            .setPositiveButton(R.string.fechar, null)
            .show()
    }
}
