package com.geanalves.tvonline

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Ajudas de desenho iguais às da tela inicial. */
private fun Activity.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
private fun Activity.dpf(v: Int) = v * resources.displayMetrics.density
private fun redondo(cor: Int, raio: Float) = GradientDrawable().apply {
    cornerRadius = raio
    setColor(cor)
}

/**
 * TV ao vivo com o mesmo design da tela inicial do Início TV:
 * título grande, abas (Jogos, Destaques, Filmes, Séries, Kids, Anime, Explorar),
 * destaque grande + dois cartões altos, os grupos em blocos e a grade de canais.
 */
class IptvActivity : Activity() {

    private companion object {
        const val VOZ = 77
    }

    private lateinit var conteudo: LinearLayout
    private lateinit var info: TextView
    private lateinit var titulo: TextView
    private val abas = ArrayList<TextView>()
    private var abaAtual = 0 // TV ao vivo começa em Destaques; Filmes e Séries em Filmes
    private val ehVod get() = Iptv.atual.vod
    private val nomeTela get() = when (modo) {
        1 -> "Filmes e Séries"
        2 -> "Pluto TV"
        else -> "TV ao vivo"
    }
    private var subgrupo: String? = null
    private val colunas = 5
    private val h = Handler(Looper.getMainLooper())
    private var girar: Runnable? = null
    private var episodios: Map<String, List<Iptv.Canal>> = emptyMap()
    /** 0 = TV ao vivo, 1 = Filmes e Séries, 2 = Pluto TV. */
    private var modo = 0
    private lateinit var relogio: TextView
    private val horaFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR"))
    private val tique = object : Runnable {
        override fun run() {
            relogio.text = horaFmt.format(java.util.Date())
            h.postDelayed(this, 20_000)
        }
    }
    private lateinit var abasRow: LinearLayout
    private val botoesModo = ArrayList<TextView>()

    /** Grade em pedaços: monta 40 cartões e o resto conforme você desce. */
    private var gradeLista: List<Iptv.Canal> = emptyList()
    private var gradeFeitos = 0
    private var gradeLinha: LinearLayout? = null
    private var gradeFavs: Set<String> = emptySet()
    private var gradeLarg = 0
    private val PEDACO = 40

    /** Para abrir no último canal só uma vez, quando o app abre. */
    private var jaAbriuUltimo = false

    /** Texto buscado (null = sem busca). */
    private var busca: String? = null

    /** Prévia ao vivo: um player pequeno e mudo dentro do cartão parado. */
    private var previa: ExoPlayer? = null
    private var previaView: PlayerView? = null
    private var previaAgendada: Runnable? = null
    private fun previaLigada() = getSharedPreferences("inicio", MODE_PRIVATE).getBoolean("previa", true)

