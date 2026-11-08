package com.geanalves.tvonline

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Guia de programação (o que está passando agora e o próximo).
 * O GitHub monta um arquivo pequeno (listas/guia.json) a cada 6 horas;
 * aqui ele é baixado de vez em quando e consultado pelo tvg-id do canal.
 */
object Guia {
    data class Programa(val inicio: Long, val fim: Long, val titulo: String) {
        /** Quanto do programa já passou (0 a 1). */
        fun progresso(agora: Long = System.currentTimeMillis() / 1000) =
            ((agora - inicio).toFloat() / (fim - inicio).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    private const val LINK = "https://raw.githubusercontent.com/XProzzinho/TV-online/main/listas/guia.json"
    private const val VALIDADE = 3L * 60 * 60 * 1000

    private var dados: Map<String, List<Programa>> = emptyMap()
    private var carregando = false
    private val avisar = ArrayList<() -> Unit>()

    val pronto get() = dados.isNotEmpty()

    /** Carrega o guia (cópia salva na hora; baixa de novo se estiver velha). [cb] roda quando tiver dados. */
    fun carregar(ctx: Context, cb: (() -> Unit)? = null) {
        if (cb != null) avisar += cb
        if (carregando) return
        val arq = File(ctx.applicationContext.filesDir, "guia.json")
        val velho = !arq.exists() || System.currentTimeMillis() - arq.lastModified() > VALIDADE
        if (pronto && !velho) {
            terminar()
            return
        }
        carregando = true
        Iptv.pool.execute {
            if (!pronto && arq.exists()) ler(arq)?.let { d -> Iptv.main.post { dados = d } }
            if (velho) {
                try {
                    val c = URL(LINK).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 20000
                    c.setRequestProperty("User-Agent", "LumiTV")
                    if (c.responseCode == 200) {
                        val tmp = File(arq.parentFile, "guia.tmp")
                        c.inputStream.use { e -> tmp.outputStream().use { e.copyTo(it) } }
                        tmp.renameTo(arq)
                        ler(arq)?.let { d -> Iptv.main.post { dados = d } }
                    }
                    c.disconnect()
                } catch (_: Throwable) {
                }
            }
            Iptv.main.post {
                carregando = false
                terminar()
            }
        }
    }

    private fun terminar() {
        val l = avisar.toList()
        avisar.clear()
        l.forEach { it() }
    }

    private fun ler(arq: File): Map<String, List<Programa>>? = try {
        val js = JSONObject(arq.readText())
        val out = HashMap<String, List<Programa>>(js.length() * 2)
        val it = js.keys()
        while (it.hasNext()) {
            val k = it.next()
            val a = js.getJSONArray(k)
            out[k] = List(a.length()) { i ->
                val p = a.getJSONArray(i)
                Programa(p.getLong(0), p.getLong(1), p.getString(2))
            }
        }
        out
    } catch (_: Throwable) {
        null
    }

    /** O programa de agora e o próximo do canal (ou nulls se o guia não tem o canal). */
    fun agora(c: Iptv.Canal): Pair<Programa?, Programa?> {
        if (c.id.isBlank()) return null to null
        val l = dados[c.id] ?: return null to null
        val t = System.currentTimeMillis() / 1000
        val i = l.indexOfFirst { t >= it.inicio && t < it.fim }
        if (i < 0) return null to l.firstOrNull { it.inicio > t }
        return l[i] to l.getOrNull(i + 1)
    }

    private val horaFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
    fun hora(seg: Long): String = horaFmt.format(java.util.Date(seg * 1000))
}
