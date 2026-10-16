package com.leitordelivros.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.concurrent.Executors

/** Estado da leitura em voz alta, visto pela tela do player. */
object Ouvindo {
    var chave: String? = null
    var titulo = ""
    var capitulo = 0
    var capitulos = 0
    var paragrafo = 0
    var paragrafos: List<String> = emptyList()
    var sumario: List<Pair<String, Int>> = emptyList()
    var tocando = false
    var carregando = false
    var velocidade = 1f
    /** Mensagem de erro (string) para a tela mostrar, ou 0 */
    var erro = 0
    val ouvintes = LinkedHashSet<() -> Unit>()

    val rotuloCapitulo: String get() = sumario.lastOrNull { it.second <= capitulo }?.first ?: ""

    /** Caracteres por segundo de uma voz normal, para estimar o tempo */
    private const val CARACTERES_POR_SEGUNDO = 15f

    fun segundos(ate: Int): Long =
        (paragrafos.take(ate.coerceIn(0, paragrafos.size)).sumOf { it.length + 1 } / CARACTERES_POR_SEGUNDO / velocidade).toLong()

    fun avisar() = ouvintes.toList().forEach { it() }
}

/** Lê o livro em voz alta (Text-to-Speech do celular), mesmo com a tela desligada. */
class ServicoOuvir : Service() {

