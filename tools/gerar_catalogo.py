#!/usr/bin/env python3
"""
Gera app/src/main/assets/catalogo.json, a lista de livros grátis da aba Loja.

Fonte: o catálogo do Project Gutenberg (rdf-files.tar.bz2), só livros em
domínio público. Cada livro recebe uma faixa etária e um gênero a partir dos
assuntos e estantes do próprio Gutenberg.

Uso:  python3 tools/gerar_catalogo.py rdf-files.tar.bz2 app/src/main/assets/catalogo.json

Formato de saída (compacto, ordenado do mais baixado para o menos):
  {"versao": 1, "livros": [[id, "título", "autor", "idioma", faixa, genero], ...]}
  faixa:  0 = Infantil (até 8), 1 = Juvenil (9 a 14), 2 = Adulto (15+)
  genero: índice em GENEROS (mesma ordem do string-array generos_loja do app)
"""
import json
import re
import sys
import tarfile
import xml.etree.ElementTree as ET
from collections import Counter

# Mesma ordem do <string-array name="generos_loja"> no app
GENEROS = [
    "contos_de_fadas",   # 0 Contos de fadas e fábulas
    "aventura",          # 1
    "misterio",          # 2 Mistério e policial
    "ficcao_cientifica", # 3 Ficção científica e fantasia
    "terror",            # 4
    "romances",          # 5 Romances (ficção em geral)
    "contos",            # 6 Contos e histórias
    "humor",             # 7
    "poesia",            # 8
    "teatro",            # 9
    "biografias",        # 10
    "historia",          # 11
    "filosofia",         # 12
    "religiao",          # 13
    "ciencia",           # 14
    "viagens",           # 15
    "outros",            # 16 Ensaios e outros
]
G = {nome: i for i, nome in enumerate(GENEROS)}

# Quantos livros entram de cada idioma (os mais baixados primeiro)
COTAS = {
    "pt": {"adulto": 900, "jovem": 300},
    "es": {"adulto": 220, "jovem": 60},
    "en": {"adulto": 520, "jovem": 330},
}

# Brasil: domínio público 70 anos após a morte do autor (contados a partir de 1º de janeiro)
ANO_ATUAL = 2026
ULTIMA_MORTE_LIVRE = ANO_ATUAL - 71  # morreu até 1955 → livre em 2026

NS = {
    "rdf": "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "dcterms": "http://purl.org/dc/terms/",
    "pgterms": "http://www.gutenberg.org/2009/pgterms/",
    "dcam": "http://purl.org/dc/dcam/",
    "marcrel": "http://id.loc.gov/vocabulary/relators/",
}
RDF_RES = "{%s}resource" % NS["rdf"]

PROIBIDO = re.compile(r"erotic|pornograph|sex instruction|sexual|obscen|sadism|masochism|periodicals|PT Periódicos", re.I)

# Faixa etária
JOVEM = re.compile(r"juvenile|children's|children & young|young adult fiction|infantil|juvenil|school stories", re.I)
INFANTIL = re.compile(
    r"picture books|nursery rhymes|alphabet|readers \(primary\)|children's poetry|lullab|toy and movable|"
    r"children's songs|fairy tales|fables|children's myths|mother goose|counting|bedtime|"
    r"animals -- juvenile|easy reading|rimas infantis|contos infantis", re.I)

# Gênero: primeiro as estantes "Category:" do Gutenberg, que são bem confiáveis;
# depois os assuntos (LCSH) e as estantes em português ("PT Poesia"...).
def _r(x):
    return re.compile(x, re.I)

REGRAS = [
    ("teatro", _r(r"Category: Plays|PT Teatro|-- Drama\b|^Drama$|\bTragedies|\bComedies \(|English drama|Portuguese drama")),
    ("poesia", _r(r"Category: Poetry|PT Poesia|\bpoetry\b|\bpoems\b|sonnets|children's poetry")),
    ("terror", _r(r"horror tales|ghost stories|vampires -- fiction|haunted houses -- fiction|Category: Horror")),
    ("misterio", _r(r"Category: Crime, Thrillers|detective and mystery|mystery fiction|spy stories")),
    ("ficcao_cientifica", _r(r"Category: Science-Fiction|science fiction|fantasy fiction|utopias|time travel -- fiction")),
    ("aventura", _r(r"Category: Adventure|adventure stories|sea stories|pirates -- fiction|western stories|PT Navegações")),
    ("romances", _r(r"Category: Novels|Category: Historical Novels|Category: Romance|PT Romance")),
    ("contos_de_fadas", _r(r"Category: Mythology|fairy tales|fables|folklore|legends|mythology|children's myths")),
    ("humor", _r(r"Category: Humour")),
    ("contos", _r(r"Category: Short Stories|PT Contos|short stories|children's stories")),
    ("romances", _r(r"Category: Novels|Category: Romance|Category: Historical Novels|PT Romance|\bfiction\b|\bnovel")),
    ("biografias", _r(r"Category: Biographies|PT Biografia|biography|autobiograph|memoirs|diaries")),
    ("historia", _r(r"Category: History|PT História|Category: Archaeology|\bhistory\b")),
    ("religiao", _r(r"Category: Religion|bible|theology|sermons|christian life")),
    ("filosofia", _r(r"Category: Philosophy|Category: Psychiatry|philosophy|ethics")),
    ("ciencia", _r(r"Category: Science|Category: Nature|Category: Mathematics|Category: Health|Category: Engineering|PT Ciência|natural history")),
    ("viagens", _r(r"Category: Travel|description and travel|voyages and travels")),
]
REGRAS = [(G[n], rx) for n, rx in REGRAS]


