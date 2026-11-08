package com.geanalves.tvonline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Links das listas mandados pelo Controle TV (celular):
 *   --es iptv "LINK"  lista de TV ao vivo ("" apaga)
 *   --es vod  "LINK"  lista de filmes e séries ("" apaga)
 *   --es pluto "LINK" lista da Pluto TV ("" volta para a lista padrão do Brasil)
 *   --es tmdb "CHAVE" chave do TMDB (informações dos filmes)
 *   --ei perfil N --es perfil_nome "NOME" --ez perfil_kids true  (mandado pelo Início TV)
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        fun aviso(msg: String) = Handler(Looper.getMainLooper()).post { Toast.makeText(app, msg, Toast.LENGTH_LONG).show() }
        intent.getStringExtra("iptv")?.let { link ->
            Iptv.aoVivo.salvarLink(app, link)
            aviso(if (link.isBlank()) "Lista de TV ao vivo removida" else "Lista de TV ao vivo salva")
            resultData = "ok"
        }
        intent.getStringExtra("vod")?.let { link ->
            Iptv.vod.salvarLink(app, link)
            aviso(if (link.isBlank()) "Lista de filmes e séries removida" else "Lista de filmes e séries salva")
            resultData = "ok"
        }
        intent.getStringExtra("tmdb")?.let { k ->
            Perfil.salvarTmdb(app, k)
            aviso(if (k.isBlank()) "Lumi TV: chave do TMDB removida" else "Lumi TV: chave do TMDB salva")
            resultData = "ok"
        }
        if (intent.hasExtra("perfil")) {
            Perfil.mudar(app, intent.getIntExtra("perfil", 0), intent.getStringExtra("perfil_nome"), intent.getBooleanExtra("perfil_kids", false))
            Iptv.main.post { Iptv.aoMudar?.invoke() }
            resultData = "ok"
        }
        intent.getStringExtra("pluto")?.let { link ->
            Iptv.pluto.salvarLink(app, link)
            aviso(if (link.isBlank()) "Pluto TV: voltou para a lista padrão" else "Lista da Pluto TV trocada")
            resultData = "ok"
        }
    }
}