    companion object {
        const val ACAO_TOCAR = "tocar"         // EXTRA_CHAVE: começa (ou continua) este livro
        const val ACAO_ALTERNAR = "alternar"
        const val ACAO_PAUSAR = "pausar"
        const val ACAO_ANTERIOR = "anterior"
        const val ACAO_SEGUINTE = "seguinte"
        const val ACAO_IR = "ir"               // EXTRA_CAPITULO e EXTRA_PARAGRAFO
        const val ACAO_VELOCIDADE = "velocidade"
        const val ACAO_PARAR = "parar"
        const val EXTRA_CHAVE = "chave"
        const val EXTRA_CAPITULO = "capitulo"
        const val EXTRA_PARAGRAFO = "paragrafo"
        const val EXTRA_VELOCIDADE = "velocidade"
        private const val CANAL = "ouvir"
        private const val NOTIFICACAO = 7

        fun enviar(ctx: Context, acao: String, extras: Intent.() -> Unit = {}) {
            val i = Intent(ctx, ServicoOuvir::class.java).setAction(acao).apply(extras)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    private val principal = Handler(Looper.getMainLooper())
    private val fundo = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var vozPronta = false
    private var livro: Livro? = null
    private var fonte: Fonte? = null
    private var falando = -1
    private var focoPedido: AudioFocusRequest? = null
    private var emPrimeiroPlano = false
    private val prefs by lazy { getSharedPreferences("ouvir", MODE_PRIVATE) }

    private val fone = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = pausar() // tirou o fone de ouvido
    }

    private val foco = AudioManager.OnAudioFocusChangeListener { mudanca ->
        if (mudanca == AudioManager.AUDIOFOCUS_LOSS || mudanca == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausar()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Ouvindo.velocidade = prefs.getFloat("velocidade", 1f)
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CANAL, getString(R.string.canal_ouvir), NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
        val filtro = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(fone, filtro, RECEIVER_NOT_EXPORTED)
        else registerReceiver(fone, filtro)
        tts = TextToSpeech(this) { status ->
            principal.post {
                if (status == TextToSpeech.SUCCESS) {
                    vozPronta = true
                    tts?.setOnUtteranceProgressListener(Progresso())
                    if (Ouvindo.tocando) falar()
                } else {
                    Ouvindo.erro = R.string.sem_voz
                    Ouvindo.tocando = false
                    Ouvindo.avisar()
                    parar()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Precisa ficar em primeiro plano logo que é iniciado
        primeiroPlano()
        when (intent?.action) {
            ACAO_TOCAR -> intent.getStringExtra(EXTRA_CHAVE)?.let { comecar(it) }
            ACAO_ALTERNAR -> if (Ouvindo.tocando) pausar() else tocar()
            ACAO_PAUSAR -> pausar()
            ACAO_ANTERIOR -> ir(Ouvindo.capitulo, Ouvindo.paragrafo - 1)
            ACAO_SEGUINTE -> ir(Ouvindo.capitulo, Ouvindo.paragrafo + 1)
            ACAO_IR -> ir(intent.getIntExtra(EXTRA_CAPITULO, Ouvindo.capitulo), intent.getIntExtra(EXTRA_PARAGRAFO, 0))
            ACAO_VELOCIDADE -> {
                Ouvindo.velocidade = intent.getFloatExtra(EXTRA_VELOCIDADE, 1f)
                prefs.edit().putFloat("velocidade", Ouvindo.velocidade).apply()
                if (Ouvindo.tocando) falar()
                Ouvindo.avisar()
            }
            ACAO_PARAR -> parar()
            else -> if (livro == null) parar()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        guardar()
        Ouvindo.tocando = false
        Ouvindo.avisar()
        try { unregisterReceiver(fone) } catch (e: Exception) { }
        largarFoco()
        tts?.stop()
        tts?.shutdown()
        val f = fonte
        fundo.execute { try { f?.fechar() } catch (e: Exception) { } }
        fundo.shutdown()
        super.onDestroy()
    }

    // ---------- Livro e capítulos ----------

    private fun comecar(chave: String) {
        if (chave == Ouvindo.chave && fonte != null) {
            tocar()
            return
        }
        guardar()
        val l = Biblioteca.buscar(this, chave) ?: run { parar(); return }
        if (l.formato == "pdf") {
            Ouvindo.erro = R.string.pdf_sem_audio
            Ouvindo.avisar()
            parar()
            return
        }
        tts?.stop()
        livro = l
        Ouvindo.chave = chave
        Ouvindo.titulo = l.titulo
        Ouvindo.capitulo = l.capitulo
        Ouvindo.paragrafos = emptyList()
        Ouvindo.paragrafo = 0
        Ouvindo.sumario = emptyList()
        Ouvindo.carregando = true
        Ouvindo.tocando = true
        Ouvindo.erro = 0
        Ouvindo.avisar()
        val antiga = fonte
        fonte = null
        fundo.execute {
            try { antiga?.fechar() } catch (e: Exception) { }
            val f = try {
                val arq = Biblioteca.arquivoLocal(this, l.uri, l.formato)
                when (l.formato) {
                    "epub" -> Epub(arq, getString(R.string.capitulo))
                    "fb2" -> Fb2.fonte(arq, getString(R.string.capitulo))
                    else -> Txt.fonte(arq, getString(R.string.parte))
                }
            } catch (e: Throwable) {
                null
            }
            principal.post {
                if (livro !== l) { fundo.execute { try { f?.fechar() } catch (e: Exception) { } }; return@post }
                if (f == null) {
                    Ouvindo.erro = R.string.erro_abrir
                    Ouvindo.carregando = false
                    Ouvindo.avisar()
                    parar()
                    return@post
                }
                fonte = f
                Ouvindo.capitulos = f.capitulos
                Ouvindo.sumario = f.sumario
                carregarCapitulo(l.capitulo.coerceIn(0, f.capitulos - 1), fracao = l.posicao)
            }
        }
    }

    /** Lê os parágrafos do capítulo; pula capítulos sem texto (capa, imagens). */
    private fun carregarCapitulo(cap: Int, paragrafo: Int = 0, fracao: Float? = null, paraTras: Boolean = false) {
        val f = fonte ?: return
        Ouvindo.carregando = true
        Ouvindo.avisar()
        fundo.execute {
            var c = cap
            var lista: List<String> = emptyList()
            while (c in 0 until f.capitulos) {
                lista = try {
                    f.abrir(Fonte.caminhoDe(f.urlCapitulo(c)))?.let { Paragrafos.de(String(it.first, Charsets.UTF_8)) }
                } catch (e: Exception) {
                    null
                } ?: emptyList()
                if (lista.isNotEmpty()) break
                c += if (paraTras) -1 else 1
            }
            principal.post {
                if (fonte !== f) return@post
                Ouvindo.carregando = false
                if (lista.isEmpty()) {
                    // Acabou o livro
                    Ouvindo.tocando = false
                    Ouvindo.paragrafo = Ouvindo.paragrafos.size
                    tts?.stop()
                    if (!paraTras) livro?.let { it.lido = true; it.paraLer = false }
                    guardar()
                    notificar()
                    Ouvindo.avisar()
                    return@post
                }
                Ouvindo.capitulo = c
                Ouvindo.paragrafos = lista
                Ouvindo.paragrafo = when {
                    fracao != null -> (fracao * lista.size).toInt()
                    paragrafo < 0 -> lista.size - 1
                    else -> paragrafo
                }.coerceIn(0, lista.size - 1)
                if (Ouvindo.tocando) falar() else tts?.stop()
                guardar()
                notificar()
                Ouvindo.avisar()
            }
        }
    }

    private fun ir(cap: Int, paragrafo: Int) {
        if (fonte == null) return
        when {
            cap != Ouvindo.capitulo -> carregarCapitulo(cap, paragrafo)
            paragrafo < 0 -> if (cap > 0) carregarCapitulo(cap - 1, -1, paraTras = true)
            paragrafo >= Ouvindo.paragrafos.size -> carregarCapitulo(cap + 1)
            else -> {
                Ouvindo.paragrafo = paragrafo
                if (Ouvindo.tocando) falar()
                guardar()
                Ouvindo.avisar()
            }
        }
    }

    // ---------- Voz ----------

    private fun tocar() {
        if (livro == null) return
        Ouvindo.tocando = true
        if (!Ouvindo.carregando) {
            // Terminou o livro: recomeça do início
            if (Ouvindo.paragrafo >= Ouvindo.paragrafos.size) { carregarCapitulo(0); return }
            falar()
        }
        notificar()
        Ouvindo.avisar()
    }

    private fun pausar() {
        if (!Ouvindo.tocando) return
        Ouvindo.tocando = false
        falando = -1
        tts?.stop()
        largarFoco()
        guardar()
        notificar()
        Ouvindo.avisar()
    }

    private fun falar() {
        val t = tts ?: return
        if (!vozPronta || Ouvindo.carregando) return
        val texto = Ouvindo.paragrafos.getOrNull(Ouvindo.paragrafo) ?: return
        if (!pedirFoco()) { pausar(); return }
        t.setSpeechRate(Ouvindo.velocidade)
        falando = Ouvindo.paragrafo
        t.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "${Ouvindo.capitulo}:${Ouvindo.paragrafo}")
    }

    private inner class Progresso : UtteranceProgressListener() {
        override fun onStart(id: String?) {}
        override fun onDone(id: String?) {
            principal.post {
                // Ignora o fim de uma fala antiga (depois de pular ou pausar)
                if (!Ouvindo.tocando || id != "${Ouvindo.capitulo}:$falando") return@post
                ir(Ouvindo.capitulo, Ouvindo.paragrafo + 1)
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = onDone(id)
    }

    private fun pedirFoco(): Boolean {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val atributos = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= 26) {
            val pedido = focoPedido ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(atributos)
                .setOnAudioFocusChangeListener(foco, principal)
                .build().also { focoPedido = it }
            am.requestAudioFocus(pedido) != AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(foco, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) !=
                AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }

    private fun largarFoco() {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) focoPedido?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(foco)
    }

    /** Guarda onde parou: o leitor abre no mesmo ponto. */
    private fun guardar() {
        val l = livro ?: return
        val total = Ouvindo.capitulos
        if (total <= 0 || Ouvindo.paragrafos.isEmpty()) return
        val fr = (Ouvindo.paragrafo.toFloat() / Ouvindo.paragrafos.size).coerceIn(0f, 1f)
        l.capitulo = Ouvindo.capitulo
        l.posicao = fr
        l.progresso = ((Ouvindo.capitulo + fr) / total).coerceIn(0f, 1f)
        l.ultimaLeitura = System.currentTimeMillis()
        l.rotulo = Ouvindo.rotuloCapitulo
        Biblioteca.salvar(this)
    }

    private fun parar() {
        Ouvindo.tocando = false
        if (emPrimeiroPlano) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            emPrimeiroPlano = false
        }
        stopSelf()
    }

    // ---------- Notificação ----------

    private fun acao(acao: String, icone: Int, texto: Int): Notification.Action {
        val pi = PendingIntent.getService(this, acao.hashCode(), Intent(this, ServicoOuvir::class.java).setAction(acao),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(icone, getString(texto), pi).build()
    }

    private fun criarNotificacao(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val abrir = PendingIntent.getActivity(this, 0,
            Intent(this, OuvirActivity::class.java).putExtra(OuvirActivity.EXTRA_CHAVE, Ouvindo.chave)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        b.setSmallIcon(R.drawable.ic_fones)
            .setContentTitle(Ouvindo.titulo.ifEmpty { getString(R.string.ouvindo) })
            .setContentText(Ouvindo.rotuloCapitulo)
            .setContentIntent(abrir)
            .setOngoing(Ouvindo.tocando)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(PendingIntent.getService(this, 99, Intent(this, ServicoOuvir::class.java).setAction(ACAO_PARAR),
                PendingIntent.FLAG_IMMUTABLE))
            .addAction(acao(ACAO_ANTERIOR, R.drawable.ic_anterior, R.string.paragrafo_anterior))
            .addAction(if (Ouvindo.tocando) acao(ACAO_ALTERNAR, R.drawable.ic_pause, R.string.pausar)
                else acao(ACAO_ALTERNAR, R.drawable.ic_play, R.string.tocar))
            .addAction(acao(ACAO_SEGUINTE, R.drawable.ic_seguinte, R.string.proximo_paragrafo))
            .addAction(acao(ACAO_PARAR, R.drawable.ic_fechar, R.string.fechar))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        livro?.capa?.takeIf { it.isNotEmpty() }?.let { caminho ->
            try { BitmapFactory.decodeFile(caminho)?.let { b.setLargeIcon(it) } } catch (e: Exception) { }
        }
        return b.build()
    }

    private fun primeiroPlano() {
        val n = criarNotificacao()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICACAO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICACAO, n)
            emPrimeiroPlano = true
        } catch (e: Exception) {
            // Android 12+ pode recusar vindo do segundo plano: só atualiza a notificação
            notificar()
        }
    }

    private fun notificar() {
        if (!emPrimeiroPlano) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICACAO, criarNotificacao())
        } catch (e: Exception) {
        }
    }
}
