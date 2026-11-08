package com.geanalves.tvonline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Coisas que o Lumi TV lembra sozinho:
 * - onde você parou em cada filme/episódio (continuar assistindo)
 * - os últimos canais vistos (histórico) e quantas vezes cada um foi aberto (mais vistos)
 * - canais que não abriram (ficam escondidos por 24 horas)
 * - listas guardadas (várias listas)
 */
object Historico {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    private fun obj(ctx: Context, chave: String) = try {
        JSONObject(sp(ctx).getString(chave, "{}")!!)
    } catch (_: Throwable) {
        JSONObject()
    }

    private fun salvar(ctx: Context, chave: String, o: Any) = sp(ctx).edit().putString(chave, o.toString()).apply()

    // ---------- continuar assistindo ----------

    /** Guarda onde parou ([pos] e [dur] em ms). Quase no fim = terminou (apaga). */
    fun guardarPosicao(ctx: Context, c: Iptv.Canal, pos: Long, dur: Long) {
        val o = obj(ctx, "continuar")
        if (dur > 0 && (pos < 30_000 || pos > dur - 90_000)) {
            o.remove(c.url)
        } else if (pos >= 30_000) {
            o.put(c.url, JSONObject().put("p", pos).put("d", dur).put("n", c.nome).put("l", c.logo ?: "").put("g", c.grupo).put("t", System.currentTimeMillis()))
        }
        // Guarda só os 30 mais recentes
        if (o.length() > 30) {
            val velhos = o.keys().asSequence().toList().sortedBy { o.getJSONObject(it).optLong("t") }
            velhos.take(o.length() - 30).forEach { o.remove(it) }
        }
        salvar(ctx, "continuar", o)
    }

    fun posicao(ctx: Context, url: String): Long = obj(ctx, "continuar").optJSONObject(url)?.optLong("p") ?: 0L

    /** Fração já vista (para a barrinha do cartão), ou -1. */
    fun fracao(ctx: Context, url: String): Float {
        val e = obj(ctx, "continuar").optJSONObject(url) ?: return -1f
        val d = e.optLong("d")
        return if (d > 0) (e.optLong("p").toFloat() / d).coerceIn(0f, 1f) else -1f
    }

    /** Filmes/episódios começados, do mais recente para o mais antigo. */
    fun continuar(ctx: Context): List<Iptv.Canal> {
        val o = obj(ctx, "continuar")
        return o.keys().asSequence().toList()
            .sortedByDescending { o.getJSONObject(it).optLong("t") }
            .map { u ->
                val e = o.getJSONObject(u)
                Iptv.Canal(e.optString("n"), e.optString("g"), e.optString("l").ifBlank { null }, u)
            }
    }

    // ---------- histórico e mais vistos ----------

    fun assistiu(ctx: Context, lista: String, c: Iptv.Canal) {
        val h = try { JSONArray(sp(ctx).getString("hist_$lista", "[]")) } catch (_: Throwable) { JSONArray() }
        val nova = JSONArray().put(c.url)
        for (i in 0 until h.length()) if (h.optString(i) != c.url && nova.length() < 20) nova.put(h.optString(i))
        salvar(ctx, "hist_$lista", nova)
        val v = obj(ctx, "vezes_$lista")
        v.put(c.url, v.optInt(c.url) + 1)
        if (v.length() > 400) {
            val menos = v.keys().asSequence().toList().sortedBy { v.optInt(it) }
            menos.take(v.length() - 400).forEach { v.remove(it) }
        }
        salvar(ctx, "vezes_$lista", v)
    }

    /** Links dos últimos canais vistos (mais recente primeiro). */
    fun ultimos(ctx: Context, lista: String): List<String> {
        val h = try { JSONArray(sp(ctx).getString("hist_$lista", "[]")) } catch (_: Throwable) { JSONArray() }
        return List(h.length()) { h.optString(it) }
    }

    fun vezes(ctx: Context, lista: String): Map<String, Int> {
        val v = obj(ctx, "vezes_$lista")
        return v.keys().asSequence().associateWith { v.optInt(it) }
    }

    // ---------- canais quebrados ----------

    private const val QUEBRADO_POR = 24L * 60 * 60 * 1000

    fun marcarQuebrado(ctx: Context, url: String) {
        val q = obj(ctx, "quebrados")
        q.put(url, System.currentTimeMillis())
        salvar(ctx, "quebrados", q)
    }

    fun funcionou(ctx: Context, url: String) {
        val q = obj(ctx, "quebrados")
        if (q.has(url)) {
            q.remove(url)
            salvar(ctx, "quebrados", q)
        }
    }

    /** Links escondidos agora (falharam nas últimas 24 horas). */
    fun quebrados(ctx: Context): Set<String> {
        if (!esconderQuebrados(ctx)) return emptySet()
        val q = obj(ctx, "quebrados")
        val agora = System.currentTimeMillis()
        return q.keys().asSequence().filter { agora - q.optLong(it) < QUEBRADO_POR }.toSet()
    }

    fun limparQuebrados(ctx: Context) = sp(ctx).edit().remove("quebrados").apply()

    fun esconderQuebrados(ctx: Context) = sp(ctx).getBoolean("esconder_quebrados", true)
    fun mudarEsconderQuebrados(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("esconder_quebrados", v).apply()

    // ---------- ordem dos canais ----------

    /** 0 = ordem da lista, 1 = A a Z, 2 = mais vistos. */
    fun ordem(ctx: Context, lista: String) = sp(ctx).getInt("ordem_$lista", 0)
    fun mudarOrdem(ctx: Context, lista: String, v: Int) = sp(ctx).edit().putInt("ordem_$lista", v).apply()

    // ---------- várias listas ----------

    /** Listas guardadas: nome -> link (na ordem em que foram guardadas). */
    fun listasGuardadas(ctx: Context): List<Pair<String, String>> {
        val a = try { JSONArray(sp(ctx).getString("listas_guardadas", "[]")) } catch (_: Throwable) { JSONArray() }
        return List(a.length()) { a.getJSONArray(it).let { p -> p.optString(0) to p.optString(1) } }
    }

    fun guardarLista(ctx: Context, nome: String, url: String) {
        val l = listasGuardadas(ctx).filter { it.second != url } + (nome to url)
        val a = JSONArray()
        l.takeLast(12).forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        salvar(ctx, "listas_guardadas", a)
    }

    fun apagarLista(ctx: Context, url: String) {
        val a = JSONArray()
        listasGuardadas(ctx).filter { it.second != url }.forEach { a.put(JSONArray().put(it.first).put(it.second)) }
        salvar(ctx, "listas_guardadas", a)
    }
}
