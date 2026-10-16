package com.leitordelivros.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Entrega para outros apps (só leitura) os livros importados, ao compartilhar. */
class ProvedorLivros : ContentProvider() {

    companion object {
        fun uri(ctx: Context, livro: Livro): Uri? {
            val f = File(livro.uri.path ?: return null)
            if (livro.uri.scheme != "file" || !f.exists()) return null
            val nome = livro.titulo.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "livro" } + "." + livro.formato
            return Uri.Builder().scheme("content").authority(ctx.packageName + ".livros")
                .appendPath(f.name).appendPath(nome).build()
        }

        fun mime(formato: String) = when (formato) {
            "epub" -> "application/epub+zip"
            "fb2" -> "application/x-fictionbook+xml"
            "pdf" -> "application/pdf"
            else -> "text/plain"
        }
    }

    private fun arquivo(uri: Uri): File {
        val nome = uri.pathSegments.firstOrNull() ?: throw FileNotFoundException()
        val pasta = File(context!!.filesDir, "livros")
        val f = File(pasta, nome)
        if (f.parentFile != pasta || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor {
        val f = arquivo(uri)
        val colunas = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(colunas)
        c.addRow(colunas.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> uri.pathSegments.getOrNull(1) ?: f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun getType(uri: Uri): String = mime(uri.lastPathSegment?.substringAfterLast('.') ?: "")

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(arquivo(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
