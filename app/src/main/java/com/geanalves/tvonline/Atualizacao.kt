package com.geanalves.tvonline

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Confere no GitHub se tem versão nova do TV Online.
 * Quem instalou pela Play Store recebe as atualizações pela própria loja (regra do Google).
 */
object Atualizacao {
    private const val API = "https://api.github.com/repos/XProzzinho/TV-online/releases/latest"
    private const val INTERVALO = 6L * 60 * 60 * 1000 // confere no máximo a cada 6 horas

    data class Versao(val numero: Int, val nome: String, val link: String)

    private val main = Handler(Looper.getMainLooper())

    fun daPlayStore(ctx: Context): Boolean = try {
        val inst = if (Build.VERSION.SDK_INT >= 30) {
            ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            ctx.packageManager.getInstallerPackageName(ctx.packageName)
        }
        inst == "com.android.vending"
    } catch (_: Throwable) {
        false
    }

    /** Chama [novaVersao] só quando tiver uma versão maior que a instalada. */
    fun conferir(ctx: Context, forcar: Boolean = false, novaVersao: (Versao) -> Unit) {
        if (daPlayStore(ctx)) return
        val sp = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        if (!forcar && agora - sp.getLong("atualizacao_conferida", 0) < INTERVALO) return
        sp.edit().putLong("atualizacao_conferida", agora).apply()
        Thread {
            try {
                val c = URL(API).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                val tag = js.optString("tag_name") // ex.: v1.31
                val numero = tag.substringAfterLast('.').toIntOrNull() ?: return@Thread
                val assets = js.optJSONArray("assets") ?: return@Thread
                var link: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) link = a.optString("browser_download_url")
                }
                if (link == null || numero <= BuildConfig.VERSION_CODE) return@Thread
                // "Depois": não insiste na mesma versão no mesmo dia
                val adiada = sp.getInt("atualizacao_adiada", 0)
                val quando = sp.getLong("atualizacao_adiada_em", 0)
                if (!forcar && adiada == numero && agora - quando < 24L * 60 * 60 * 1000) return@Thread
                val v = Versao(numero, js.optString("name").ifBlank { tag }, link)
                main.post { novaVersao(v) }
            } catch (_: Throwable) {
            }
        }.start()
    }

    fun adiar(ctx: Context, v: Versao) {
        ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE).edit()
            .putInt("atualizacao_adiada", v.numero)
            .putLong("atualizacao_adiada_em", System.currentTimeMillis())
            .apply()
    }

    fun arquivo(ctx: Context): File = File(ctx.cacheDir, "atualizacao.apk")

    /** Baixa o APK novo; [progresso] recebe de 0 a 100. */
    fun baixar(ctx: Context, v: Versao, progresso: (Int) -> Unit, fim: (File?) -> Unit) {
        Thread {
            val destino = arquivo(ctx)
            try {
                var url = URL(v.link)
                var c: HttpURLConnection
                var pulos = 0
                while (true) {
                    c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.connectTimeout = 15000
                    c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399 && pulos < 8) {
                        url = URL(url, c.getHeaderField("Location"))
                        c.disconnect()
                        pulos++
                        continue
                    }
                    if (code !in 200..299) throw java.io.IOException("erro $code")
                    break
                }
                val total = c.contentLengthLong
                var lido = 0L
                var ultimo = -1
                c.inputStream.use { ent ->
                    destino.outputStream().use { sai ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ent.read(buf)
                            if (n < 0) break
                            sai.write(buf, 0, n)
                            lido += n
                            if (total > 0) {
                                val pct = (lido * 100 / total).toInt()
                                if (pct != ultimo) {
                                    ultimo = pct
                                    main.post { progresso(pct) }
                                }
                            }
                        }
                    }
                }
                main.post { fim(destino) }
            } catch (_: Throwable) {
                main.post { fim(null) }
            }
        }.start()
    }

    /** Abre o instalador da TV com o APK baixado. */
    fun instalar(ctx: Context): Boolean {
        val uri = Uri.parse("content://${ctx.packageName}.apk/atualizacao.apk")
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
    }
}

/** Entrega o APK baixado para o instalador da TV (só leitura, só esse arquivo). */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = Atualizacao.arquivo(context ?: return null)
        if (uri.lastPathSegment != f.name || !f.exists()) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
