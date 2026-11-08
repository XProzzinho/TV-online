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

    private lateinit var conteudo: LinearLayout
    private lateinit var info: TextView
    private lateinit var titulo: TextView
    private val abas = ArrayList<TextView>()
    private var abaAtual = 0 // TV ao vivo começa em Destaques; Filmes e Séries em Filmes
    private val ehVod get() = Iptv.atual.vod
    private val nomeTela get() = if (ehVod) "Filmes e Séries" else "TV ao vivo"
    private var subgrupo: String? = null
    private val colunas = 5
    private val h = Handler(Looper.getMainLooper())
    private var girar: Runnable? = null
    private var episodios: Map<String, List<Iptv.Canal>> = emptyMap()
    private var modoVod = false
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
        Iptv.atual = if (intent.getStringExtra("modo") == "vod") Iptv.vod else Iptv.aoVivo
        abaAtual = intent.getIntExtra("aba", 0).coerceIn(0, Iptv.atual.abas.size - 1)
        modoVod = Iptv.atual.vod
        setContentView(montar())
        carregar(false)
    }

    override fun onResume() {
        super.onResume()
        Iptv.atual = if (modoVod) Iptv.vod else Iptv.aoVivo
        Atualizacao.conferir(this) { v -> if (!isFinishing) avisarAtualizacao(v) }
        // Volta do player: atualiza os favoritos
        if (Iptv.atual.canais.isNotEmpty()) mostrar()
    }

    override fun onPause() {
        super.onPause()
        girar?.let { h.removeCallbacks(it) }
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
        listOf("● TV ao vivo" to false, "Filmes e Séries" to true).forEach { (nome, vod) ->
            val bg = redondo(Color.TRANSPARENT, dpf(20))
            val b = TextView(this).apply {
                text = nome
                textSize = 16f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                background = bg
                isFocusable = true
                isClickable = true
                tag = vod
                setOnClickListener { trocarModo(vod) }
                setOnFocusChangeListener { _, _ -> pintarModo() }
            }
            botoesModo += b
            troca.addView(b)
        }
        tituloRow.addView(troca)
        tituloRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
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

    /** Troca entre TV ao vivo e Filmes e Séries. */
    private fun trocarModo(vod: Boolean) {
        if (vod == modoVod) return
        modoVod = vod
        Iptv.atual = if (vod) Iptv.vod else Iptv.aoVivo
        abaAtual = 0
        subgrupo = null
        montarAbas()
        pintarModo()
        carregar(false)
    }

    private fun pintarModo() {
        for (b in botoesModo) {
            val bg = b.background as GradientDrawable
            val meu = b.tag == modoVod
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
        row.addView(botao("Canais abertos do Brasil") {
            aoVivo.setText("https://iptv-org.github.io/iptv/countries/br.m3u")
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(12) })
        row.addView(botao("Cancelar") { d.dismiss() })
        box.addView(row)
        d.setContentView(box)
        d.show()
        aoVivo.requestFocus()
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
                null -> mostrar()
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
        conteudo.removeAllViews()
        val nomeAba = Iptv.atual.abas[abaAtual]
        titulo.text = nomeAba
        val brutos = Iptv.atual.daAba(this, abaAtual)
        // Séries: cada série aparece uma vez (o cartão toca os episódios dela)
        episodios = if (ehVod && abaAtual == 1) brutos.groupBy { Iptv.nomeSerie(it.nome) } else emptyMap()
        val daAba = if (episodios.isNotEmpty()) {
            episodios.map { (nome, eps) -> eps.first().copy(nome = nome) }
        } else {
            brutos
        }
        info.text = "$nomeTela · ${Iptv.atual.canais.size} " + if (ehVod) "títulos" else "canais"
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
        val lista = if (subgrupo == null) daAba else daAba.filter { it.grupo == subgrupo }
        Iptv.fila = lista
        conteudo.addView(secao((subgrupo ?: "Todos os canais") + " · ${lista.size}"))
        val favs = Iptv.atual.favoritos(this)
        val larg = (resources.displayMetrics.widthPixels - dp(112) - dp(16) * (colunas - 1)) / colunas
        var linha: LinearLayout? = null
        lista.take(600).forEachIndexed { i, c ->
            if (i % colunas == 0) {
                linha = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    clipChildren = false
                    clipToPadding = false
                }
                conteudo.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            }
            val lp = LinearLayout.LayoutParams(larg, (larg * (if (ehVod) 1.5f else 0.72f)).toInt())
            if (i % colunas != colunas - 1) lp.rightMargin = dp(16)
            linha!!.addView(cartao(c, lista, i, c.url in favs), lp)
        }
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
            grupo.text = c.grupo
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
            setOnClickListener { abrir(lista, i) }
            setOnFocusChangeListener { v, has ->
                anel.setStroke(if (has) dp(3) else 0, Cores.tinta)
                v.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f)
                    .translationY(if (has) -dpf(8) else 0f)
                    .translationZ(if (has) dpf(14) else 0f).setDuration(180).start()
            }
        }
        c.logo?.let { u ->
            Iptv.logo(u) { b ->
                if (b != null) {
                    img.setImageBitmap(b)
                    letra.visibility = View.INVISIBLE
                }
            }
        }
        return card
    }
}