    /** O que o player vai tocar: os episódios da série ou a lista da tela. */
    private fun abrir(lista: List<Iptv.Canal>, i: Int) {
        val c = lista[i]
        val eps = episodios[c.nome]
        if (eps != null) {
            Iptv.fila = eps
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("indice", 0).putExtra("vod", true))
        } else {
            // Filme: só ele (cima/baixo não trocam de filme sem querer)
            Iptv.fila = if (ehVod) listOf(c) else lista
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("indice", if (ehVod) 0 else i).putExtra("vod", ehVod))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
                Cores.atualizar()
        modo = when (intent.getStringExtra("modo")) {
            "vod" -> 1
            "pluto" -> 2
            else -> 0
        }
        Iptv.atual = Iptv.listas[modo]
        abaAtual = intent.getIntExtra("aba", 0).coerceIn(0, Iptv.atual.abas.size - 1)
        setContentView(montar())
        jaAbriuUltimo = savedInstanceState != null
        busca = intent.getStringExtra("busca")?.takeIf { it.isNotBlank() }
        if (busca != null) jaAbriuUltimo = true
        Iptv.aoMudar = { if (!isFinishing && Iptv.atual.canais.isNotEmpty()) mostrar() }
        carregar(false)
        // Guia de programação: quando chegar, mostra "Agora" nos cartões
        Guia.carregar(this) { if (!isFinishing && !ehVod && Iptv.atual.canais.isNotEmpty()) mostrar() }
    }

    /** Liga o último canal assistido assim que a lista carrega (se a opção estiver ligada). */
    private fun abrirUltimoSeLigado() {
        if (jaAbriuUltimo || ehVod) return
        jaAbriuUltimo = true
        if (!Iptv.atual.abrirNoUltimo(this)) return
        val u = Iptv.atual.ultimo(this) ?: return
        val lista = Iptv.atual.canais
        val i = lista.indexOfFirst { it.url == u }
        if (i >= 0) {
            Iptv.fila = lista
            startActivity(Intent(this, PlayerActivity::class.java).putExtra("indice", i).putExtra("vod", false))
        }
    }

    override fun onResume() {
        super.onResume()
        Iptv.atual = Iptv.listas[modo]
        Atualizacao.conferir(this) { v -> if (!isFinishing) avisarAtualizacao(v) }
        // Volta do player: atualiza os favoritos
        if (Iptv.atual.canais.isNotEmpty()) mostrar()
    }

    override fun onPause() {
        super.onPause()
        girar?.let { h.removeCallbacks(it) }
        pararPrevia()
    }

    override fun onDestroy() {
        previa?.release()
        previa = null
        if (Iptv.aoMudar != null) Iptv.aoMudar = null
        super.onDestroy()
    }

    /** Busca mandada pelo Controle TV com o app já aberto. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("busca")?.takeIf { it.isNotBlank() }?.let { q ->
            buscar(q)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            abrirBusca()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && busca != null) {
            busca = null
            mostrar()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- busca (digitando ou falando) ----------

    private fun buscar(q: String) {
        busca = q.trim().ifBlank { null }
        subgrupo = null
        if (Iptv.atual.canais.isNotEmpty()) mostrar() else carregar(false)
    }

    private fun abrirBusca() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redondo(Cores.janela, dpf(28))
            setPadding(dp(32), dp(26), dp(32), dp(24))
        }
        box.addView(TextView(this).apply {
            text = "Buscar em $nomeTela"
            textSize = 24f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(12))
        })
        val bgCampo = redondo(Cores.campo, dpf(16))
        val campo = android.widget.EditText(this).apply {
            hint = "Nome do canal, filme ou série"
            setText(busca ?: "")
            textSize = 18f
            setTextColor(Cores.tinta)
            setHintTextColor(Cores.cinza)
            background = bgCampo
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnFocusChangeListener { _, has -> bgCampo.setStroke(if (has) dp(3) else 0, Cores.tinta) }
            setOnEditorActionListener { v, _, _ ->
                d.dismiss()
                buscar(v.text.toString())
                true
            }
        }
        box.addView(campo, LinearLayout.LayoutParams(dp(560), -2))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(18), 0, 0)
        }
        fun botao(t: String, escuro: Boolean, acao: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 17f
            val bg = redondo(if (escuro) Cores.tinta else Cores.campo, dpf(22))
            background = bg
            setTextColor(if (escuro) Cores.papel else Cores.tinta)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            isFocusable = true
            isClickable = true
            setOnClickListener { acao() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
                bg.setStroke(if (has) dp(3) else 0, if (escuro) Cores.campo else Cores.tinta)
            }
        }
        row.addView(botao("Buscar", true) {
            d.dismiss()
            buscar(campo.text.toString())
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        row.addView(botao("🎤  Falar", false) {
            d.dismiss()
            falar()
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        if (busca != null) {
            row.addView(botao("Limpar busca", false) {
                d.dismiss()
                busca = null
                mostrar()
            })
        }
        box.addView(row)
        d.setContentView(box)
        d.show()
        campo.requestFocus()
    }

    /** Busca por voz pelo microfone do controle. */
    private fun falar() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Fale o nome do canal ou filme")
        }
        try {
            startActivityForResult(i, VOZ)
        } catch (_: Throwable) {
            Toast.makeText(this, "Esta TV não tem busca por voz. Use o teclado ou o Controle TV.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VOZ && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { buscar(it) }
        }
    }

    private fun mostrarBusca(q: String) {
        val alvo = Iptv.semAcento(q)
        val achados = Perfil.filtrar(this, Iptv.atual.canais).let { l ->
            val esc = Historico.quebrados(this)
            l.filter { it.url !in esc && (Iptv.semAcento(it.nome).contains(alvo) || Iptv.semAcento(it.grupo).contains(alvo)) }
        }
        // Séries: um cartão por série
        episodios = if (ehVod) achados.filter { it.url.contains("/series/") }.groupBy { Iptv.nomeSerie(it.nome) }.filter { it.value.size > 1 } else emptyMap()
        val itens = achados.filter { episodios[Iptv.nomeSerie(it.nome)] == null } +
            episodios.map { (nome, eps) -> eps.first().copy(nome = nome) }
        titulo.text = "Busca"
        info.text = "$nomeTela · busca: \"$q\" · ${itens.size} resultados  ·  Voltar limpa a busca"
        if (itens.isEmpty()) {
            conteudo.addView(TextView(this).apply {
                text = "Não achei nada com \"$q\" em $nomeTela."
                textSize = 18f
                setTextColor(Cores.cinza)
            })
            return
        }
        Iptv.fila = itens
        conteudo.addView(secao("Resultados para \"$q\" · ${itens.size}"))
        gradeFavs = Iptv.atual.favoritos(this)
        gradeLarg = (resources.displayMetrics.widthPixels - dp(112) - dp(16) * (colunas - 1)) / colunas
        gradeLista = itens
        gradeFeitos = 0
        gradeLinha = null
        maisCartoes()
    }

    // ---------- prévia ao vivo ----------

    private fun agendarPrevia(lugar: FrameLayout, c: Iptv.Canal) {
        previaAgendada?.let { h.removeCallbacks(it) }
        if (ehVod || !previaLigada()) return
        val r = Runnable { mostrarPrevia(lugar, c) }
        previaAgendada = r
        h.postDelayed(r, 1500)
    }

    private fun mostrarPrevia(lugar: FrameLayout, c: Iptv.Canal) {
        if (isFinishing || !lugar.isAttachedToWindow) return
        val p = previa ?: ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36")
                        .setConnectTimeoutMs(8000)
                        .setReadTimeoutMs(10000),
                ),
            )
            .build().also {
                it.volume = 0f
                previa = it
            }
        val v = previaView ?: PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            setShutterBackgroundColor(Color.TRANSPARENT)
            previaView = this
        }
        (v.parent as? android.view.ViewGroup)?.removeView(v)
        v.player = p
        v.alpha = 0f
        lugar.addView(v, FrameLayout.LayoutParams(-1, -1))
        p.setMediaItem(MediaItem.Builder().setUri(Uri.parse(c.url)).apply {
            if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build())
        p.prepare()
        p.playWhenReady = true
        // Aparece devagar quando o vídeo começa
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                v.animate().alpha(1f).setDuration(300).start()
                p.removeListener(this)
            }
        })
    }

    private fun pararPrevia() {
        previaAgendada?.let { h.removeCallbacks(it) }
        previaAgendada = null
        previa?.stop()
        previaView?.let { v ->
            v.player = null
            (v.parent as? android.view.ViewGroup)?.removeView(v)
        }
    }

    // ---------- informações do filme (TMDB) ----------

    private fun mostrarInfo(c: Iptv.Canal, lista: List<Iptv.Canal>, i: Int) {
        if (Perfil.tmdb(this) == null) {
            Toast.makeText(this, "Mande a chave do TMDB pelo Controle TV para ver as informações", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Procurando ${Iptv.nomeSerie(c.nome)}…", Toast.LENGTH_SHORT).show()
        Perfil.info(this, c.nome) { inf ->
            if (isFinishing) return@info
            if (inf == null) {
                Toast.makeText(this, "Não achei informações deste título", Toast.LENGTH_SHORT).show()
                return@info
            }
            val d = android.app.Dialog(this)
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                background = redondo(Cores.janela, dpf(28))
                setPadding(dp(26), dp(26), dp(30), dp(26))
            }
            val cartaz = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = redondo(Cores.campo, dpf(16))
                clipToOutline = true
            }
            box.addView(cartaz, LinearLayout.LayoutParams(dp(200), dp(300)).apply { rightMargin = dp(26) })
            (inf.cartaz ?: c.logo)?.let { u -> Iptv.logo(u, 400) { b -> if (b != null) cartaz.setImageBitmap(b) } }
            val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            txt.addView(TextView(this).apply {
                text = inf.titulo
                textSize = 28f
                setTextColor(Cores.tinta)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            })
            txt.addView(TextView(this).apply {
                text = listOf(inf.tipo, inf.ano, if (inf.nota > 0) "★ %.1f".format(inf.nota) else "").filter { it.isNotBlank() }.joinToString("  ·  ")
                textSize = 16f
                setTextColor(Cores.cinza)
                setPadding(0, dp(6), 0, dp(12))
            })
            txt.addView(TextView(this).apply {
                text = inf.sinopse.ifBlank { "Sem sinopse em português." }
                textSize = 16f
                setTextColor(Cores.tinta)
                maxLines = 8
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f)
            })
            val assistir = TextView(this).apply {
                text = "▶  Assistir"
                textSize = 17f
                setTextColor(Cores.papel)
                typeface = Typeface.DEFAULT_BOLD
                background = redondo(Cores.tinta, dpf(22))
                setPadding(dp(24), dp(10), dp(26), dp(10))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    d.dismiss()
                    abrir(lista, i)
                }
            }
            txt.addView(assistir, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
            box.addView(txt, LinearLayout.LayoutParams(dp(560), -2))
            d.setContentView(box)
            d.show()
            assistir.requestFocus()
        }
    }

    private fun montar(): View {
        // Tema "Noite profunda": quase preto, com um brilho azulado no alto e um vermelho bem fraco embaixo
        val dm = resources.displayMetrics
        val brilhoAlto = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dm.widthPixels * 0.55f
            setGradientCenter(0.78f, 0.0f)
            colors = intArrayOf(0x40505A8C, 0x00000000)
        }
        val brilhoBaixo = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dm.widthPixels * 0.45f
            setGradientCenter(0.0f, 1.0f)
            colors = intArrayOf(0x1AFF3B30, 0x00000000)
        }
        val root = FrameLayout(this).apply {
            background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Cores.fundoTopo, Cores.fundoBase)),
                    brilhoAlto,
                    brilhoBaixo,
                ),
            )
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), dp(40))
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        info = TextView(this).apply {
            textSize = 14f
            setTextColor(Cores.cinza)
            text = nomeTela
        }
        titulo = TextView(this).apply {
            visibility = View.GONE
            text = Iptv.atual.abas[abaAtual]
        }
        // Topo: logo com a bolinha vermelha, troca de modo, Listas e relógio
        val tituloRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        val logo = android.text.SpannableString("●  Lumi TV")
        logo.setSpan(android.text.style.ForegroundColorSpan(Cores.vivo), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        logo.setSpan(android.text.style.RelativeSizeSpan(0.6f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tituloRow.addView(
            TextView(this).apply {
                text = logo
                textSize = 28f
                setTextColor(Cores.tinta)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                letterSpacing = -0.02f
            },
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(24) },
        )
        val troca = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = redondo(Cores.janela, dpf(26)).apply { setStroke(dp(1), Cores.linha) }
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        listOf("● TV ao vivo", "Filmes e Séries", "Pluto TV").forEachIndexed { m, nome ->
            val bg = redondo(Color.TRANSPARENT, dpf(20))
            val b = TextView(this).apply {
                text = nome
                textSize = 16f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = bg
                isFocusable = true
                isClickable = true
                tag = m
                setOnClickListener { trocarModo(m) }
                setOnFocusChangeListener { _, _ -> pintarModo() }
            }
            botoesModo += b
            troca.addView(b)
        }
        tituloRow.addView(troca)
        tituloRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // Botão Buscar (também pela tecla de busca/microfone do controle)
        val buscaBg = redondo(Cores.janela, dpf(26)).apply { setStroke(dp(1), Cores.linha) }
        tituloRow.addView(
            TextView(this).apply {
                text = "⌕  Buscar"
                textSize = 16f
                setTextColor(Cores.tinta)
                background = buscaBg
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isFocusable = true
                isClickable = true
                setOnClickListener { abrirBusca() }
                setOnFocusChangeListener { _, has ->
                    buscaBg.setColor(if (has) Cores.tinta else Cores.janela)
                    setTextColor(if (has) Cores.papel else Cores.tinta)
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) },
        )
        // Botão "Listas": colocar os links direto na TV
        val listasBg = redondo(Cores.janela, dpf(26)).apply { setStroke(dp(1), Cores.linha) }
        tituloRow.addView(
            TextView(this).apply {
                text = "＋ Listas"
                textSize = 16f
                setTextColor(Cores.tinta)
                background = listasBg
                setPadding(dp(20), dp(12), dp(20), dp(12))
                isFocusable = true
                isClickable = true
                setOnClickListener { abrirListas() }
                setOnFocusChangeListener { _, has ->
                    listasBg.setColor(if (has) Cores.tinta else Cores.janela)
                    setTextColor(if (has) Cores.papel else Cores.tinta)
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) },
        )
        relogio = TextView(this).apply {
            textSize = 30f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }
        tituloRow.addView(relogio, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(22) })
        col.addView(tituloRow)
        col.addView(info, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        h.post(tique)
        abasRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(18))
            clipChildren = false
        }
        col.addView(abasRow)
        montarAbas()
        pintarModo()

        conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        col.addView(conteudo)
        return root
    }

    private fun montarAbas() {
        abasRow.removeAllViews()
        abas.clear()
        Iptv.atual.abas.forEachIndexed { i, nome ->
            val bg = redondo(Color.TRANSPARENT, dpf(20))
            val t = TextView(this).apply {
                text = nome
                textSize = 16f
                setPadding(dp(18), dp(8), dp(18), dp(8))
                background = bg
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, has ->
                    if (has && abaAtual != i) {
                        abaAtual = i
                        subgrupo = null
                        mostrar()
                    }
                    pintarAbas()
                }
                setOnClickListener {
                    abaAtual = i
                    subgrupo = null
                    mostrar()
                    pintarAbas()
                }
            }
            abas += t
            abasRow.addView(t, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        }
        pintarAbas()
    }

    /** Troca entre TV ao vivo, Filmes e Séries e Pluto TV. */
    private fun trocarModo(m: Int) {
        if (m == modo) return
        modo = m
        Iptv.atual = Iptv.listas[m]
        abaAtual = 0
        subgrupo = null
        montarAbas()
        pintarModo()
        carregar(false)
    }

    private fun pintarModo() {
        for (b in botoesModo) {
            val bg = b.background as GradientDrawable
            val meu = b.tag == modo
            bg.setColor(if (meu) Cores.tinta else Color.TRANSPARENT)
            bg.setStroke(if (b.isFocused) dp(2) else 0, if (meu) Cores.vivo else Cores.tinta)
            b.setTextColor(if (meu) Cores.papel else if (b.isFocused) Cores.tinta else Cores.cinza)
            b.typeface = if (meu) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            b.animate().scaleX(if (b.isFocused) 1.06f else 1f).scaleY(if (b.isFocused) 1.06f else 1f).setDuration(120).start()
        }
    }

    // ---------- listas (links colocados na própria TV) ----------

    private fun abrirListas() {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = redondo(Cores.janela, dpf(28))
            setPadding(dp(36), dp(28), dp(36), dp(26))
        }
        box.addView(TextView(this).apply {
            text = "Suas listas"
            textSize = 26f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Cole ou digite o link (M3U) e aperte Salvar. Pode ser o mesmo link nas duas: o app separa sozinho."
            textSize = 15f
            setTextColor(Cores.cinza)
            setPadding(0, dp(4), 0, dp(14))
        })

        fun campo(rotulo: String, atual: String?): android.widget.EditText {
            box.addView(TextView(this).apply {
                text = rotulo
                textSize = 15f
                setTextColor(Cores.tinta)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), dp(8), 0, dp(6))
            })
            val bg = redondo(Cores.campo, dpf(16))
            val e = android.widget.EditText(this).apply {
                setText(atual ?: "")
                hint = "http://…"
                textSize = 17f
                setTextColor(Cores.tinta)
                setHintTextColor(Cores.cinza)
                background = bg
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setOnFocusChangeListener { _, has -> bg.setStroke(if (has) dp(3) else 0, Cores.tinta) }
            }
            box.addView(e, LinearLayout.LayoutParams(dp(640), -2))
            return e
        }
        val aoVivo = campo("● TV ao vivo", Iptv.aoVivo.link(this))
        val vod = campo("Filmes e Séries", Iptv.vod.link(this))

        fun botao(t: String, escuro: Boolean = false, acao: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 17f
            val bg = redondo(if (escuro) Cores.tinta else Cores.campo, dpf(22))
            background = bg
            setTextColor(if (escuro) Cores.papel else Cores.tinta)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            isFocusable = true
            isClickable = true
            setOnClickListener { acao() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(120).start()
                bg.setStroke(if (has) dp(3) else 0, if (escuro) Cores.campo else Cores.tinta)
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(22), 0, 0)
            clipChildren = false
        }
        row.addView(botao("Salvar", escuro = true) {
            fun ok(l: String) = l.isBlank() || l.startsWith("http")
            val a = aoVivo.text.toString().trim()
            val b = vod.text.toString().trim()
            if (!ok(a) || !ok(b)) {
                Toast.makeText(this, "O link precisa começar com http", Toast.LENGTH_LONG).show()
                return@botao
            }
            if (a != (Iptv.aoVivo.link(this) ?: "")) Iptv.aoVivo.salvarLink(this, a)
            if (b != (Iptv.vod.link(this) ?: "")) Iptv.vod.salvarLink(this, b)
            Toast.makeText(this, "Listas salvas", Toast.LENGTH_SHORT).show()
            d.dismiss()
            carregar(true)
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        row.addView(botao("Minha lista grátis") {
            aoVivo.setText("https://raw.githubusercontent.com/XProzzinho/TV-online/main/listas/minha.m3u")
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        row.addView(botao("Cancelar") { d.dismiss() })
        box.addView(row)
        // Abrir direto no último canal
        fun textoUltimo() = "Abrir no último canal: " + if (Iptv.atual.abrirNoUltimo(this)) "LIGADO" else "desligado"
        val ult = botao(textoUltimo()) {}
        ult.setOnClickListener {
            Iptv.atual.mudarAbrirNoUltimo(this, !Iptv.atual.abrirNoUltimo(this))
            ult.text = textoUltimo()
        }
        box.addView(ult, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })

        // Canais que não abrem: ficam escondidos por 24 horas
        fun textoQuebrados() = "Esconder canais que não abrem: " +
            if (Historico.esconderQuebrados(this)) "LIGADO (${Historico.quebrados(this).size} escondidos)" else "desligado"
        val qb = botao(textoQuebrados()) {}
        qb.setOnClickListener {
            val agora = !Historico.esconderQuebrados(this)
            Historico.mudarEsconderQuebrados(this, agora)
            if (!agora) Historico.limparQuebrados(this)
            qb.text = textoQuebrados()
        }
        box.addView(qb, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        // Prévia ao vivo nos cartões
        fun textoPrevia() = "Prévia ao vivo no cartão: " + if (previaLigada()) "LIGADA" else "desligada"
        val pv = botao(textoPrevia()) {}
        pv.setOnClickListener {
            getSharedPreferences("inicio", MODE_PRIVATE).edit().putBoolean("previa", !previaLigada()).apply()
            pv.text = textoPrevia()
        }
        box.addView(pv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        // Canais adultos: só com PIN
        fun textoAdulto() = "Canais adultos: " + if (Perfil.adultosLiberados) "LIBERADOS (até fechar o app)" else "bloqueados (PIN)"
        val ad = botao(textoAdulto()) {}
        ad.setOnClickListener {
            if (Perfil.adultosLiberados) {
                Perfil.adultosLiberados = false
                ad.text = textoAdulto()
                return@setOnClickListener
            }
            if (Perfil.infantil(this)) {
                Toast.makeText(this, "Não dá no perfil infantil", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pedirPin {
                Perfil.adultosLiberados = true
                ad.text = textoAdulto()
            }
        }
        box.addView(ad, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        // Várias listas: guardar a de TV ao vivo e trocar entre elas
        box.addView(TextView(this).apply {
            text = "Listas guardadas (TV ao vivo)"
            textSize = 15f
            setTextColor(Cores.tinta)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(18), 0, dp(6))
        })
        val guardadas = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        val hsg = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
            addView(guardadas)
        }
        fun montarGuardadas() {
            guardadas.removeAllViews()
            guardadas.addView(botao("＋ Guardar esta") {
                val u = aoVivo.text.toString().trim()
                if (!u.startsWith("http")) {
                    Toast.makeText(this, "Coloque um link no campo TV ao vivo", Toast.LENGTH_SHORT).show()
                    return@botao
                }
                val nome = try { Uri.parse(u).host?.removePrefix("www.") } catch (_: Throwable) { null } ?: "Lista"
                Historico.guardarLista(this, if (u.contains("minha.m3u")) "Minha lista grátis" else nome, u)
                montarGuardadas()
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            Historico.listasGuardadas(this).forEach { (nome, u) ->
                val b = botao(nome) { aoVivo.setText(u) }
                b.setOnLongClickListener {
                    Historico.apagarLista(this, u)
                    Toast.makeText(this, "Lista \"$nome\" apagada", Toast.LENGTH_SHORT).show()
                    montarGuardadas()
                    true
                }
                guardadas.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            }
        }
        montarGuardadas()
        box.addView(hsg, LinearLayout.LayoutParams(dp(640), -2))
        box.addView(TextView(this).apply {
            text = "OK numa lista guardada põe o link no campo (depois aperte Salvar). Segure OK para apagar."
            textSize = 13f
            setTextColor(Cores.cinza)
            setPadding(dp(4), dp(6), 0, 0)
        })
        d.setContentView(ScrollView(this).apply { addView(box) })
        d.show()
        aoVivo.requestFocus()
    }

    /** Pede o PIN de 4 números (na primeira vez, cria: digita duas vezes). */
    private fun pedirPin(ok: () -> Unit) {
        val salvo = Perfil.pin(this)
        var primeiro: String? = null
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = redondo(Cores.janela, dpf(28))
            setPadding(dp(36), dp(26), dp(36), dp(26))
        }
        val tit = TextView(this).apply {
            text = if (salvo == null) "Crie um PIN de 4 números" else "Digite o PIN"
            textSize = 22f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(14))
        }
        box.addView(tit)
        val campo = android.widget.EditText(this).apply {
            textSize = 30f
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            setTextColor(Cores.tinta)
            background = redondo(Cores.campo, dpf(16))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        box.addView(campo, LinearLayout.LayoutParams(dp(260), -2))
        campo.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val t = e?.toString().orEmpty()
                if (t.length < 4) return
                when {
                    salvo != null && t == salvo -> {
                        d.dismiss()
                        ok()
                    }
                    salvo != null -> {
                        Toast.makeText(this@IptvActivity, "PIN errado", Toast.LENGTH_SHORT).show()
                        campo.setText("")
                    }
                    primeiro == null -> {
                        primeiro = t
                        tit.text = "Digite o PIN de novo"
                        campo.setText("")
                    }
                    primeiro == t -> {
                        Perfil.criarPin(this@IptvActivity, t)
                        Toast.makeText(this@IptvActivity, "PIN criado", Toast.LENGTH_SHORT).show()
                        d.dismiss()
                        ok()
                    }
                    else -> {
                        Toast.makeText(this@IptvActivity, "Os PINs não batem, comece de novo", Toast.LENGTH_SHORT).show()
                        primeiro = null
                        tit.text = "Crie um PIN de 4 números"
                        campo.setText("")
                    }
                }
            }
        })
        d.setContentView(box)
        d.show()
        campo.requestFocus()
    }

    // ---------- versão nova ----------

    private fun avisarAtualizacao(v: Atualizacao.Versao) {
        val d = android.app.Dialog(this)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = redondo(Cores.janela, dpf(28))
            setPadding(dp(40), dp(30), dp(40), dp(28))
        }
        box.addView(TextView(this).apply {
            text = "Versão nova!"
            textSize = 26f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        val info = TextView(this).apply {
            text = "${v.nome} está disponível.\nVocê tem a versão 1.${BuildConfig.VERSION_CODE}."
            textSize = 16f
            setTextColor(Cores.cinza)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(18))
        }
        box.addView(info)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun botao(t: String, acao: () -> Unit) = TextView(this).apply {
            text = t
            textSize = 18f
            val bg = redondo(Cores.campo, dpf(22))
            background = bg
            setTextColor(Cores.tinta)
            setPadding(dp(26), dp(10), dp(26), dp(10))
            isFocusable = true
            isClickable = true
            setOnClickListener { acao() }
            setOnFocusChangeListener { _, has ->
                bg.setColor(if (has) Cores.tinta else Cores.campo)
                setTextColor(if (has) Cores.papel else Cores.tinta)
            }
        }
        var baixando = false
        val atualizar = botao("Atualizar") {
            if (baixando) return@botao
            if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                Toast.makeText(this, "Permita o Lumi TV instalar apps e aperte Atualizar de novo", Toast.LENGTH_LONG).show()
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                } catch (_: Throwable) {
                }
                return@botao
            }
            baixando = true
            Atualizacao.baixar(this, v, progresso = { info.text = "Baixando… $it%" }) { f ->
                baixando = false
                if (f == null) {
                    info.text = "Não consegui baixar. Tente de novo."
                } else {
                    d.dismiss()
                    Atualizacao.instalar(this)
                }
            }
        }
        row.addView(atualizar, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        row.addView(botao("Depois") {
            Atualizacao.adiar(this, v)
            d.dismiss()
        })
        box.addView(row)
        d.setContentView(box)
        d.show()
        atualizar.requestFocus()
    }

    /** Mesmas cores das abas da tela inicial: preta selecionada, cinza clarinho na atual. */
    private fun pintarAbas() {
        abas.forEachIndexed { i, t ->
            val bg = t.background as GradientDrawable
            val on = t.isFocused || i == abaAtual
            bg.setColor(if (on) Cores.tinta else Color.TRANSPARENT)
            bg.setStroke(dp(1), if (on) Cores.tinta else 0x29FFFFFF)
            t.setTextColor(if (on) Cores.papel else Cores.cinza)
            t.typeface = if (i == abaAtual) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.animate().scaleX(if (t.isFocused) 1.08f else 1f).scaleY(if (t.isFocused) 1.08f else 1f).setDuration(120).start()
        }
    }

    private fun carregar(forcar: Boolean) {
        info.text = "$nomeTela · carregando a lista…"
        Iptv.atual.carregar(this, forcar) { erro ->
            if (isFinishing) return@carregar
            when (erro) {
                null -> {
                    mostrar()
                    abrirUltimoSeLigado()
                }
                "sem lista" -> vazio(
                    (if (ehVod) "Nenhuma lista de filmes e séries ainda.\n" else "Nenhuma lista de canais ainda.\n") +
                        "Aperte OK para colocar o link da lista aqui na TV (ou mande pelo Controle TV).",
                )
                else -> vazio("$erro. Confira a internet e o link da lista, e aperte OK para tentar de novo.")
            }
        }
    }

    private fun vazio(msg: String) {
        info.text = nomeTela
        conteudo.removeAllViews()
        val bg = redondo(Cores.janela, dpf(22))
        val t = TextView(this).apply {
            text = msg
            textSize = 20f
            setTextColor(Cores.tinta)
            background = bg
            setPadding(dp(28), dp(24), dp(28), dp(24))
            isFocusable = true
            isClickable = true
            setOnClickListener { if (Iptv.atual.link(this@IptvActivity) == null) abrirListas() else carregar(true) }
        }
        conteudo.addView(t, LinearLayout.LayoutParams(-1, -2))
    }

    private fun secao(texto: String) = TextView(this).apply {
        text = texto
        textSize = 20f
        setTextColor(Cores.tinta)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setPadding(dp(2), dp(4), 0, dp(12))
    }

    // ---------- conteúdo da aba ----------

    private fun mostrar() {
        girar?.let { h.removeCallbacks(it) }
        pararPrevia()
        conteudo.removeAllViews()
        busca?.let {
            mostrarBusca(it)
            return
        }
        val nomeAba = Iptv.atual.abas[abaAtual]
        titulo.text = nomeAba
        val escondidos = Historico.quebrados(this)
        val brutos = Perfil.filtrar(this, Iptv.atual.daAba(this, abaAtual)).let { l -> if (escondidos.isEmpty()) l else l.filter { it.url !in escondidos } }
        // Séries: cada série aparece uma vez (o cartão toca os episódios dela)
        episodios = if (ehVod && abaAtual == 1) brutos.groupBy { Iptv.nomeSerie(it.nome) } else emptyMap()
        val daAba = if (episodios.isNotEmpty()) {
            episodios.map { (nome, eps) -> eps.first().copy(nome = nome) }
        } else {
            brutos
        }
        info.text = "$nomeTela · ${Iptv.atual.canais.size} " + (if (ehVod) "títulos" else "canais") +
            (Perfil.nome(this)?.let { "  ·  Perfil: $it" + if (Perfil.infantil(this)) " (infantil)" else "" } ?: "")
        if (daAba.isEmpty()) {
            conteudo.addView(
                TextView(this).apply {
                    text = if (!ehVod && abaAtual == 0) {
                        "Assistindo um canal, segure OK para ele aparecer aqui nos Destaques."
                    } else {
                        "Sua lista não tem nada de $nomeAba."
                    }
                    textSize = 18f
                    setTextColor(Cores.cinza)
                },
            )
            return
        }

        // 1) Destaque grande + dois cartões altos
        if (daAba.size >= 3) {
            conteudo.addView(linhaDestaque(daAba), LinearLayout.LayoutParams(-1, dp(270)).apply { bottomMargin = dp(26) })
        }

        // 1b) Continuar assistindo (filmes) ou Vistos por último (canais)
        if (abaAtual == 0) {
            val porUrl = Iptv.atual.canais.associateBy { it.url }
            val itens = if (ehVod) {
                Perfil.filtrar(this, Historico.continuar(this).filter { it.url in porUrl }.map { porUrl[it.url]!! })
            } else {
                Perfil.filtrar(this, Historico.ultimos(this, Iptv.atual.id).mapNotNull { porUrl[it] }).filter { it.url !in escondidos }
            }
            if (itens.isNotEmpty()) linhaMini(if (ehVod) "Continuar assistindo" else "Vistos por último", itens.take(12))
        }

        // 2) Os grupos da lista dentro desta aba, em blocos (como Gênero, Nacional, No ar)
        val grupos = daAba.map { it.grupo }.distinct()
        if (grupos.size > 1) {
            conteudo.addView(secao("Categorias"))
            val hs = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                setPadding(dp(4), dp(4), dp(4), dp(8))
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
            }
            hs.addView(row)
            (listOf<String?>(null) + grupos).forEach { g ->
                row.addView(blocoGrupo(g, daAba.count { g == null || it.grupo == g }), LinearLayout.LayoutParams(dp(170), dp(84)).apply { rightMargin = dp(14) })
            }
            conteudo.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        }

        // 3) Grade com os canais (do grupo escolhido ou todos da aba)
        val doGrupo = if (subgrupo == null) daAba else daAba.filter { it.grupo == subgrupo }
        val ordem = Historico.ordem(this, Iptv.atual.id)
        val lista = when (ordem) {
            1 -> doGrupo.sortedBy { Iptv.semAcento(it.nome) }
            2 -> Historico.vezes(this, Iptv.atual.id).let { v -> doGrupo.sortedByDescending { v[it.url] ?: 0 } }
            else -> doGrupo
        }
        Iptv.fila = lista
        conteudo.addView(cabecalhoGrade((subgrupo ?: if (ehVod) "Todos" else "Todos os canais") + " · ${lista.size}", ordem))
        gradeFavs = Iptv.atual.favoritos(this)
        gradeLarg = (resources.displayMetrics.widthPixels - dp(112) - dp(16) * (colunas - 1)) / colunas
        gradeLista = lista
        gradeFeitos = 0
        gradeLinha = null
        maisCartoes()
    }

    /** Põe mais um pedaço de cartões na grade (chamado de novo quando você chega perto do fim). */
    private fun maisCartoes() {
        val lista = gradeLista
        val ate = (gradeFeitos + PEDACO).coerceAtMost(lista.size)
        val comGuia = !ehVod && Guia.pronto
        for (i in gradeFeitos until ate) {
            if (i % colunas == 0) {
                gradeLinha = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                conteudo.addView(gradeLinha, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
            val alt = gradeLarg * when {
                ehVod -> 1.5f
                comGuia -> 0.92f
                else -> 0.72f
            }
            val lp = LinearLayout.LayoutParams(gradeLarg, alt.toInt())
            if (i % colunas != colunas - 1) lp.rightMargin = dp(16)
            gradeLinha!!.addView(cartao(lista[i], lista, i, lista[i].url in gradeFavs), lp)
        }
        gradeFeitos = ate
    }

    /** Bloco de categoria: branco com o nome e a quantidade; o escolhido fica com contorno. */
    private fun blocoGrupo(g: String?, qtd: Int): View {
        val escolhido = g == subgrupo
        val bg = redondo(Cores.janela, dpf(18)).apply { setStroke(if (escolhido) dp(2) else dp(1), if (escolhido) Cores.tinta else Cores.linha) }
        val nome = TextView(this).apply {
            text = g ?: "Tudo"
            textSize = 16f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val num = TextView(this).apply {
            text = "$qtd canais"
            textSize = 13f
            setTextColor(Cores.cinza)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = dpf(2)
            setPadding(dp(16), 0, dp(12), 0)
            isFocusable = true
            isClickable = true
            addView(nome)
            addView(num)
            setOnClickListener {
                subgrupo = g
                mostrar()
            }
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) Cores.tinta else Cores.janela)
                nome.setTextColor(if (has) Cores.papel else Cores.tinta)
                num.setTextColor(if (has) Cores.papel else Cores.cinza)
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
            }
        }
    }

    /** Destaque grande (passa entre os 4 primeiros canais) e dois cartões altos à direita. */
    private fun linhaDestaque(lista: List<Iptv.Canal>): View {
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        val roda = lista.take(4)
        var atual = 0
        val bg = redondo(Cores.janela, dpf(22)).apply { setStroke(dp(1), Cores.linha) }
        val anel = GradientDrawable().apply {
            cornerRadius = dpf(22)
            setColor(Color.TRANSPARENT)
        }
        val logo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = redondo(Cores.campo, dpf(18))
            setPadding(dp(22), dp(22), dp(22), dp(22))
        }
        val aoVivo = TextView(this).apply {
            text = if (ehVod) "▶ ASSISTIR" else "● AO VIVO"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = redondo(if (ehVod) Cores.tinta else Cores.vivo, dpf(8))
            if (ehVod) setTextColor(Cores.papel)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        val nome = TextView(this).apply {
            textSize = 30f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(10), 0, dp(4))
        }
        val grupo = TextView(this).apply {
            textSize = 16f
            setTextColor(Cores.cinza)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        // Botão branco "▶ Assistir" (o destaque inteiro é o botão: OK abre)
        val dica = TextView(this).apply {
            text = "▶  Assistir"
            textSize = 15f
            setTextColor(Cores.papel)
            typeface = Typeface.DEFAULT_BOLD
            background = redondo(Cores.tinta, dpf(22))
            setPadding(dp(20), dp(9), dp(22), dp(9))
        }
        val pontos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bolinhas = roda.map {
            View(this).also { v -> pontos.addView(v, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(6) }) }
        }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(26), dp(6), dp(16), 0)
            addView(aoVivo, LinearLayout.LayoutParams(-2, -2))
            addView(nome)
            addView(grupo)
            addView(dica, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        }
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (ehVod) {
                logo.scaleType = ImageView.ScaleType.CENTER_CROP
                logo.setPadding(0, 0, 0, 0)
                logo.clipToOutline = true
            }
            addView(logo, LinearLayout.LayoutParams(if (ehVod) dp(150) else dp(250), -1))
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val destaque = FrameLayout(this).apply {
            background = bg
            foreground = anel
            elevation = dpf(2)
            isFocusable = true
            isClickable = true
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(corpo, FrameLayout.LayoutParams(-1, -1))
            addView(pontos, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END))
        }
        fun mostrarDestaque(i: Int) {
            atual = i
            val c = roda[i]
            nome.text = c.nome
            val (ag, dep) = if (ehVod) null to null else Guia.agora(c)
            grupo.text = if (ag != null) {
                "Agora: ${ag.titulo}  ·  até ${Guia.hora(ag.fim)}" + (dep?.let { "\nDepois: ${it.titulo}" } ?: "")
            } else {
                c.grupo
            }
            logo.setImageDrawable(null)
            c.logo?.let { u -> Iptv.logo(u) { b -> if (b != null && roda[atual] == c) logo.setImageBitmap(b) } }
            bolinhas.forEachIndexed { k, v ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (k == i) 0xFFFF3B30.toInt() else Cores.campo)
                }
            }
        }
        mostrarDestaque(0)
        // Passa sozinho a cada 6 segundos (para enquanto estiver selecionado)
        val r = object : Runnable {
            override fun run() {
                if (!destaque.isFocused && roda.size > 1) mostrarDestaque((atual + 1) % roda.size)
                h.postDelayed(this, 6000)
            }
        }
        girar = r
        h.postDelayed(r, 6000)
        destaque.setOnClickListener { abrir(lista, atual) }
        destaque.setOnFocusChangeListener { v, has ->
            anel.setStroke(if (has) dp(4) else 0, Cores.tinta)
            v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                .translationZ(if (has) dpf(8) else 0f).setDuration(140).start()
        }
        linha.addView(destaque, LinearLayout.LayoutParams(0, -1, 2.3f).apply { rightMargin = dp(22) })
        // Coluna "A seguir nos favoritos" (ou os próximos canais) ao lado do destaque
        val favs = Iptv.atual.favoritos(this)
        val doLado = lista.filter { it.url in favs && it !in roda }.ifEmpty { lista.drop(roda.size) }.take(3)
        val lado = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        lado.addView(TextView(this).apply {
            text = if (lista.any { it.url in favs }) "A SEGUIR NOS FAVORITOS" else "MAIS CANAIS"
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(Cores.cinza)
            setPadding(dp(2), 0, 0, dp(10))
        })
        doLado.forEach { c ->
            val rbg = redondo(Cores.janela, dpf(16)).apply { setStroke(dp(1), Cores.linha) }
            val mini = ImageView(this).apply {
                scaleType = if (ehVod) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
                background = redondo(Cores.campo, dpf(10))
                setPadding(dp(6), dp(4), dp(6), dp(4))
                clipToOutline = true
            }
            val nm = TextView(this).apply {
                text = c.nome
                textSize = 15f
                setTextColor(Cores.tinta)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val gr = TextView(this).apply {
                text = c.grupo
                textSize = 12f
                setTextColor(Cores.cinza)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val txt = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(nm)
                addView(gr)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rbg
                setPadding(dp(10), dp(10), dp(12), dp(10))
                isFocusable = true
                isClickable = true
                addView(mini, LinearLayout.LayoutParams(dp(64), dp(44)))
                addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { abrir(lista, lista.indexOf(c)) }
                setOnFocusChangeListener { v, has ->
                    rbg.setStroke(if (has) dp(3) else dp(1), if (has) Cores.tinta else Cores.linha)
                    v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).setDuration(120).start()
                }
            }
            c.logo?.let { u -> Iptv.logo(u) { b -> if (b != null) mini.setImageBitmap(b) } }
            lado.addView(row, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(10) })
        }
        linha.addView(lado, LinearLayout.LayoutParams(0, -1, 1.1f))
        return linha
    }

    /** Título da grade com o botão de ordem (lista, A a Z, mais vistos). */
    private fun cabecalhoGrade(texto: String, ordem: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        row.addView(secao(texto))
        val nomes = listOf("Ordem da lista", "A a Z", "Mais vistos")
        val bg = redondo(Cores.janela, dpf(18)).apply { setStroke(dp(1), Cores.linha) }
        row.addView(
            TextView(this).apply {
                text = "⇅  " + nomes[ordem]
                textSize = 14f
                setTextColor(Cores.tinta)
                background = bg
                setPadding(dp(14), dp(6), dp(16), dp(6))
                isFocusable = true
                isClickable = true
                setOnClickListener {
                    Historico.mudarOrdem(this@IptvActivity, Iptv.atual.id, (ordem + 1) % nomes.size)
                    mostrar()
                }
                setOnFocusChangeListener { _, has ->
                    bg.setColor(if (has) Cores.tinta else Cores.janela)
                    setTextColor(if (has) Cores.papel else Cores.tinta)
                }
            },
            LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(16)
                bottomMargin = dp(8)
            },
        )
        return row
    }

    /** Fileira deslizante de cartões (Continuar assistindo / Vistos por último). */
    private fun linhaMini(titulo: String, itens: List<Iptv.Canal>) {
        conteudo.addView(secao(titulo))
        val hs = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(10), dp(4), dp(14))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }
        hs.addView(row)
        val favs = Iptv.atual.favoritos(this)
        val larg = (resources.displayMetrics.widthPixels - dp(112) - dp(16) * (colunas - 1)) / colunas
        val alt = (larg * (if (ehVod) 1.5f else if (Guia.pronto) 0.92f else 0.72f)).toInt()
        itens.forEachIndexed { i, c ->
            row.addView(cartao(c, itens, i, c.url in favs), LinearLayout.LayoutParams(larg, alt).apply { rightMargin = dp(16) })
        }
        conteudo.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    /** Barrinha fina: cinza no fundo e vermelha até onde o programa já passou. */
    private fun barraProgresso(p: Float): View {
        val fundo = FrameLayout(this).apply { background = redondo(Cores.campo, dpf(2)) }
        val cheio = View(this).apply { background = redondo(Cores.vivo, dpf(2)) }
        fundo.addView(cheio, FrameLayout.LayoutParams(0, -1))
        fundo.addOnLayoutChangeListener { v, l, _, rr, _, _, _, _, _ ->
            val w = ((rr - l) * p).toInt()
            if (cheio.layoutParams.width != w) {
                cheio.layoutParams = FrameLayout.LayoutParams(w, -1)
                v.post { cheio.requestLayout() }
            }
        }
        return fundo
    }

    private fun cartao(c: Iptv.Canal, lista: List<Iptv.Canal>, i: Int, fav: Boolean): View {
        val bg = redondo(Cores.janela, dpf(18)).apply { setStroke(dp(1), Cores.linha) }
        val anel = GradientDrawable().apply {
            cornerRadius = dpf(18)
            setColor(Color.TRANSPARENT)
        }
        val img = ImageView(this).apply {
            // Filmes e séries: o cartaz enche o cartão; canais: o logo no meio
            if (ehVod) {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) {
                        o.setRoundRect(0, 0, v.width, v.height + dp(18), dpf(18))
                    }
                }
                clipToOutline = true
            } else {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(14), dp(12), dp(14), dp(4))
            }
        }
        // Sem logo: a primeira letra grande
        val letra = TextView(this).apply {
            text = c.nome.trim().take(1).uppercase()
            textSize = 30f
            setTextColor(Cores.cinza)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        val nome = TextView(this).apply {
            text = (if (fav) "★ " else "") + c.nome
            textSize = 14f
            setTextColor(Cores.tinta)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(if (ehVod) 8 else 0), dp(8), dp(10))
        }
        // Guia: o programa de agora e quanto já passou
        val (agora, _) = if (ehVod) null to null else Guia.agora(c)
        val prog = agora?.let { p ->
            TextView(this).apply {
                text = p.titulo
                textSize = 12f
                setTextColor(Cores.cinza)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), dp(6))
            }
        }
        val visto = if (ehVod) Historico.fracao(this, c.url) else -1f
        val barra = agora?.let { p -> barraProgresso(p.progresso()) }
        val topo = FrameLayout(this)
        topo.addView(letra, FrameLayout.LayoutParams(-1, -1))
        topo.addView(img, FrameLayout.LayoutParams(-1, -1))
        // Número do canal no canto (TV ao vivo)
        if (!ehVod) {
            topo.addView(
                TextView(this).apply {
                    text = "%02d".format(i + 1)
                    textSize = 11f
                    setTextColor(Cores.cinza)
                    setPadding(0, dp(8), dp(10), 0)
                },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END),
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            foreground = anel
            elevation = dpf(2)
            isFocusable = true
            isClickable = true
            addView(topo, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nome)
            if (prog != null) {
                nome.setPadding(dp(8), 0, dp(8), dp(2))
                addView(prog)
                addView(barra!!, LinearLayout.LayoutParams(-1, dp(4)).apply {
                    leftMargin = dp(14)
                    rightMargin = dp(14)
                    bottomMargin = dp(10)
                })
            }
            if (visto >= 0f) {
                addView(barraProgresso(visto), LinearLayout.LayoutParams(-1, dp(4)).apply {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                    bottomMargin = dp(8)
                })
            }
            setOnClickListener { abrir(lista, i) }
            // Filmes e séries: segurar OK mostra as informações (TMDB)
            if (ehVod) {
                setOnLongClickListener {
                    mostrarInfo(c, lista, i)
                    true
                }
            }
            setOnFocusChangeListener { v, has ->
                // Chegou perto do fim do que já foi montado: monta mais
                if (has && lista === gradeLista && i >= gradeFeitos - colunas * 2 && gradeFeitos < gradeLista.size) {
                    v.post { maisCartoes() }
                }
                if (has) agendarPrevia(topo, c) else if (previaView?.parent === topo || previaAgendada != null) pararPrevia()
                anel.setStroke(if (has) dp(3) else 0, Cores.tinta)
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                    .translationY(if (has) -dpf(8) else 0f)
                    .translationZ(if (has) dpf(14) else 0f).setDuration(180).start()
            }
        }
        c.logo?.let { u ->
            Iptv.logo(u) { b ->
                if (b != null) {
                    img.alpha = 0f
                    img.setImageBitmap(b)
                    img.animate().alpha(1f).setDuration(220).start()
                    letra.visibility = View.INVISIBLE
                }
            }
        }
        return card
    }
}

