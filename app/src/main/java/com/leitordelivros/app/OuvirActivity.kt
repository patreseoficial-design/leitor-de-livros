package com.leitordelivros.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/** Player para ouvir o livro, no estilo dos apps de audiolivro. */
class OuvirActivity : Activity() {

    companion object {
        const val EXTRA_CHAVE = "chave"
        private val VELOCIDADES = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    }

    private lateinit var livro: Livro
    private lateinit var capitulo: TextView
    private lateinit var seek: SeekBar
    private lateinit var decorrido: TextView
    private lateinit var restante: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnVelocidade: TextView
    private var arrastando = false
    private val ouvinte: () -> Unit = { atualizar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chave = intent.getStringExtra(EXTRA_CHAVE) ?: Ouvindo.chave
        val l = chave?.let { Biblioteca.buscar(this, it) }
        if (l == null) { finish(); return }
        if (l.formato == "pdf") {
            Toast.makeText(this, R.string.pdf_sem_audio, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        livro = l
        setContentView(R.layout.activity_ouvir)
        telaInteira(findViewById(R.id.raiz))

        capitulo = findViewById(R.id.capituloOuvir)
        seek = findViewById(R.id.seekOuvir)
        decorrido = findViewById(R.id.decorrido)
        restante = findViewById(R.id.restante)
        btnPlay = findViewById(R.id.btnPlayOuvir)
        btnVelocidade = findViewById(R.id.btnVelocidadeOuvir)

        val capa = l.capa.takeIf { it.isNotEmpty() }?.let { try { BitmapFactory.decodeFile(it) } catch (e: Exception) { null } }
        findViewById<CapaView>(R.id.capaOuvir).mostrar(capa, l.titulo, l.autor)
        if (capa != null) {
            // Desfoque barato: reduz muito e deixa o ImageView esticar com filtro
            val pequena = Bitmap.createScaledBitmap(capa, 12, (12f * capa.height / capa.width).toInt().coerceAtLeast(1), true)
            findViewById<ImageView>(R.id.fundoCapa).setImageBitmap(pequena)
        }

        findViewById<View>(R.id.btnFecharOuvir).setOnClickListener { finish() }
        findViewById<View>(R.id.btnLerTexto).setOnClickListener {
            ServicoOuvir.enviar(this, ServicoOuvir.ACAO_PAUSAR)
            startActivity(Intent(this, LeitorActivity::class.java).putExtra(LeitorActivity.EXTRA_CHAVE, l.chave))
            finish()
        }
        btnPlay.setOnClickListener {
            if (Ouvindo.chave == l.chave) ServicoOuvir.enviar(this, ServicoOuvir.ACAO_ALTERNAR) else tocar()
        }
        findViewById<View>(R.id.btnAnteriorOuvir).setOnClickListener { ServicoOuvir.enviar(this, ServicoOuvir.ACAO_ANTERIOR) }
        findViewById<View>(R.id.btnSeguinteOuvir).setOnClickListener { ServicoOuvir.enviar(this, ServicoOuvir.ACAO_SEGUINTE) }
        findViewById<View>(R.id.btnSumarioOuvir).setOnClickListener { mostrarSumario() }
        btnVelocidade.setOnClickListener {
            val i = VELOCIDADES.indexOfFirst { it >= Ouvindo.velocidade - 0.01f }
            val nova = VELOCIDADES[(i + 1) % VELOCIDADES.size]
            ServicoOuvir.enviar(this, ServicoOuvir.ACAO_VELOCIDADE) { putExtra(ServicoOuvir.EXTRA_VELOCIDADE, nova) }
            Ouvindo.velocidade = nova
            atualizar()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, valor: Int, doUsuario: Boolean) {
                if (doUsuario) decorrido.text = tempo(Ouvindo.segundos(valor))
            }
            override fun onStartTrackingTouch(s: SeekBar) { arrastando = true }
            override fun onStopTrackingTouch(s: SeekBar) {
                arrastando = false
                ServicoOuvir.enviar(this@OuvirActivity, ServicoOuvir.ACAO_IR) {
                    putExtra(ServicoOuvir.EXTRA_CAPITULO, Ouvindo.capitulo)
                    putExtra(ServicoOuvir.EXTRA_PARAGRAFO, s.progress)
                }
            }
        })

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Abriu o player: começa a ouvir (ou continua, se já era este livro)
        if (savedInstanceState == null && !(Ouvindo.chave == l.chave && Ouvindo.tocando)) tocar()
    }

    override fun onStart() {
        super.onStart()
        if (::livro.isInitialized) {
            Ouvindo.ouvintes += ouvinte
            atualizar()
        }
    }

    override fun onStop() {
        Ouvindo.ouvintes -= ouvinte
        super.onStop()
    }

    private fun tocar() {
        Ouvindo.erro = 0
        ServicoOuvir.enviar(this, ServicoOuvir.ACAO_TOCAR) { putExtra(ServicoOuvir.EXTRA_CHAVE, livro.chave) }
    }

    private fun tempo(segundos: Long): String {
        val h = segundos / 3600
        val m = segundos / 60 % 60
        val s = segundos % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    private fun atualizar() {
        if (Ouvindo.erro != 0) {
            val msg = Ouvindo.erro
            Ouvindo.erro = 0
            AlertDialog.Builder(this).setMessage(msg).setPositiveButton(R.string.fechar, null).show()
        }
        val meu = Ouvindo.chave == livro.chave
        val tocando = meu && Ouvindo.tocando
        btnPlay.setImageResource(if (tocando) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (tocando) R.string.pausar else R.string.tocar)
        btnVelocidade.text = String.format(Locale.ROOT, "%sx",
            if (Ouvindo.velocidade % 1f == 0f) "%.1f".format(Locale.ROOT, Ouvindo.velocidade)
            else Ouvindo.velocidade.toString().trimEnd('0'))

        val rotulo = if (meu) Ouvindo.rotuloCapitulo else livro.rotulo
        capitulo.text = if (rotulo.isNotEmpty()) getString(R.string.capitulo_de, livro.titulo, rotulo) else livro.titulo
        if (!meu || Ouvindo.paragrafos.isEmpty()) {
            seek.max = 1
            seek.progress = 0
            decorrido.text = if (meu && Ouvindo.carregando) getString(R.string.abrindo) else ""
            restante.text = ""
            return
        }
        val n = Ouvindo.paragrafos.size
        seek.max = (n - 1).coerceAtLeast(1)
        if (!arrastando) {
            seek.progress = Ouvindo.paragrafo
            decorrido.text = tempo(Ouvindo.segundos(Ouvindo.paragrafo))
        }
        restante.text = getString(R.string.restante, tempo(Ouvindo.segundos(n) - Ouvindo.segundos(Ouvindo.paragrafo)))
    }

    private fun mostrarSumario() {
        val itens = Ouvindo.sumario
        if (Ouvindo.chave != livro.chave || itens.isEmpty()) return
        val atual = itens.indexOfLast { it.second <= Ouvindo.capitulo }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.sumario)
            .setSingleChoiceItems(itens.map { it.first }.toTypedArray(), atual) { d, qual ->
                d.dismiss()
                ServicoOuvir.enviar(this, ServicoOuvir.ACAO_IR) {
                    putExtra(ServicoOuvir.EXTRA_CAPITULO, itens[qual].second)
                    putExtra(ServicoOuvir.EXTRA_PARAGRAFO, 0)
                }
            }
            .setNegativeButton(R.string.fechar, null)
            .show()
    }
}
