package com.geanalves.tvonline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Duas listas M3U mandadas pelo Controle TV:
 * [aoVivo] = canais de TV ao vivo e [vod] = filmes e séries.
 * O Início TV só toca as listas que a pessoa escolher.
 */
object Iptv {
    data class Canal(val nome: String, val grupo: String, val logo: String?, val url: String)

    val aoVivo = ListaM3u("iptv", vod = false)
    val vod = ListaM3u("vod", vod = true)

    /** A lista que a tela aberta está mostrando. */
    var atual: ListaM3u = aoVivo

    /** Itens que o player percorre (a lista que estava na tela). */
    var fila: List<Canal> = emptyList()

    /** Avisado quando o controle troca um link (as bolinhas da tela inicial mudam de cor). */
    var aoMudar: (() -> Unit)? = null

    internal val pool = Executors.newFixedThreadPool(4)
    internal val main = Handler(Looper.getMainLooper())
    private val logos = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** "Round 6 S01 E03" → "Round 6" (para juntar os episódios da mesma série). */
    fun nomeSerie(n: String): String =
        n.replace(Regex("""\s*[-–|:]?\s*(S\d{1,2}\s*E\d{1,3}|T\d{1,2}\s*E\d{1,3}|\d{1,2}x\d{1,3}|Temporada\s*\d+.*|Ep(is[oó]dio)?\.?\s*\d+).*$""", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { n }

    internal fun semAcento(s: String) =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    // ---------- logos dos canais ----------

    fun logo(url: String, cb: (Bitmap?) -> Unit) = logo(url, 200, cb)

    /** Baixa o logo/cartaz reduzido para no máximo [max] px de largura. */
    fun logo(url: String, max: Int, cb: (Bitmap?) -> Unit) {
        val chave = "$max|$url"
        logos.get(chave)?.let { cb(it); return }
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 12000
                c.instanceFollowRedirects = true
                val bytes = c.inputStream.use { it.readBytes() }
                c.disconnect()
                // Reduz para no máximo ~200px: economiza memória da TV
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max * 0.6) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
            } catch (_: Throwable) {
                null
            }
            main.post {
                if (bmp != null) logos.put(chave, bmp)
                cb(bmp)
            }
        }
    }
}

/** Uma lista M3U (TV ao vivo ou filmes e séries), com favoritos e abas próprias. */
class ListaM3u(private val chave: String, val vod: Boolean) {
    private val VALIDADE = 12L * 60 * 60 * 1000 // baixa a lista de novo a cada 12 horas
    private val pool get() = Iptv.pool
    private val main get() = Iptv.main

    var canais: List<Iptv.Canal> = emptyList()
        private set

    private fun sp(ctx: Context) = ctx.getSharedPreferences("inicio", Context.MODE_PRIVATE)
    private fun cache(ctx: Context) = File(ctx.filesDir, "lista_$chave.m3u")

    fun link(ctx: Context): String? = sp(ctx).getString("${chave}_url", null)

    fun salvarLink(ctx: Context, url: String) {
        val e = sp(ctx).edit()
        if (url.isBlank()) e.remove("${chave}_url") else e.putString("${chave}_url", url.trim())
        e.apply()
        cache(ctx).delete()
        canais = emptyList()
        main.post { Iptv.aoMudar?.invoke() }
    }

    // ---------- favoritos e último ----------

    fun favoritos(ctx: Context): Set<String> = sp(ctx).getStringSet("${chave}_fav", emptySet())!!

    /** Liga/desliga o favorito. Devolve true se agora é favorito. */
    fun trocarFavorito(ctx: Context, c: Iptv.Canal): Boolean {
        val f = favoritos(ctx).toMutableSet()
        val agora = if (c.url in f) { f.remove(c.url); false } else { f.add(c.url); true }
        sp(ctx).edit().putStringSet("${chave}_fav", f).apply()
        return agora
    }

    fun salvarUltimo(ctx: Context, c: Iptv.Canal) = sp(ctx).edit().putString("${chave}_ultimo", c.url).apply()

    // ---------- abas ----------

    /** TV ao vivo: só canais. Filmes e Séries: só filmes e séries. */
    val abas: List<String> =
        if (vod) listOf("Filmes", "Séries", "Kids", "Anime", "Tudo")
        else listOf("Destaques", "Jogos", "Notícias", "Kids", "Explorar")

