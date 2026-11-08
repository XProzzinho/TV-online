package com.geanalves.tvonline

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Perfil em uso (mandado pelo Início TV quando você troca de perfil),
 * bloqueio infantil, canais adultos com PIN e a chave do TMDB.
 */
object Perfil {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)

    /** Perfil do Início TV: 0, 1 ou 2 (-1 = o Início TV ainda não avisou). */
    fun numero(ctx: Context) = sp(ctx).getInt("perfil_num", -1)
    fun nome(ctx: Context): String? = sp(ctx).getString("perfil_nome", null)

    /** Perfil de criança: o Lumi TV mostra só desenhos e coisas infantis. */
    fun infantil(ctx: Context) = sp(ctx).getBoolean("perfil_kids", false)

    fun mudar(ctx: Context, num: Int, nome: String?, kids: Boolean) {
        sp(ctx).edit().putInt("perfil_num", num).putString("perfil_nome", nome).putBoolean("perfil_kids", kids).apply()
    }

    /** Chave dos favoritos: o perfil 1 usa a lista antiga (ninguém perde os favoritos que já tinha). */
    fun sufixoFavoritos(ctx: Context): String = numero(ctx).let { if (it <= 0) "" else "_p$it" }

    // ---------- infantil e adultos ----------

    private val kids = listOf("kid", "infantil", "crianca", "desenho", "cartoon", "disney", "nick", "gloob", "animacao", "animation", "pixar", "anime")
    private val adulto = listOf("adult", "adulto", "xxx", "+18", "18+", "hot", "sexy", "erotic", "playboy", "venus", "sextreme")

    fun ehInfantil(c: Iptv.Canal): Boolean {
        val g = Iptv.semAcento(c.grupo)
        return kids.any { g.contains(it) }
    }

    fun ehAdulto(c: Iptv.Canal): Boolean {
        val g = Iptv.semAcento(c.grupo + " " + c.nome)
        return adulto.any { g.contains(it) }
    }

    /** Adultos liberados só até fechar o app (com o PIN). */
    var adultosLiberados = false

    fun pin(ctx: Context): String? = sp(ctx).getString("pin_lumi", null)
    fun criarPin(ctx: Context, p: String) = sp(ctx).edit().putString("pin_lumi", p).apply()

    /** Tira o que a pessoa não pode ver: no perfil infantil só fica o infantil; adultos só com PIN. */
    fun filtrar(ctx: Context, l: List<Iptv.Canal>): List<Iptv.Canal> = when {
        infantil(ctx) -> l.filter { ehInfantil(it) && !ehAdulto(it) }
        !adultosLiberados -> l.filter { !ehAdulto(it) }
        else -> l
    }

    // ---------- TMDB (informações do filme) ----------

    fun tmdb(ctx: Context): String? = sp(ctx).getString("tmdb", null)?.takeIf { it.isNotBlank() }
    fun salvarTmdb(ctx: Context, k: String) = sp(ctx).edit().putString("tmdb", k.trim()).apply()

    data class Info(val titulo: String, val ano: String, val nota: Double, val sinopse: String, val fundo: String?, val cartaz: String?, val tipo: String)

    /** Procura o título no TMDB (em português). [cb] recebe null se não achar. */
    fun info(ctx: Context, nome: String, cb: (Info?) -> Unit) {
        val key = tmdb(ctx)
        if (key == null) {
            cb(null)
            return
        }
        val limpo = Iptv.nomeSerie(nome)
            .replace(Regex("""\(?\b(19|20)\d{2}\b\)?"""), "")
            .replace(Regex("""\[.*?]|\b(4K|FHD|HD|SD|720p|1080p|dublado|legendado|dub|leg)\b""", RegexOption.IGNORE_CASE), "")
            .trim()
        Iptv.pool.execute {
            val r = try {
                val bearer = key.length > 40
                val url = "https://api.themoviedb.org/3/search/multi?language=pt-BR&include_adult=false&query=" +
                    URLEncoder.encode(limpo, "UTF-8") + if (bearer) "" else "&api_key=$key"
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 15000
                if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
                val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                c.disconnect()
                val res = js.optJSONArray("results")
                var achado: JSONObject? = null
                if (res != null) {
                    for (i in 0 until res.length()) {
                        val o = res.getJSONObject(i)
                        if (o.optString("media_type") in listOf("movie", "tv")) {
                            achado = o
                            break
                        }
                    }
                }
                achado?.let { o ->
                    val tv = o.optString("media_type") == "tv"
                    Info(
                        titulo = o.optString(if (tv) "name" else "title").ifBlank { limpo },
                        ano = o.optString(if (tv) "first_air_date" else "release_date").take(4),
                        nota = o.optDouble("vote_average", 0.0),
                        sinopse = o.optString("overview"),
                        fundo = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w780$it" },
                        cartaz = o.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" },
                        tipo = if (tv) "Série" else "Filme",
                    )
                }
            } catch (_: Throwable) {
                null
            }
            Iptv.main.post { cb(r) }
        }
    }
}
