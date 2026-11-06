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
    }
}
