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

PROIBIDO = re.compile(r"erotic|pornograph|sex instruction|sexual|obscen|sadism|masochism", re.I)
INFANTIL = re.compile(
    r"picture books|nursery rhymes|alphabet|readers \(primary\)|children's poetry|lullab|"
    r"toy and movable|children's songs|fairy tales|fables|children's myths|rimas infantis|"
    r"counting|mother goose|bedtime", re.I)
JOVEM = re.compile(r"juvenile|children's|children &|young adult|infantil|juvenil|school stories", re.I)

FICCAO = re.compile(r"fiction|stories|novel|romance|tales|ficção|contos|novela|cuentos|legends|fables|fairy", re.I)
REGRAS_FICCAO = [
    ("contos_de_fadas", r"fairy|fables|folklore|legends|mytholog|myths|nursery|fábula|conto de fada|contos populares|mother goose"),
    ("terror", r"horror|ghost|gothic|vampire|supernatural|haunted|werewol"),
    ("misterio", r"detective|mystery|crime|murder|policial|spy stories|thieves|criminal"),
    ("ficcao_cientifica", r"science fiction|fantasy|utopia|dystopia|time travel|imaginary|space|martians|interplanetary|lost worlds|fantas"),
    ("aventura", r"adventure|sea stories|pirates|western|explorers|frontier|robinsonades|shipwreck|voyages|war stories|aventura|cowboys|treasure"),
    ("humor", r"humor|humorous|satire|comic|wit and"),
    ("contos", r"short stories|contos|cuentos"),
]
REGRAS_OUTRAS = [
    ("poesia", r"poetry|poems|verse|sonnets|ballads|poesia|poesía|epic|hymns"),
    ("teatro", r"drama|plays|tragedies|comedies|teatro|theater|farces"),
    ("biografias", r"biography|autobiograph|memoirs|diaries|correspondence|letters|biografia"),
    ("historia", r"history|historia|história|civilization|wars|antiquities|revolution|politics and government"),
    ("filosofia", r"philosophy|ethics|psychology|logic|filosofia|conduct of life|political science"),
    ("religiao", r"religion|bible|christian|sermons|theology|church|catholic|god|prayer|spiritual|saints"),
    ("ciencia", r"science|mathematics|natural history|astronomy|biology|medicine|physics|chemistry|botany|zoology|evolution|geology|economics"),
    ("viagens", r"travel|voyages|description and travel|discovery and exploration|viagens|viajes"),
    ("humor", r"humor|wit and humor|satire"),
]
REGRAS_FICCAO = [(G[n], re.compile(r, re.I)) for n, r in REGRAS_FICCAO]
REGRAS_OUTRAS = [(G[n], re.compile(r, re.I)) for n, r in REGRAS_OUTRAS]


def valores(no, caminho):
    return [v.text.strip() for v in no.findall(caminho, NS) if v.text and v.text.strip()]


def nome_autor(cru):
    """'Assis, Machado de' → 'Machado de Assis'; tira datas e parênteses."""
    n = re.sub(r"\s*\(.*?\)", "", cru).strip()
    if "," in n:
        sobrenome, resto = n.split(",", 1)
        resto = resto.strip()
        # "Fulano, Jr." e afins ficam como estão
        if resto and not re.fullmatch(r"(Jr|Sr|II|III)\.?", resto):
            n = f"{resto} {sobrenome.strip()}"
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
    t = re.sub(r"\s+", " ", t).strip(" :;,.")
    return t


def classificar(assuntos, estantes, lcc):
    texto = " | ".join(assuntos + estantes)
    jovem = bool(JOVEM.search(texto)) or any(c.startswith("PZ") for c in lcc)
    infantil = bool(INFANTIL.search(texto)) and (jovem or re.search(r"fairy|fables|nursery|mother goose", texto, re.I))
    faixa = 0 if infantil else 1 if jovem else 2

    ficcao = bool(FICCAO.search(texto)) or any(c[:2] in ("PZ",) for c in lcc)
    genero = None
    if ficcao:
        for g, rx in REGRAS_FICCAO:
            if rx.search(texto):
                genero = g
                break
        if genero is None:
            # Poesia e teatro às vezes vêm marcados com "Portuguese literature", "tales in verse"...
            for g, rx in REGRAS_OUTRAS[:2]:
                if rx.search(texto):
                    genero = g
                    break
        if genero is None:
            # Ficção infantil sem outra pista: histórias curtas, não "romance"
            genero = G["contos"] if faixa < 2 else G["romances"]
    else:
        for g, rx in REGRAS_OUTRAS:
            if rx.search(texto):
                genero = g
                break
        if genero is None:
            # Literatura sem outra pista ("Brazilian literature", LCC P*) costuma ser romance
            if any(c.startswith("P") for c in lcc) and re.search(r"literature|literatura", texto, re.I):
                genero = G["romances"]
            else:
                genero = G["outros"]
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
    faixa, genero = classificar(assuntos, estantes, lcc)
    return {
        "id": gid, "titulo": titulo, "autor": autor, "idioma": idiomas[0],
        "faixa": faixa, "genero": genero,
        "downloads": int(downloads) if downloads.isdigit() else 0,
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
    if len(escolhidos) < 1000:
        sys.exit(f"Poucos livros ({len(escolhidos)}): o catálogo deveria ter pelo menos 1000")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
