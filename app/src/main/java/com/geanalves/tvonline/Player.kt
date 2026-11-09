package com.geanalves.tvonline

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
private fun Activity.dpf(v: Int) = v * resources.displayMetrics.density
private fun redondo(cor: Int, raio: Float) = GradientDrawable().apply {
    cornerRadius = raio
    setColor(cor)
}

/**
 * Assistindo: tela cheia.
 * TV ao vivo: cima/baixo trocam de canal, números vão direto ao canal, esquerda = último canal,
 * direita (ou Menu) = opções, OK = informações, segurar OK = favorito.
 * Filmes: esquerda/direita 15 s, OK pausa, Menu (ou baixo, em filme) = opções.
 */
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var tela: PlayerView
    private lateinit var faixa: LinearLayout
    private lateinit var faixaNome: TextView
    private lateinit var faixaSub: TextView
    private lateinit var faixaGuia: TextView
    private lateinit var faixaBarra: FrameLayout
    private lateinit var faixaCheio: View
    private lateinit var faixaLogo: ImageView
    private lateinit var aviso: TextView
    private lateinit var numero: TextView
    private var lista: List<Iptv.Canal> = emptyList()
    private var indice = 0
    private var anterior = -1
    private var vod = false
    private var tentou = false
    private var tentativas = 0
    private lateinit var raiz: FrameLayout
    private var pular: TextView? = null
    private val conferePular = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val p = pular
            if (p != null) {
                val mostrar = vod && lista.size > 1 && player.isPlaying &&
                    player.currentPosition in 5_000L..300_000L
                p.visibility = if (mostrar) View.VISIBLE else View.GONE
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun pularVisivel() = pular?.visibility == View.VISIBLE

    private fun pularAbertura() {
        player.seekTo(player.currentPosition + 85_000)
        pular?.visibility = View.GONE
        Toast.makeText(this, "Abertura pulada", Toast.LENGTH_SHORT).show()
    }
    private var painel: View? = null

    /** Lista de canais na esquerda, por cima do vídeo (o canal continua tocando). */
    private fun abrirPainel() {
        if (painel != null) return
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(18), dp(14), dp(18))
        }
        caixa.addView(TextView(this).apply {
            text = "Canais"
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(8), 0, 0, dp(10))
        })
        var focar: View? = null
        lista.forEachIndexed { i, c ->
            val bg = redondo(Color.TRANSPARENT, dpf(14))
            val nm = TextView(this).apply {
                text = "%02d   %s".format(i + 1, c.nome)
                textSize = 16f
                setTextColor(if (i == indice) Color.WHITE else 0xFFB0B0B8.toInt())
                typeface = if (i == indice) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = bg
                setPadding(dp(12), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                if (i == indice) addView(View(this@PlayerActivity).apply { background = redondo(Cores.vivo, dpf(4)) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
                addView(nm, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener {
                    fecharPainel()
                    if (i != indice) irPara(i)
                }
                setOnFocusChangeListener { _, has ->
                    bg.setColor(if (has) Color.WHITE else Color.TRANSPARENT)
                    nm.setTextColor(if (has) 0xFF07070A.toInt() else if (i == indice) Color.WHITE else 0xFFB0B0B8.toInt())
                }
            }
            caixa.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
            if (i == indice) focar = item
        }
        val sc = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF207070A.toInt(), 0xCC07070A.toInt()))
            addView(caixa)
        }
        raiz.addView(sc, FrameLayout.LayoutParams(dp(380), -1, Gravity.START))
        painel = sc
        sc.translationX = -dpf(380)
        sc.animate().translationX(0f).setDuration(220).start()
        focar?.post { focar?.requestFocus() }
    }

    private fun fecharPainel() {
        val p = painel ?: return
        painel = null
        p.animate().translationX(-dpf(380)).setDuration(180).withEndAction { raiz.removeView(p) }.start()
    }
    private val vigia = Runnable {
        // Ficou mais de 20 s carregando: tenta de novo sozinho
        if (!isFinishing && player.playbackState == Player.STATE_BUFFERING) reconectar()
    }

    /** Tenta abrir de novo (internet que caiu, link que venceu): 1,5 s, 4 s e 8 s. */
    private fun reconectar(): Boolean {
        if (tentativas >= 3) return false
        val espera = longArrayOf(1500, 4000, 8000)[tentativas]
        tentativas++
        aviso.text = "Reconectando… ($tentativas/3)"
        aviso.visibility = View.VISIBLE
        val pos = if (vod) player.currentPosition else 0L
        h.postDelayed({
            if (!isFinishing) {
                tocar(manterTentativa = true)
                if (vod && pos > 0) retomar = pos
            }
        }, espera)
        return true
    }
    private var digitado = ""
    private var timerFim = 0L
    private val h = Handler(Looper.getMainLooper())
    private val esconder = Runnable { faixa.animate().alpha(0f).setDuration(300).start() }
    private val irParaNumero = Runnable { pularParaNumero() }

    /** Guarda onde parou no filme/episódio a cada 15 segundos. */
    private val guardar = object : Runnable {
        override fun run() {
            guardarPosicao()
            h.postDelayed(this, 15_000)
        }
    }
    private var retomar = 0L
    private var velocidade = 1f
    private val desligar = Runnable {
        Toast.makeText(this, "Timer: desligando o Lumi TV", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun sp() = getSharedPreferences("inicio", Context.MODE_PRIVATE)

    private val modosTela = listOf(
        "Ajustar (normal)" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
        "Esticar (tela cheia)" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
        "Zoom (corta as bordas)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lista = Iptv.fila.ifEmpty { Iptv.atual.canais }
        vod = intent.getBooleanExtra("vod", false)
        indice = intent.getIntExtra("indice", 0).coerceIn(0, (lista.size - 1).coerceAtLeast(0))
        if (lista.isEmpty()) {
            finish()
            return
        }

        // Pluto TV toca melhor (e em HD) quando acha que é um navegador
        val ehPluto = lista.any { it.url.contains("pluto.tv") }
        val fonte = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(
                if (ehPluto) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
                else "InicioTV",
            )
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(fonte))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Reconecta sozinho até 3 vezes antes de desistir
                tentou = true
                if (reconectar()) return
                if (!vod) Historico.marcarQuebrado(this@PlayerActivity, lista[indice].url)
                aviso.text = if (vod) "Não consegui abrir este título agora." else "Este canal está fora do ar agora.\nUse cima/baixo para trocar."
                aviso.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                h.removeCallbacks(vigia)
                if (state == Player.STATE_BUFFERING) h.postDelayed(vigia, 20_000)
                if (state == Player.STATE_READY) {
                    aviso.visibility = View.GONE
                    tentou = false
                    tentativas = 0
                    val c = lista[indice]
                    if (!vod) {
                        Historico.funcionou(this@PlayerActivity, c.url)
                    }
                    // Continuar assistindo: volta para onde parou
                    if (vod && retomar > 0) {
                        val pos = retomar
                        retomar = 0
                        player.seekTo(pos)
                        Toast.makeText(this@PlayerActivity, "Continuando de onde você parou", Toast.LENGTH_SHORT).show()
                    }
                }
                if (state == Player.STATE_ENDED && vod) {
                    guardarPosicao(terminou = true)
                    // Série: próximo episódio sozinho
                    if (indice < lista.size - 1) {
                        Toast.makeText(this@PlayerActivity, "Próximo episódio", Toast.LENGTH_SHORT).show()
                        trocar(1)
                    } else {
                        finish()
                    }
                }
            }
        })
        Guia.carregar(this) { if (!isFinishing && !vod) mostrarFaixa() }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        tela = PlayerView(this).apply {
            useController = false
            resizeMode = modosTela.getOrNull(sp().getInt("tela_modo", 0))?.second ?: AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            this.player = this@PlayerActivity.player
        }
        root.addView(tela, FrameLayout.LayoutParams(-1, -1))

        // Faixa embaixo com o canal e o guia (some sozinha)
        faixa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = redondo(Cores.janela, dpf(24))
            elevation = dpf(6)
            setPadding(dp(14), dp(12), dp(24), dp(12))
        }
        faixaLogo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = redondo(Cores.campo, dpf(14))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        faixa.addView(faixaLogo, LinearLayout.LayoutParams(dp(84), dp(60)))
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, 0, 0)
        }
        faixaNome = TextView(this).apply {
            textSize = 22f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        faixaGuia = TextView(this).apply {
            textSize = 15f
            setTextColor(Cores.tinta)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        }
        faixaBarra = FrameLayout(this).apply { background = redondo(Cores.campo, dpf(2)) }
        faixaCheio = View(this).apply { background = redondo(Cores.vivo, dpf(2)) }
        faixaBarra.addView(faixaCheio, FrameLayout.LayoutParams(0, -1))
        faixaSub = TextView(this).apply {
            textSize = 13f
            setTextColor(Cores.cinza)
            setPadding(0, dp(4), 0, 0)
        }
        txt.addView(faixaNome)
        txt.addView(faixaGuia)
        txt.addView(faixaBarra, LinearLayout.LayoutParams(dp(420), dp(4)).apply { topMargin = dp(6) })
        txt.addView(faixaSub)
        faixa.addView(txt, LinearLayout.LayoutParams(dp(640), -2))
        root.addView(
            faixa,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(48)
                bottomMargin = dp(40)
            },
        )

        // Número digitado no controle (canto de cima)
        numero = TextView(this).apply {
            textSize = 54f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = redondo(0xCC000000.toInt(), dpf(18))
            setPadding(dp(26), dp(8), dp(26), dp(8))
            visibility = View.GONE
        }
        root.addView(numero, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(36)
            rightMargin = dp(48)
        })

        aviso = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = redondo(0xCC000000.toInt(), dpf(20))
            setPadding(dp(28), dp(18), dp(28), dp(18))
            visibility = View.GONE
        }
        root.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // Botão "Pular abertura" no canto (aparece nos primeiros minutos do episódio)
        pular = TextView(this).apply {
            text = "Pular abertura  ▸▸   (OK)"
            textSize = 17f
            setTextColor(0xFF07070A.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = redondo(Color.WHITE, dpf(24))
            setPadding(dp(22), dp(12), dp(22), dp(12))
            visibility = View.GONE
        }
        root.addView(pular, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(48)
            bottomMargin = dp(48)
        })
        setContentView(root)
        raiz = root
        h.post(conferePular)
        tocar()
    }

    private fun tocar(manterTentativa: Boolean = false) {
        if (!manterTentativa) {
            tentou = false
            tentativas = 0
        }
        val c = lista[indice]
        Iptv.atual.salvarUltimo(this, c)
        if (!manterTentativa) Historico.assistiu(this, Iptv.atual.id, c)
        retomar = if (vod) Historico.posicao(this, c.url) else 0L
        aviso.visibility = View.GONE
        val item = MediaItem.Builder().setUri(Uri.parse(c.url)).apply {
            if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.setPlaybackSpeed(velocidade)
        player.playWhenReady = true
        mostrarFaixa()
    }

    private fun guardarPosicao(terminou: Boolean = false) {
        if (!vod || !::player.isInitialized) return
        val dur = player.duration
        if (dur <= 0) return
        Historico.guardarPosicao(this, lista[indice], if (terminou) dur else player.currentPosition, dur)
    }

    private fun mostrarFaixa() {
        if (!::faixa.isInitialized) return
        val c = lista[indice]
        val fav = c.url in Iptv.atual.favoritos(this)
        if (vod) {
            val pos = player.currentPosition / 1000
            val dur = player.duration.takeIf { it > 0 }?.div(1000)
            fun t(x: Long) = "%d:%02d:%02d".format(x / 3600, x / 60 % 60, x % 60)
            faixaNome.text = c.nome
            faixaGuia.text = (if (player.playWhenReady) "▶  " else "❚❚  ") + t(pos) + (dur?.let { " / " + t(it) } ?: "")
            faixaSub.text = "◀ ▶ 15 s" + (if (lista.size > 1) "  ·  ▲▼ episódio ${indice + 1}/${lista.size}" else "") + "  ·  Menu: opções"
            barra(if (dur != null && dur > 0) pos.toFloat() / dur else -1f)
        } else {
            faixaNome.text = "${indice + 1}  ${c.nome}"
            val (ag, dep) = Guia.agora(c)
            faixaGuia.text = when {
                ag != null -> "Agora: ${ag.titulo}  (${Guia.hora(ag.inicio)}–${Guia.hora(ag.fim)})" +
                    (dep?.let { "\nDepois: ${it.titulo}  às ${Guia.hora(it.inicio)}" } ?: "")
                else -> c.grupo
            }
            barra(ag?.progresso() ?: -1f)
            faixaSub.text = (if (fav) "★ Favorito" else "Segure OK para favoritar") +
                "  ·  ◀ último canal  ·  ▶ opções" + timerTexto()
        }
        faixaLogo.setImageDrawable(null)
        c.logo?.let { u -> Iptv.logo(u) { b -> if (b != null && lista[indice] == c) faixaLogo.setImageBitmap(b) } }
        faixa.animate().cancel()
        faixa.alpha = 1f
        h.removeCallbacks(esconder)
        h.postDelayed(esconder, 5000)
    }

    /** Barra de progresso da faixa (p < 0 = esconde). */
    private fun barra(p: Float) {
        if (p < 0f) {
            faixaBarra.visibility = View.GONE
            return
        }
        faixaBarra.visibility = View.VISIBLE
        faixaCheio.layoutParams = FrameLayout.LayoutParams((dp(420) * p.coerceIn(0f, 1f)).toInt(), -1)
    }

    private fun timerTexto(): String {
        if (timerFim == 0L) return ""
        val min = ((timerFim - System.currentTimeMillis()) / 60000).coerceAtLeast(0) + 1
        return "  ·  desliga em $min min"
    }

    private fun trocar(passo: Int) {
        irPara((indice + passo + lista.size) % lista.size)
    }

    private fun irPara(i: Int) {
        if (i == indice) {
            mostrarFaixa()
            return
        }
        guardarPosicao()
        anterior = indice
        indice = i
        tocar()
    }

    // ---------- canal pelo número ----------

    private fun digitar(n: Int) {
        if (digitado.length >= 4) digitado = ""
        digitado += n
        numero.text = digitado
        numero.visibility = View.VISIBLE
        h.removeCallbacks(irParaNumero)
        h.postDelayed(irParaNumero, 1500)
    }

    private fun pularParaNumero() {
        numero.visibility = View.GONE
        val n = digitado.toIntOrNull()
        digitado = ""
        if (n == null || n < 1 || n > lista.size) {
            Toast.makeText(this, "Não tem canal com esse número", Toast.LENGTH_SHORT).show()
            return
        }
        irPara(n - 1)
    }

    // ---------- menu de opções ----------

    private fun escolher(titulo: String, opcoes: List<String>, marcado: Int, acao: (Int) -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redondo(Cores.janela, dpf(26))
            setPadding(dp(26), dp(22), dp(26), dp(22))
        }
        box.addView(TextView(this).apply {
            text = titulo
            textSize = 22f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(12))
        })
        var primeiro: View? = null
        opcoes.forEachIndexed { i, o ->
            val bg = redondo(if (i == marcado) Cores.campo else Color.TRANSPARENT, dpf(16))
            val t = TextView(this).apply {
                text = (if (i == marcado) "✓  " else "     ") + o
                textSize = 18f
                setTextColor(Cores.tinta)
                background = bg
                setPadding(dp(16), dp(12), dp(22), dp(12))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    d.dismiss()
                    acao(i)
                }
                setOnFocusChangeListener { _, has ->
                    bg.setColor(if (has) Cores.tinta else if (i == marcado) Cores.campo else Color.TRANSPARENT)
                    setTextColor(if (has) Cores.papel else Cores.tinta)
                }
            }
            if (i == marcado.coerceAtLeast(0)) primeiro = t
            box.addView(t, LinearLayout.LayoutParams(dp(460), -2).apply { bottomMargin = dp(4) })
        }
        val sv = ScrollView(this).apply { addView(box) }
        d.setContentView(sv)
        d.show()
        primeiro?.requestFocus()
    }

    private fun menu() {
        val itens = mutableListOf("Áudio", "Legenda", "Tela", "Desligar em… (timer)", "Qualidade da imagem")
        itens += if (vod) "Velocidade (${fmtVel(velocidade)})" else "—"
        if (podePip()) itens += "Imagem na imagem (canto da tela)"
        escolher("Opções", itens, -1) { i ->
            when (i) {
                0 -> menuFaixa(C.TRACK_TYPE_AUDIO)
                1 -> menuFaixa(C.TRACK_TYPE_TEXT)
                2 -> menuTela()
                3 -> menuTimer()
                4 -> menuQualidade()
                5 -> if (vod) menuVelocidade()
                6 -> entrarPip()
            }
        }
    }

    private fun fmtVel(v: Float) = (if (v % 1f == 0f) v.toInt().toString() else v.toString().replace('.', ',')) + "x"

    private fun menuVelocidade() {
        val vs = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        escolher("Velocidade", vs.map { fmtVel(it) + if (it == 1f) " (normal)" else "" }, vs.indexOf(velocidade)) { i ->
            velocidade = vs[i]
            player.setPlaybackSpeed(velocidade)
            mostrarFaixa()
        }
    }

    /** Lista os áudios ou legendas do canal/filme e deixa escolher. */
    /** Qualidade: automática ou uma resolução fixa (quando o canal tem mais de uma). */
    private fun menuQualidade() {
        val nomes = arrayListOf("Automática")
        val acoes = arrayListOf<() -> Unit>({
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
        })
        var marcado = 0
        val vistas = HashSet<Int>()
        for (g in player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }) {
            val ordem = (0 until g.length).filter { g.isTrackSupported(it) }.sortedByDescending { g.getTrackFormat(it).height }
            for (j in ordem) {
                val f = g.getTrackFormat(j)
                if (f.height <= 0 || !vistas.add(f.height)) continue
                val rotulo = when {
                    f.height >= 2160 -> "4K"
                    f.height >= 1080 -> "Full HD (1080p)"
                    f.height >= 720 -> "HD (720p)"
                    else -> "SD (${f.height}p)"
                }
                if (player.trackSelectionParameters.overrides.values.any { it.mediaTrackGroup == g.mediaTrackGroup && j in it.trackIndices }) marcado = nomes.size
                nomes += rotulo
                val grupo = g.mediaTrackGroup
                acoes += {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(grupo, j)).build()
                }
            }
        }
        if (nomes.size <= 2) {
            Toast.makeText(this, "Este ${if (vod) "título" else "canal"} só tem uma qualidade", Toast.LENGTH_SHORT).show()
            return
        }
        escolher("Qualidade da imagem", nomes, marcado) { acoes[it]() }
    }

    private fun menuFaixa(tipo: Int) {
        val grupos = player.currentTracks.groups.filter { it.type == tipo }
        val nomes = ArrayList<String>()
        val acoes = ArrayList<() -> Unit>()
        var marcado = -1
        if (tipo == C.TRACK_TYPE_TEXT) {
            nomes += "Sem legenda"
            acoes += {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || grupos.none { it.isSelected }) marcado = 0
        }
        for (g in grupos) {
            for (j in 0 until g.length) {
                if (!g.isTrackSupported(j)) continue
                val f = g.getTrackFormat(j)
                val idioma = f.language?.let { java.util.Locale(it).getDisplayLanguage(java.util.Locale("pt", "BR")) }
                val nome = listOfNotNull(f.label, idioma?.replaceFirstChar { it.uppercase() })
                    .distinct().joinToString(" · ").ifBlank { (if (tipo == C.TRACK_TYPE_AUDIO) "Áudio " else "Legenda ") + (nomes.size + 1) } +
                    (if (tipo == C.TRACK_TYPE_AUDIO && f.channelCount >= 6) "  (5.1)" else "")
                if (g.isTrackSelected(j) && !(tipo == C.TRACK_TYPE_TEXT && marcado == 0)) marcado = nomes.size
                nomes += nome
                val grupo = g.mediaTrackGroup
                acoes += {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(tipo, false)
                        .setOverrideForType(TrackSelectionOverride(grupo, j))
                        .build()
                }
            }
        }
        if (nomes.isEmpty() || (tipo == C.TRACK_TYPE_TEXT && nomes.size == 1)) {
            Toast.makeText(this, if (tipo == C.TRACK_TYPE_AUDIO) "Só tem um áudio aqui" else "Este canal não tem legenda", Toast.LENGTH_SHORT).show()
            return
        }
        escolher(if (tipo == C.TRACK_TYPE_AUDIO) "Áudio" else "Legenda", nomes, marcado) { acoes[it]() }
    }

    private fun menuTela() {
        val atual = modosTela.indexOfFirst { it.second == tela.resizeMode }
        escolher("Tela", modosTela.map { it.first }, atual) { i ->
            tela.resizeMode = modosTela[i].second
            sp().edit().putInt("tela_modo", i).apply()
        }
    }

    private fun menuTimer() {
        val mins = listOf(0, 15, 30, 60, 90, 120)
        val nomes = mins.map { if (it == 0) "Desligado" else "$it minutos" }
        escolher("Desligar em…", nomes, if (timerFim == 0L) 0 else -1) { i ->
            h.removeCallbacks(desligar)
            if (mins[i] == 0) {
                timerFim = 0L
                Toast.makeText(this, "Timer desligado", Toast.LENGTH_SHORT).show()
            } else {
                timerFim = System.currentTimeMillis() + mins[i] * 60_000L
                h.postDelayed(desligar, mins[i] * 60_000L)
                Toast.makeText(this, "O Lumi TV fecha em ${mins[i]} minutos", Toast.LENGTH_SHORT).show()
            }
            mostrarFaixa()
        }
    }

    /** Abriu outro canal com o player já aberto (por exemplo, na imagem na imagem). */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val nova = Iptv.fila.ifEmpty { Iptv.atual.canais }
        if (nova.isEmpty()) return
        guardarPosicao()
        lista = nova
        vod = intent.getBooleanExtra("vod", false)
        indice = intent.getIntExtra("indice", 0).coerceIn(0, lista.size - 1)
        anterior = -1
        tocar()
    }

    // ---------- imagem na imagem (PiP) ----------

    private fun podePip() = android.os.Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun entrarPip() {
        if (!podePip()) {
            Toast.makeText(this, "Esta TV não tem imagem na imagem", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            enterPictureInPictureMode(
                android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).build(),
            )
        } catch (_: Throwable) {
            Toast.makeText(this, "Esta TV não deixou abrir a imagem na imagem", Toast.LENGTH_SHORT).show()
        }
    }

    /** Apertou Início assistindo TV ao vivo: o canal continua pequeno no canto. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!vod && podePip() && getSharedPreferences("inicio", MODE_PRIVATE).getBoolean("pip_auto", true)) entrarPip()
    }

    override fun onPictureInPictureModeChanged(pip: Boolean, cfg: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(pip, cfg)
        if (pip) {
            h.removeCallbacks(esconder)
            faixa.alpha = 0f
            numero.visibility = View.GONE
        }
    }

    // ---------- touchpad: deslizar troca de canal ----------

    private var toqueY = 0f
    private var toqueX = 0f

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean = deslizar(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean = deslizar(ev) || super.onTouchEvent(ev)

    private fun deslizar(ev: android.view.MotionEvent): Boolean {
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                toqueX = ev.x
                toqueY = ev.y
                return true
            }
            android.view.MotionEvent.ACTION_UP -> {
                val dx = ev.x - toqueX
                val dy = ev.y - toqueY
                val min = dp(60)
                when {
                    kotlin.math.abs(dy) > min && kotlin.math.abs(dy) > kotlin.math.abs(dx) -> {
                        if (vod) {
                            if (lista.size > 1) trocar(if (dy < 0) 1 else -1)
                        } else {
                            trocar(if (dy < 0) 1 else -1)
                        }
                    }
                    kotlin.math.abs(dx) > min -> {
                        if (vod) {
                            player.seekTo((player.currentPosition + if (dx > 0) 15000 else -15000).coerceAtLeast(0))
                            mostrarFaixa()
                        } else if (dx < 0 && anterior in lista.indices) {
                            irPara(anterior)
                        } else {
                            menu()
                        }
                    }
                    else -> {
                        if (vod) player.playWhenReady = !player.playWhenReady
                        mostrarFaixa()
                    }
                }
                return true
            }
        }
        return false
    }

    // ---------- teclas do controle ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Lista de canais aberta: setas andam na lista, Voltar fecha
        if (painel != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                fecharPainel()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            menu()
            return true
        }
        // Filme/série: esquerda/direita voltam e avançam 15 s; OK pausa
        if (vod) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    player.seekTo((player.currentPosition - 15000).coerceAtLeast(0))
                    mostrarFaixa()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    player.seekTo(player.currentPosition + 15000)
                    mostrarFaixa()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (lista.size > 1) trocar(1) else mostrarFaixa()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Série: episódio anterior; filme: abre as opções
                    if (lista.size > 1) trocar(-1) else menu()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player.playWhenReady = !player.playWhenReady
                    mostrarFaixa()
                    return true
                }
            }
        }
        if (!vod && keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            digitar(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                trocar(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                trocar(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                if (anterior in lista.indices) irPara(anterior) else mostrarFaixa()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                menu()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (painel != null) return super.onKeyLongPress(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val agora = Iptv.atual.trocarFavorito(this, lista[indice])
            Toast.makeText(this, if (agora) "★ Favorito" else "Tirado dos favoritos", Toast.LENGTH_SHORT).show()
            mostrarFaixa()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (painel != null) return super.onKeyUp(keyCode, event)
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                when {
                    // Série no começo: OK pula a abertura
                    vod && pularVisivel() -> pularAbertura()
                    vod -> {
                        player.playWhenReady = !player.playWhenReady
                        mostrarFaixa()
                    }
                    // TV ao vivo: OK abre a lista de canais por cima do vídeo
                    else -> abrirPainel()
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        // Fechou a janelinha da imagem na imagem: para de vez
        if (android.os.Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) finish()
        guardarPosicao()
        h.removeCallbacks(guardar)
        if (::player.isInitialized) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
        if (vod) h.postDelayed(guardar, 15_000)
    }

    override fun onDestroy() {
        h.removeCallbacks(conferePular)
        h.removeCallbacks(vigia)
        h.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