def valores(no, caminho):
    return [v.text.strip() for v in no.findall(caminho, NS) if v.text and v.text.strip()]


TITULOS_NOBRES = re.compile(r"(Emperor|Empress|King|Queen|Saint|Pope|Lord|Sir|Lady|Duke|Count|Prince|Princess|graf|of)\b")


def nome_autor(cru):
    """'Assis, Machado de' → 'Machado de Assis'; 'Tolstoy, Leo, graf' → 'Leo Tolstoy'."""
    n = re.sub(r"\s*\(.*?\)", "", cru).strip()
    partes = [p.strip() for p in n.split(",") if p.strip()]
    if len(partes) >= 2 and not TITULOS_NOBRES.match(partes[1]) and not re.fullmatch(r"(Jr|Sr|II|III)\.?", partes[1]):
        n = f"{partes[1]} {partes[0]}"
    elif partes:
        n = partes[0]
    return re.sub(r"\s+", " ", n).strip()


def pessoas(livro, papel):
    """(nome, nascimento, morte) dos agentes de um papel (dcterms:creator, marcrel:trl...)."""
    out = []
    for papel_no in livro.findall(papel, NS):
        for ag in papel_no.findall("pgterms:agent", NS):
            nome = (ag.findtext("pgterms:name", default="", namespaces=NS) or "").strip()
            nasc = ag.findtext("pgterms:birthdate", default="", namespaces=NS)
            morte = ag.findtext("pgterms:deathdate", default="", namespaces=NS)
            out.append((nome, int(nasc) if nasc and nasc.lstrip("-").isdigit() else None,
                        int(morte) if morte and morte.lstrip("-").isdigit() else None))
    return out


def livre_no_brasil(gente):
    for _, nasc, morte in gente:
        if morte is not None:
            if morte > ULTIMA_MORTE_LIVRE:
                return False
        elif nasc is not None and nasc >= 1870:
            # Sem data de morte e nascido depois de 1870: pode ainda ter direitos autorais
            return False
    return True


def limpar_titulo(t):
    t = t.replace("\r", "").split("\n")[0]
    t = re.sub(r"\s*:\s*\$b\s*", ": ", t)
    t = t.replace("[", "").replace("]", "")
    t = re.sub(r"\s+", " ", t).strip(" :;,.")
    return t


# Títulos que dizem a idade (os livros em português e espanhol quase não têm assuntos infantis)
TITULO_INFANTIL = re.compile(r"\bfábulas\b|cuento infantil|cuentos infantiles|contos infantis|cartilha|para niños|para crianças", re.I)
TITULO_JOVEM = re.compile(r"infância|para a infancia|para meninos|para meninas|diario de un niño|para la juventud|para a juventude", re.I)
NAO_FICCAO = {"biografias", "historia", "filosofia", "religiao", "ciencia", "viagens", "outros"}


def classificar(assuntos, estantes, lcc, titulo=""):
    pistas = assuntos + estantes
    texto = "\n".join(pistas)
    genero = G["outros"]
    for g, rx in REGRAS:
        if any(rx.search(p) for p in pistas):
            genero = g
            break

    juvenil_explicito = re.search(r"juvenile|children's|young adult fiction", texto, re.I)
    jovem = bool(JOVEM.search(texto))
    # Memórias de generais e afins marcados só na estante "Children & Young Adult": ficam no Adulto
    if jovem and not juvenil_explicito and GENEROS[genero] in NAO_FICCAO:
        jovem = False
    infantil = jovem and INFANTIL.search(texto) or TITULO_INFANTIL.search(titulo)
    jovem = jovem or TITULO_JOVEM.search(titulo)
    faixa = 0 if infantil else 1 if jovem else 2

    if faixa == 0 and genero in (G["romances"], G["outros"]):
        genero = G["contos"]
    return faixa, genero