/**
 * Assistindo um canal: tela cheia.
 * Cima = próximo canal, baixo = anterior, OK = informações, segurar OK = favorito.
 */
class PlayerActivity : Activity() {

    private lateinit var player: ExoPlayer
    private lateinit var faixa: LinearLayout
    private lateinit var faixaNome: TextView
    private lateinit var faixaSub: TextView
    private lateinit var faixaLogo: ImageView
    private lateinit var aviso: TextView
    private var lista: List<Iptv.Canal> = emptyList()
    private var indice = 0
    private var vod = false
    private val h = Handler(Looper.getMainLooper())
    private val esconder = Runnable { faixa.animate().alpha(0f).setDuration(300).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lista = Iptv.fila.ifEmpty { Iptv.atual.canais }
        vod = intent.getBooleanExtra("vod", false)
        indice = intent.getIntExtra("indice", 0)
        if (lista.isEmpty()) {
            finish()
            return
        }

        val fonte = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("InicioTV")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(fonte))
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                aviso.text = if (vod) "Não consegui abrir este título agora." else "Este canal está fora do ar agora.\nUse cima/baixo para trocar."
                aviso.visibility = View.VISIBLE
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) aviso.visibility = View.GONE
                if (state == Player.STATE_BUFFERING && aviso.visibility != View.VISIBLE) {
                    // nada: o vídeo mostra o carregamento sozinho
                }
            }
        })

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val tela = PlayerView(this).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            this.player = this@PlayerActivity.player
        }
        root.addView(tela, FrameLayout.LayoutParams(-1, -1))

        // Faixa branca embaixo com o canal (some sozinha)
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
        faixa.addView(faixaLogo, LinearLayout.LayoutParams(dp(72), dp(52)))
        val txt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        faixaNome = TextView(this).apply {
            textSize = 22f
            setTextColor(Cores.tinta)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        faixaSub = TextView(this).apply {
            textSize = 14f
            setTextColor(Cores.cinza)
        }
        txt.addView(faixaNome)
        txt.addView(faixaSub)
        faixa.addView(txt)
        root.addView(
            faixa,
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(48)
                bottomMargin = dp(40)
            },
        )

        aviso = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = redondo(0xCC000000.toInt(), dpf(20))
            setPadding(dp(28), dp(18), dp(28), dp(18))
            visibility = View.GONE
        }
        root.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        setContentView(root)
        tocar()
    }

    private fun tocar() {
        val c = lista[indice]
        Iptv.atual.salvarUltimo(this, c)
        aviso.visibility = View.GONE
        val item = MediaItem.Builder().setUri(Uri.parse(c.url)).apply {
            if (c.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        mostrarFaixa()
    }

    private fun mostrarFaixa() {
        val c = lista[indice]
        val fav = c.url in Iptv.atual.favoritos(this)
        if (vod) {
            val pos = player.currentPosition / 1000
            val dur = player.duration.takeIf { it > 0 }?.div(1000)
            fun t(x: Long) = "%d:%02d:%02d".format(x / 3600, x / 60 % 60, x % 60)
            faixaNome.text = c.nome
            faixaSub.text = (if (player.playWhenReady) "▶ " else "❚❚ ") + t(pos) + (dur?.let { " / " + t(it) } ?: "") +
                "  ·  ◀ ▶ 15 s" + (if (lista.size > 1) "  ·  ▲▼ episódio ${indice + 1}/${lista.size}" else "")
        } else {
            faixaNome.text = "${indice + 1}  ${c.nome}"
            faixaSub.text = c.grupo + (if (fav) "  ·  ★ Favorito" else "  ·  Segure OK para favoritar")
        }
        faixaLogo.setImageDrawable(null)
        c.logo?.let { u -> Iptv.logo(u) { b -> if (b != null && lista[indice] == c) faixaLogo.setImageBitmap(b) } }
        faixa.animate().cancel()
        faixa.alpha = 1f
        h.removeCallbacks(esconder)
        h.postDelayed(esconder, 4000)
    }

    private fun trocar(passo: Int) {
        indice = (indice + passo + lista.size) % lista.size
        tocar()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Filme/série: esquerda/direita voltam e avançam 15 s; OK pausa; cima/baixo mostram o nome
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
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // Série: cima = próximo episódio, baixo = anterior; filme: só mostra o tempo
                    if (lista.size > 1) trocar(if (keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1) else mostrarFaixa()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player.playWhenReady = !player.playWhenReady
                    mostrarFaixa()
                    return true
                }
            }
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                event.startTracking()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val agora = Iptv.atual.trocarFavorito(this, lista[indice])
            Toast.makeText(this, if (agora) "★ Favorito" else "Tirado dos favoritos", Toast.LENGTH_SHORT).show()
            mostrarFaixa()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && !event.isCanceled) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                if (vod) player.playWhenReady = !player.playWhenReady
                mostrarFaixa()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized) player.play()
    }

    override fun onDestroy() {
        h.removeCallbacks(esconder)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