    private val chavesAoVivo = mapOf(
        1 to listOf("esporte", "sport", "futebol", "football", "soccer", "premiere", "combate", "ufc", "luta", "nba", "espn", "jogos"),
        2 to listOf("noticia", "news", "jornal", "cnn", "globonews", "bandnews"),
        3 to listOf("kid", "infantil", "crianca", "desenho", "cartoon", "disney", "nick", "gloob", "animation"),
    )
    private val chavesVod = mapOf(
        0 to listOf("filme", "movie", "cine", "vod", "lancamento"),
        1 to listOf("serie", "series", "novela", "seriado", "temporada"),
        2 to listOf("kid", "infantil", "crianca", "desenho", "animacao", "animation", "disney", "pixar"),
        3 to listOf("anime", "crunchyroll", "otaku"),
    )

    private fun eFilme(c: Iptv.Canal) = c.url.contains("/movie/")
    private fun eSerie(c: Iptv.Canal) = c.url.contains("/series/")

    /** Itens de cada aba: separa pelo grupo da lista (e pelo tipo do link, nas listas Xtream). */
    fun daAba(ctx: Context, aba: Int): List<Iptv.Canal> {
        val ultima = abas.size - 1
        if (aba == ultima) return canais
        if (!vod && aba == 0) {
            // Destaques: favoritos primeiro, depois os primeiros canais da lista
            val f = favoritos(ctx)
            return (canais.filter { it.url in f } + canais.filter { it.url !in f }.take(40)).distinct()
        }
        val ks = (if (vod) chavesVod else chavesAoVivo)[aba].orEmpty()
        return canais.filter { c ->
            val g = Iptv.semAcento(c.grupo)
            ks.any { g.contains(it) } ||
                (vod && aba == 0 && eFilme(c)) ||
                (vod && aba == 1 && eSerie(c))
        }
    }

    // ---------- carregar a lista ----------

    /** [cb] recebe a mensagem de erro (null = deu certo). */
    fun carregar(ctx: Context, forcar: Boolean = false, cb: (String?) -> Unit) {
        val app = ctx.applicationContext
        val url = link(app)
        if (url == null) {
            canais = emptyList()
            cb("sem lista")
            return
        }
        if (!forcar && canais.isNotEmpty()) {
            cb(null)
            return
        }
        pool.execute {
            val arq = cache(app)
            var erro: String? = null
            try {
                val velho = !arq.exists() || System.currentTimeMillis() - arq.lastModified() > VALIDADE
                if (forcar || velho) {
                    try {
                        baixar(url, arq)
                    } catch (t: Throwable) {
                        if (!arq.exists()) throw t // sem internet: usa a cópia antiga se tiver
                    }
                }
                val lista = ler(arq)
                // Lista Xtream completa na aba de filmes: fica só com filmes e séries
                val final = if (vod && lista.any { it.url.contains("/movie/") || it.url.contains("/series/") }) {
                    lista.filter { it.url.contains("/movie/") || it.url.contains("/series/") }
                } else {
                    lista
                }
                if (final.isEmpty()) erro = "A lista está vazia" else canais = final
            } catch (t: Throwable) {
                erro = "Não consegui baixar a lista"
            }
            main.post { cb(erro) }
        }
    }

    private fun baixar(link: String, destino: File) {
        var url = URL(link)
        var c: HttpURLConnection
        var pulos = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "InicioTV")
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
        val tmp = File(destino.parentFile, destino.name + ".tmp")
        c.inputStream.use { e -> tmp.outputStream().use { e.copyTo(it) } }
        tmp.renameTo(destino)
    }

    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    /** Lê o M3U: #EXTINF com tvg-logo, group-title e o nome depois da vírgula; a linha seguinte é o link. */
    private fun ler(arq: File): List<Iptv.Canal> {
        val out = ArrayList<Iptv.Canal>()
        var nome: String? = null
        var grupo = ""
        var logo: String? = null
        arq.bufferedReader().useLines { linhas ->
            for (bruta in linhas) {
                val l = bruta.trim()
                when {
                    l.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = attr.findAll(l).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        nome = l.substringAfterLast(',').trim().ifBlank { attrs["tvg-name"] ?: "Canal" }
                        grupo = attrs["group-title"]?.split(';')?.firstOrNull()?.trim().orEmpty()
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
                    }
                    l.startsWith("#EXTGRP", ignoreCase = true) -> grupo = l.substringAfter(':').trim()
                    l.isEmpty() || l.startsWith("#") -> Unit
                    else -> {
                        if (nome != null && (l.startsWith("http") || l.startsWith("rtmp") || l.startsWith("rtsp"))) {
                            val item = Iptv.Canal(nome!!, grupo.ifBlank { "Outros" }, logo, l)
                            // Lista Xtream completa: TV ao vivo ignora filmes/séries
                            val ehVod = l.contains("/movie/") || l.contains("/series/")
                            if (vod || !ehVod) out += item
                        }
                        nome = null
                        grupo = ""
                        logo = null
                    }
                }
            }
        }
        return out
    }

}
