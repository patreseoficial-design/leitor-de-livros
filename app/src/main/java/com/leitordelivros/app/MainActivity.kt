package com.leitordelivros.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val PEDIDO_ABRIR = 1
        const val PEDIDO_PERMISSAO = 2
        const val LENDO = 0
        const val TODOS = 1
        const val FAVORITOS = 2
        const val PARA_LER = 3
        const val LIDOS = 4
    }

    private lateinit var lista: ListView
    private lateinit var chips: LinearLayout
    private lateinit var busca: EditText
    private lateinit var vazioTexto: TextView
    private lateinit var btnPermitir: Button

    private var filtro = -1
    private var mostrados: List<Livro> = emptyList()
    private var procurando = false
    private val adaptador = Adaptador()
    private val fundo = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val capas = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        telaInteira(findViewById(R.id.raiz))

        lista = findViewById(R.id.lista)
        chips = findViewById(R.id.chips)
        busca = findViewById(R.id.busca)
        vazioTexto = findViewById(R.id.vazioTexto)
        btnPermitir = findViewById(R.id.btnPermitir)

        lista.adapter = adaptador
        lista.emptyView = findViewById(R.id.vazio)
        lista.setOnItemClickListener { _, _, pos, _ -> ler(mostrados[pos]) }

        busca.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = filtrar()
        })
        btnPermitir.setOnClickListener { pedirPermissao() }
        findViewById<View>(R.id.btnAbrir).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/epub+zip", "application/x-fictionbook+xml", "application/pdf", "text/plain",
                    "application/octet-stream"
                ))
            startActivityForResult(i, PEDIDO_ABRIR)
        }
    }

    override fun onResume() {
        super.onResume()
        if (filtro < 0) {
            // Abre em "Lendo agora" quando tem algum livro começado
            filtro = if (Biblioteca.todos(this).values.any { it.progresso > 0f && !it.lido }) LENDO else TODOS
        }
        montarChips()
        filtrar()
        if (temPermissao()) procurar()
    }

    private fun temPermissao(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun pedirPermissao() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), PEDIDO_PERMISSAO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (temPermissao()) procurar()
    }

    private fun procurar() {
        if (procurando) return
        procurando = true
        filtrar()
        fundo.execute {
            try { Biblioteca.procurar(this) } catch (e: Exception) { }
            principal.post { filtrar() }
            // Depois lê título, autor e capa de cada livro novo
            try {
                Biblioteca.lerMetadados(this) { principal.post { capas.evictAll(); filtrar() } }
            } catch (e: Exception) {
            }
            principal.post {
                procurando = false
                filtrar()
            }
        }
    }

    private fun montarChips() {
        chips.removeAllViews()
        val opcoes = listOf(
            LENDO to getString(R.string.lendo_agora),
            TODOS to getString(R.string.livros),
            FAVORITOS to getString(R.string.favoritos),
            PARA_LER to getString(R.string.para_ler),
            LIDOS to getString(R.string.lidos),
        )
        for ((valor, nome) in opcoes) {
            val sel = valor == filtro
            chips.addView(TextView(this).apply {
                text = nome
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = dp(36)
                setPadding(dp(16), 0, dp(16), 0)
                setBackgroundResource(if (sel) R.drawable.chip_selecionado else R.drawable.chip)
                setTextColor(getColor(if (sel) R.color.fundo else R.color.texto))
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
        val todos = synchronized(Biblioteca) { Biblioteca.todos(this).values.toList() }
        var base = when (filtro) {
            LENDO -> todos.filter { it.progresso > 0f && !it.lido }.sortedByDescending { it.ultimaLeitura }
            FAVORITOS -> todos.filter { it.favorito }
            PARA_LER -> todos.filter { it.paraLer }
            LIDOS -> todos.filter { it.lido }
            else -> todos
        }
        if (filtro != LENDO) base = base.sortedBy { it.titulo.lowercase(Locale.getDefault()) }
        val termo = busca.text.toString().trim()
        mostrados = if (termo.isEmpty()) base
        else base.filter { it.titulo.contains(termo, true) || it.autor.contains(termo, true) }
        adaptador.notifyDataSetChanged()

        val precisaPermissao = !temPermissao() && todos.isEmpty()
        btnPermitir.visibility = if (precisaPermissao) View.VISIBLE else View.GONE
        vazioTexto.setText(
            when {
                procurando && todos.isEmpty() -> R.string.procurando
                precisaPermissao -> R.string.permissao_texto
                else -> R.string.nenhum
            }
        )
    }

    private fun ler(livro: Livro) {
        startActivity(Intent(this, LeitorActivity::class.java).setData(livro.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == PEDIDO_ABRIR && resultCode == RESULT_OK && uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            val livro = Biblioteca.registrar(this, uri, contentResolver.getType(uri))
            // Título, autor e capa do livro novo
            fundo.execute {
                try { Biblioteca.lerMetadados(this) { principal.post { filtrar() } } } catch (e: Exception) { }
            }
            ler(livro)
        }
    }

    private fun capa(livro: Livro): Bitmap? {
        if (livro.capa.isEmpty()) return null
        capas.get(livro.capa)?.let { return it }
        return BitmapFactory.decodeFile(livro.capa)?.also { capas.put(livro.capa, it) }
    }

    private inner class Adaptador : BaseAdapter() {
        override fun getCount() = mostrados.size
        override fun getItem(posicao: Int) = mostrados[posicao]
        override fun getItemId(posicao: Int) = posicao.toLong()

        override fun getView(posicao: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_livro, parent, false)
            val l = mostrados[posicao]

            val img = v.findViewById<ImageView>(R.id.capa)
            val bmp = capa(l)
            img.setImageBitmap(bmp)
            img.visibility = if (bmp != null) View.VISIBLE else View.GONE
            v.findViewById<TextView>(R.id.capaTexto).text = l.titulo

            v.findViewById<TextView>(R.id.tituloLivro).text = l.titulo
            v.findViewById<TextView>(R.id.autor).text = l.autor.ifBlank { getString(R.string.autor_desconhecido) }
            v.findViewById<TextView>(R.id.formato).text =
                listOf(l.formato.uppercase(), formatarTamanho(l.tamanho)).filter { it.isNotEmpty() }.joinToString(", ")
            v.findViewById<ProgressBar>(R.id.barraProgresso).progress = (l.progresso * 1000).toInt()
            v.findViewById<TextView>(R.id.porcentagem).text =
                if (l.progresso > 0f) getString(R.string.progresso, (l.progresso * 100).toInt()) else ""

            fun icone(id: Int, ligado: Boolean, acao: () -> Unit) {
                val b = v.findViewById<ImageButton>(id)
                b.imageTintList = getColorStateList(if (ligado) R.color.destaque else R.color.texto2)
                b.setOnClickListener {
                    acao()
                    Biblioteca.salvar(this@MainActivity)
                    filtrar()
                }
            }
            v.findViewById<ImageButton>(R.id.btnFav)
                .setImageResource(if (l.favorito) R.drawable.ic_estrela else R.drawable.ic_estrela_vazia)
            icone(R.id.btnFav, l.favorito) { l.favorito = !l.favorito }
            icone(R.id.btnParaLer, l.paraLer) { l.paraLer = !l.paraLer }
            icone(R.id.btnLido, l.lido) {
                l.lido = !l.lido
                if (l.lido) l.paraLer = false
            }
            v.findViewById<ImageButton>(R.id.btnMais).setOnClickListener { ancora ->
                val pm = PopupMenu(this@MainActivity, ancora)
                pm.menu.add(0, 1, 0, R.string.remover)
                pm.setOnMenuItemClickListener {
                    Biblioteca.remover(this@MainActivity, l)
                    filtrar()
                    true
                }
                pm.show()
            }
            return v
        }
    }
}
