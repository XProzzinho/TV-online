"""
Monta a lista M3U do Geanderson (listas/minha.m3u).
Só fontes grátis e oficiais: Rede Minas, SBT Kids e os canais da Pluto TV do Brasil.
Roda sozinho no GitHub a cada 6 horas (os links da Pluto mudam).
Uso: python3 listas/gerar.py [arquivo_iptv_org] [arquivo_pluto]
"""
import re
import sys
import urllib.request

IPTV_ORG = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/br.m3u"
PLUTO = "https://raw.githubusercontent.com/BuddyChewChew/pluto/main/pluto_br.m3u"

# Canais escolhidos da lista iptv-org: tvg-id -> (nome, grupo)
ESCOLHIDOS = {
    "RedeMinas.br": ("Rede Minas", "Abertos"),
    "SBTKids.br": ("SBT Kids", "Kids"),
}


def ler(fonte):
    if fonte.startswith("http"):
        req = urllib.request.Request(fonte, headers={"User-Agent": "LumiTV"})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read().decode("utf-8", "replace")
    return open(fonte, encoding="utf-8").read()


def entradas(texto):
    """Devolve (linha #EXTINF, linhas #EXTVLCOPT, link) de cada canal."""
    out, extinf, opts = [], None, []
    for l in texto.splitlines():
        l = l.strip()
        if l.startswith("#EXTINF"):
            extinf, opts = l, []
        elif l.startswith("#EXTVLCOPT") and extinf:
            opts.append(l)
        elif l and not l.startswith("#") and extinf:
            out.append((extinf, opts, l))
            extinf, opts = None, []
    return out


def attr(extinf, nome):
    m = re.search(nome + r'="([^"]*)"', extinf)
    return m.group(1) if m else ""


def main():
    iptv = sys.argv[1] if len(sys.argv) > 1 else IPTV_ORG
    pluto = sys.argv[2] if len(sys.argv) > 2 else PLUTO
    linhas = ['#EXTM3U url-tvg="https://github.com/matthuisman/i.mjh.nz/raw/master/PlutoTV/br.xml.gz"']

    # 1) Canais abertos escolhidos (o primeiro link que transmite 24 horas)
    achados = {}
    for extinf, opts, link in entradas(ler(iptv)):
        tid = attr(extinf, "tvg-id").split("@")[0]
        if tid in ESCOLHIDOS and tid not in achados and "Not 24/7" not in extinf and "Geo-blocked" not in extinf:
            achados[tid] = (opts, link)
    for tid, (nome, grupo) in ESCOLHIDOS.items():
        if tid not in achados:
            continue
        opts, link = achados[tid]
        linhas.append(f'#EXTINF:-1 tvg-id="{tid}" group-title="{grupo}",{nome}')
        linhas += opts
        linhas.append(link)

    # 2) Pluto TV: todos os canais, com o grupo de cada um
    n = 0
    for extinf, opts, link in entradas(ler(pluto)):
        grupo = attr(extinf, "group-title") or "Outros"
        nome = extinf.rsplit(",", 1)[-1].strip()
        logo = attr(extinf, "tvg-logo")
        tid = attr(extinf, "tvg-id")
        linhas.append(f'#EXTINF:-1 tvg-id="{tid}" tvg-logo="{logo}" group-title="Pluto · {grupo}",{nome}')
        linhas += opts
        linhas.append(link)
        n += 1

    open("listas/minha.m3u", "w", encoding="utf-8").write("\n".join(linhas) + "\n")
    print(f"Abertos: {len(achados)} · Pluto: {n}")


if __name__ == "__main__":
    main()
