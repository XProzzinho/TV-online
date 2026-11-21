package com.geanalves.tvonline

import android.content.Context
import android.view.View

/** Céu azul-noite animado (igual ao Início TV): estrelas, lua, aurora e estrela cadente. */
class Estrelas(ctx: Context, private val claro: Boolean = false, private val descanso: Boolean = false) : View(ctx) {
    private val rnd = java.util.Random(7)
    private val pts = Array(80) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat() * 0.75f, 0.6f + rnd.nextFloat() * 1.8f, rnd.nextFloat() * 6.28f, 0.4f + rnd.nextFloat() * 0.8f) }
    private val po = Array(26) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 1.2f + rnd.nextFloat() * 2.2f, 0.012f + rnd.nextFloat() * 0.025f, rnd.nextFloat() * 6.28f) }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val traco = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }
    private val aur = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val corta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
    private var fundo: android.graphics.Bitmap? = null
    private var fundoHora = -1
    private val t0 = System.currentTimeMillis()
    private var ultimo = t0
    private var cadT = -100f; private var cadX = 0f; private var cadY = 0f; private var proxCad = 1.2f
    private var conT = -100f; private var proxCon = 4f; private var con: IntArray = IntArray(0)
    private val caminho = android.graphics.Path()

    /** Fundo fixo (brilhos + cor do céu conforme a hora): refeito quando muda a hora. */
    private fun montaFundo(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val b = android.graphics.Bitmap.createBitmap(w / 2, h / 2, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b); val g = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val W = b.width.toFloat(); val H = b.height.toFloat()
        // céu muda com a hora: nascer do sol alaranjado, fim da tarde roxo, noite azul
        val tinta = if (claro) when (hora) { in 5..7 -> 0x30FFB070; in 16..18 -> 0x30E0A0FF; else -> 0 } else when (hora) { in 5..6 -> 0x55FF8A3D; in 17..18 -> 0x55B04AD8; 19 -> 0x33703AC8; else -> 0 }
        if (tinta != 0) { g.shader = android.graphics.LinearGradient(0f, H, 0f, H * 0.2f, tinta, 0, android.graphics.Shader.TileMode.CLAMP); c.drawRect(0f, 0f, W, H, g) }
        fun glow(x: Float, y: Float, r: Float, cor: Int) { g.shader = android.graphics.RadialGradient(x, y, r, cor, 0, android.graphics.Shader.TileMode.CLAMP); c.drawCircle(x, y, r, g) }
        if (claro) { glow(W * 0.42f, H * 0.48f, W * 0.38f, 0x228AB4FF); glow(W * 0.86f, H * 0.22f, W * 0.22f, 0x22FFC48A); glow(W * 0.10f, H * 0.10f, W * 0.18f, 0x1CC8A0FF) }
        else { glow(W * 0.42f, H * 0.48f, W * 0.38f, 0x332E5BFF); glow(W * 0.86f, H * 0.22f, W * 0.22f, 0x2240A0FF); glow(W * 0.10f, H * 0.10f, W * 0.18f, 0x1A9A6BFF) }
        fundo = b; fundoHora = hora
    }
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = montaFundo(w, h)

    /** Fase da lua de 0 (nova) a 1 (nova de novo); 0,5 = cheia. */
    private fun faseLua(): Float {
        val dias = (System.currentTimeMillis() - 947182440000L) / 86400000.0 // lua nova de 6/jan/2000 18:14 UTC
        val f = (dias / 29.530588853) % 1.0
        return (if (f < 0) f + 1 else f).toFloat()
    }

    private val dst = android.graphics.Rect()
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f) return
        val agora = System.currentTimeMillis(); val dt = ((agora - ultimo) / 1000f).coerceIn(0f, 0.1f); ultimo = agora
        if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) != fundoHora) montaFundo(width, height)
        // ECONOMIA AUTOMÁTICA: se a TV estiver lenta (menos de ~14 quadros/s), desenha menos coisa
        media = media * 0.95f + dt * 0.05f
        if (!leve && media > 0.07f) leve = true else if (leve && media < 0.045f) leve = false
        if (!descanso) fundo?.let { dst.set(0, 0, width, height); c.drawBitmap(it, null, dst, null) }
        val d = resources.displayMetrics.density
        val t = (agora - t0) / 1000f

        if (!descanso && !leve) // AURORA: duas faixas de luz (verde e roxa) ondulando devagar lá em cima
        for ((k, cor) in (if (claro) listOf(0x2496D8FF, 0x20FFA0C8) else listOf(0x2E3DFFB0, 0x26B06CFF)).withIndex()) {
            caminho.reset()
            val base = h * (0.03f + k * 0.05f); val alt = h * 0.16f
            var x = 0f; caminho.moveTo(0f, base)
            while (x <= w) { caminho.lineTo(x, base + kotlin.math.sin(x / w * 7f + t * (0.25f + k * 0.12f) + k * 2f) * h * 0.035f); x += w / 24f }
            x = w; while (x >= 0f) { caminho.lineTo(x, base + alt + kotlin.math.sin(x / w * 5f + t * 0.2f + k) * h * 0.04f); x -= w / 24f }
            caminho.close()
            val pul = 0.65f + 0.35f * kotlin.math.sin(t * 0.4f + k * 1.7f)
            aur.shader = android.graphics.LinearGradient(0f, base, 0f, base + alt, (cor.toLong() and 0xFFFFFF or ((((cor ushr 24) * pul).toLong()) shl 24)).toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
            c.drawPath(caminho, aur)
        }

        // LUA na fase de verdade, perto da hora
        if (!descanso) run {
            val r = 18f * d; val lx = w * 0.60f; val ly = h * 0.255f; val f = faseLua()
            if (f > 0.03f && f < 0.97f) {
                if (!claro) { p.color = 0x16FFF2CC; c.drawCircle(lx, ly, r * 1.8f, p); p.color = 0x0AFFF2CC; c.drawCircle(lx, ly, r * 2.6f, p) } else { p.color = 0x14000000; c.drawCircle(lx, ly + d, r * 1.05f, p) }
                val camada = c.saveLayer(lx - r * 2, ly - r * 2, lx + r * 2, ly + r * 2, null)
                p.color = if (claro) 0xFFDCE3EE.toInt() else 0xFFFFF4D6.toInt(); c.drawCircle(lx, ly, r, p)
                val k = f * 2f // 0..2
                val desloca = if (k < 1f) -2f * r * k else 2f * r * (2f - k) // crescente: sombra à esquerda; minguante: à direita
                if (kotlin.math.abs(desloca) < 2f * r) c.drawCircle(lx + desloca, ly, r * 1.02f, corta)
                c.restoreToCount(camada)
            }
        }

        // estrelas piscando (no modo leve, só metade)
        for ((ix, s) in pts.withIndex()) {
            if (leve && ix % 2 == 1) continue
            val a = 0.3f + 0.7f * (0.5f + 0.5f * kotlin.math.sin(t * s[4] + s[3]))
            val x = s[0] * w; val y = s[1] * h; val r = s[2] * d * 0.9f
            p.color = if (claro) Color.argb((a * 120).toInt(), 120, 140, 185) else Color.argb((a * 210).toInt(), 255, 244, 214)
            c.drawCircle(x, y, r, p)
            if (s[2] > 2.1f && a > 0.75f) {
                traco.strokeWidth = d * 0.8f; traco.color = if (claro) Color.argb(((a - 0.75f) * 4 * 110).toInt(), 120, 140, 190) else Color.argb(((a - 0.75f) * 4 * 160).toInt(), 255, 248, 225)
                val l = r * 5f; c.drawLine(x - l, y, x + l, y, traco); c.drawLine(x, y - l, x, y + l, traco)
            }
        }

        // CONSTELAÇÃO: de vez em quando 5 estrelas vizinhas se ligam com linhas finas e depois somem
        if (t > proxCon) {
            val a = rnd.nextInt(pts.size)
            con = pts.indices.sortedBy { j -> val dx = (pts[j][0] - pts[a][0]) * w; val dy = (pts[j][1] - pts[a][1]) * h; dx * dx + dy * dy }.take(5).toIntArray()
            conT = t; proxCon = t + 9f + rnd.nextFloat() * 6f
        }
        val uc = (t - conT) / 5f
        if (uc in 0f..1f && con.size > 1) {
            val al = kotlin.math.sin(Math.PI.toFloat() * uc)
            traco.strokeWidth = d * 1.1f; traco.color = if (claro) Color.argb((al * 90).toInt(), 110, 130, 180) else Color.argb((al * 120).toInt(), 200, 220, 255)
            val n = kotlin.math.min(con.size - 1, (uc * 3f * (con.size - 1)).toInt() + 1)
            for (q in 0 until n) { val A = pts[con[q]]; val B = pts[con[q + 1]]; c.drawLine(A[0] * w, A[1] * h, B[0] * w, B[1] * h, traco) }
            for (q in con) { p.color = if (claro) Color.argb((al * 170).toInt(), 110, 130, 180) else Color.argb((al * 255).toInt(), 230, 240, 255); c.drawCircle(pts[q][0] * w, pts[q][1] * h, 2.6f * d, p) }
        }

        // poeirinha de luz subindo
        if (!leve) for (q in po) {
            q[1] -= q[3] * dt; if (q[1] < -0.05f) { q[1] = 1.05f; q[0] = rnd.nextFloat() }
            val x = (q[0] + 0.015f * kotlin.math.sin(t * 0.6f + q[4])) * w; val y = q[1] * h; val r = q[2] * d
            val a = (0.5f + 0.5f * kotlin.math.sin(t * 1.3f + q[4])) * kotlin.math.min(1f, (1.05f - q[1]) * 4f)
            if (claro) { p.color = Color.argb((a * 45).toInt(), 140, 180, 255); c.drawCircle(x, y, r * 3.2f, p); p.color = Color.argb((a * 120).toInt(), 120, 160, 230); c.drawCircle(x, y, r, p) }
            else { p.color = Color.argb((a * 40).toInt(), 140, 190, 255); c.drawCircle(x, y, r * 3.2f, p); p.color = Color.argb((a * 200).toInt(), 225, 238, 255); c.drawCircle(x, y, r, p) }
        }

        // ESTRELA CADENTE
        if (t > proxCad) { cadT = t; cadX = w * (0.35f + rnd.nextFloat() * 0.6f); cadY = h * (0.02f + rnd.nextFloat() * 0.25f); proxCad = t + if (rnd.nextFloat() < 0.25f) 1.6f else 3f + rnd.nextFloat() * 3f }
        val u = (t - cadT) / 1.5f
        if (u in 0f..1f) {
            val dx = -w * 0.48f; val dy = h * 0.34f
            val hx = cadX + dx * u; val hy = cadY + dy * u
            val forca = kotlin.math.sin(Math.PI.toFloat() * u)
            val cauda = 0.42f * (0.4f + 0.6f * forca)
            val tx = hx - dx * cauda; val ty = hy - dy * cauda
            traco.shader = android.graphics.LinearGradient(hx, hy, tx, ty, if (claro) Color.argb((forca * 200).toInt(), 110, 140, 200) else Color.argb((forca * 255).toInt(), 255, 252, 240), 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
            traco.color = Color.WHITE
            traco.strokeWidth = d * 6f; traco.alpha = (forca * 60).toInt(); c.drawLine(hx, hy, tx, ty, traco)
            traco.strokeWidth = d * 2.6f; traco.alpha = 255; c.drawLine(hx, hy, tx, ty, traco)
            traco.shader = null
            p.color = if (claro) Color.argb((forca * 60).toInt(), 140, 170, 230) else Color.argb((forca * 90).toInt(), 190, 215, 255); c.drawCircle(hx, hy, d * 12f, p)
            p.color = if (claro) Color.argb((forca * 230).toInt(), 100, 130, 200) else Color.argb((forca * 255).toInt(), 255, 255, 255); c.drawCircle(hx, hy, d * 3f, p)
        }
        postInvalidateDelayed(if (leve) 80 else 40)
    }
    private var media = 0.04f
    private var leve = false
}

/** Efeitos de datas e do tempo, nos dois modos: NEVE no Natal (20 a 26/dez), FOGOS no Ano Novo (31/dez e 1º/jan),
 *  CORAÇÕES no Dia dos Namorados (12/jun) e CHUVA quando estiver chovendo em Pirapora. Parado quando não tem nada. */