def ler_livro(raiz):
    livro = raiz.find("pgterms:ebook", NS)
    if livro is None:
        return None
    sobre = livro.get("{%s}about" % NS["rdf"], "")
    m = re.search(r"ebooks/(\d+)", sobre)
    if not m:
        return None
    gid = int(m.group(1))

    tipo = valores(livro, "dcterms:type/rdf:Description/rdf:value")
    if tipo and tipo[0] != "Text":
        return None
    direitos = livro.findtext("dcterms:rights", default="", namespaces=NS)
    if "public domain" not in direitos.lower():
        return None
    idiomas = valores(livro, "dcterms:language/rdf:Description/rdf:value")
    if len(idiomas) != 1 or idiomas[0] not in COTAS:
        return None
    formatos = [f.get("{%s}about" % NS["rdf"], "") for f in livro.findall("dcterms:hasFormat/pgterms:file", NS)]
    if not any(".epub" in f for f in formatos):
        return None

    titulo = limpar_titulo(livro.findtext("dcterms:title", default="", namespaces=NS))
    if not titulo:
        return None

    assuntos, lcc = [], []
    for d in livro.findall("dcterms:subject/rdf:Description", NS):
        membro = d.find("dcam:memberOf", NS)
        valor = (d.findtext("rdf:value", default="", namespaces=NS) or "").strip()
        if not valor:
            continue
        if membro is not None and membro.get(RDF_RES, "").endswith("LCC"):
            lcc.append(valor)
        else:
            assuntos.append(valor)
    estantes = valores(livro, "pgterms:bookshelf/rdf:Description/rdf:value")
    if PROIBIDO.search(" | ".join(assuntos + estantes + [titulo])):
        return None

    autores = pessoas(livro, "dcterms:creator") or pessoas(livro, "marcrel:aut")
    tradutores = pessoas(livro, "marcrel:trl")
    if not livre_no_brasil(autores + tradutores + pessoas(livro, "marcrel:ill")):
        return None

    nomes = [nome_autor(n) for n, _, _ in autores if n and n.lower() not in ("anonymous", "various", "unknown")]
    autor = ", ".join(nomes[:2]) + (" e outros" if len(nomes) > 2 else "")

    downloads = livro.findtext("pgterms:downloads", default="0", namespaces=NS)
    faixa, genero = classificar(assuntos, estantes, lcc, titulo)
    return {
        "id": gid, "titulo": titulo, "autor": autor, "idioma": idiomas[0],
        "faixa": faixa, "genero": genero,
        "downloads": int(downloads) if downloads.isdigit() else 0,
        "pistas": " | ".join(assuntos + estantes + ["LCC " + c for c in lcc]),
    }


def chave_dup(l):
    t = re.sub(r"[^\w]", "", l["titulo"].lower())[:60]
    a = re.sub(r"[^\w]", "", l["autor"].lower())[:30]
    return (l["idioma"], t, a)


def main(origem, destino):
    livros = []
    with tarfile.open(origem, "r:*") as tar:
        for membro in tar:
            if not membro.isfile() or not membro.name.endswith(".rdf"):
                continue
            try:
                raiz = ET.parse(tar.extractfile(membro)).getroot()
                l = ler_livro(raiz)
            except ET.ParseError:
                continue
            if l:
                livros.append(l)
    print(f"{len(livros)} livros aproveitáveis no catálogo")

    # Várias edições do mesmo livro: fica a mais baixada
    livros.sort(key=lambda l: -l["downloads"])
    vistos, unicos = set(), []
    for l in livros:
        k = chave_dup(l)
        if k in vistos:
            continue
        vistos.add(k)
        unicos.append(l)

    escolhidos = []
    for idioma, cota in COTAS.items():
        do_idioma = [l for l in unicos if l["idioma"] == idioma]
        jovens = [l for l in do_idioma if l["faixa"] < 2][: cota["jovem"]]
        adultos = [l for l in do_idioma if l["faixa"] == 2][: cota["adulto"]]
        escolhidos += jovens + adultos
        print(f"{idioma}: {len(jovens)} infantis/juvenis + {len(adultos)} adultos")
    escolhidos.sort(key=lambda l: -l["downloads"])

    saida = {
        "versao": 1,
        "fonte": "Project Gutenberg (www.gutenberg.org)",
        "livros": [[l["id"], l["titulo"], l["autor"], l["idioma"], l["faixa"], l["genero"]] for l in escolhidos],
    }
    with open(destino, "w", encoding="utf-8") as f:
        json.dump(saida, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Total: {len(escolhidos)} livros")
    print("Por faixa:", dict(Counter(l["faixa"] for l in escolhidos)))
    print("Por gênero:", {GENEROS[g]: n for g, n in sorted(Counter(l["genero"] for l in escolhidos).items())})
    # Para calibrar a classificação: assuntos dos livros mais baixados de cada idioma
    import os
    if os.environ.get("PISTAS"):
        with open(os.environ["PISTAS"], "w", encoding="utf-8") as f:
            for idioma, n in (("pt", 2000), ("es", 1200), ("en", 3500)):
                for l in [l for l in unicos if l["idioma"] == idioma][:n]:
                    f.write("\t".join(map(str, [l["id"], idioma, l["downloads"], l["titulo"], l["autor"], l["pistas"]])) + "\n")

    if len(escolhidos) < 1000:
        sys.exit(f"Poucos livros ({len(escolhidos)}): o catálogo deveria ter pelo menos 1000")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
