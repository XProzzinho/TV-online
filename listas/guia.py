"""
Guia de programação leve para o Lumi TV (listas/guia.json).
Pega o guia da Pluto TV do Brasil (XMLTV) e guarda só as próximas 18 horas,
no formato {"id do canal": [[início, fim, "título"], ...]} (horários em segundos).
Roda sozinho no GitHub junto com a lista.
Uso: python3 listas/guia.py [arquivo_xml_gz]
"""
import gzip
import json
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime

GUIA_PLUTO = "https://github.com/matthuisman/i.mjh.nz/raw/master/PlutoTV/br.xml.gz"
HORAS = 18


def ler(fonte):
    if fonte.startswith("http"):
        req = urllib.request.Request(fonte, headers={"User-Agent": "LumiTV"})
        with urllib.request.urlopen(req, timeout=120) as r:
            dados = r.read()
    else:
        dados = open(fonte, "rb").read()
    return gzip.decompress(dados) if dados[:2] == b"\x1f\x8b" else dados


def hora(s):
    # "20261108203000 +0000"
    return int(datetime.strptime(s.strip(), "%Y%m%d%H%M%S %z").timestamp())


def main():
    fonte = sys.argv[1] if len(sys.argv) > 1 else GUIA_PLUTO
    agora = int(time.time())
    limite = agora + HORAS * 3600
    guia = {}
    for _, el in ET.iterparse(__import__("io").BytesIO(ler(fonte)), events=("end",)):
        if el.tag != "programme":
            continue
        try:
            ini, fim = hora(el.get("start")), hora(el.get("stop"))
        except Exception:
            el.clear()
            continue
        if fim > agora and ini < limite:
            titulo = (el.findtext("title") or "").strip()
            ep = (el.findtext("sub-title") or "").strip()
            if ep and ep.lower() != titulo.lower():
                titulo = f"{titulo} · {ep}"
            guia.setdefault(el.get("channel"), []).append([ini, fim, titulo[:90]])
        el.clear()
    for v in guia.values():
        v.sort()
    json.dump(guia, open("listas/guia.json", "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
    print(f"Guia: {len(guia)} canais, {sum(len(v) for v in guia.values())} programas")


if __name__ == "__main__":
    main()
