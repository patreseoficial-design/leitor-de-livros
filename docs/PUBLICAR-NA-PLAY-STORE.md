# Como publicar o Leitor de Livros na Play Store

É o mesmo caminho do Gerenciador de Arquivos. Aqui fica só o que muda.

---

## 1. Chave de assinatura

Pode usar **a mesma chave** do Gerenciador de Arquivos. Neste repositório: **Settings → Secrets and variables → Actions → New repository secret** e crie os mesmos 4 segredos, com os mesmos valores do `LEIA-ME-CHAVE.txt`:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Depois disso, cada build gera o artefato **`app-release-playstore`** (o `.aab` da loja).

---

## 2. Criar o app no Play Console

- **Nome do app:** `Leitor de Livros: EPUB e PDF` (28 caracteres)
- **Idioma padrão:** Português (Brasil)
- **App**, **Gratuito**
- **Categoria:** Livros e referências

**Descrição curta** (até 80 caracteres):

```
Leia livros EPUB, FB2, PDF e TXT com temas, sumário e o ponto onde parou.
```

**Descrição completa:**

```
Sua biblioteca no bolso. Leia livros EPUB, FB2, PDF e TXT com conforto, sem anúncios.

📚 BIBLIOTECA COM CAPAS
• O app encontra sozinho os livros do celular
• Lendo agora, Favoritos, Para ler e Lidos
• Busque por título ou autor

📖 LEITURA CONFORTÁVEL
• Toque nas laterais ou use os botões de volume para virar a página
• Temas claro, sépia e escuro
• Fonte com ou sem serifa e tamanho da letra
• Tela cheia, sem distrações

🔖 VOLTE ONDE PAROU
• O app guarda a página de cada livro
• Barra de progresso em cada livro

📑 SUMÁRIO
• Vá direto para qualquer capítulo
• Barra para pular para qualquer parte do livro

📄 PDF COM ZOOM
• Pinça ou toque duplo para aumentar

🔒 PRIVACIDADE
• Sem anúncios e sem acesso à internet
• Seus livros nunca saem do celular
```

**Imagens:**
- Ícone 512 × 512 px: está em `docs/icone-512.png`
- Imagem de destaque 1024 × 500 px
- Pelo menos 2 capturas de tela: a biblioteca com as capas, um livro aberto no tema sépia e o sumário

**Política de privacidade (URL):**

```
https://github.com/patreseoficial-design/politica-privacidade/blob/main/leitor-de-livros.md
```

---

## 3. Conteúdo do app

- **Segurança dos dados:** o app coleta ou compartilha dados? → **Não**
- **Anúncios:** → **Não**
- **Classificação de conteúdo:** Utilitário/Produtividade, sem violência, sem conteúdo sexual, sem interação entre usuários → **Livre**
- **Público-alvo:** 18 anos ou mais (ou 13+)

### Declaração: Acesso a todos os arquivos (MANAGE_EXTERNAL_STORAGE)

- Tipo de uso: **Gerenciamento de documentos** (Document management)
- Texto:

```
O app é um leitor de livros digitais (e-books). Sua função principal é encontrar e abrir os livros do usuário (EPUB, FB2 e PDF) que estão em qualquer pasta do aparelho, como Downloads, Livros e WhatsApp, montando uma biblioteca com capas e o progresso de leitura. Esses arquivos não são mídia, então não aparecem pelas permissões de fotos/vídeos/áudio, e o seletor do sistema não permite listar todos os livros do aparelho de uma vez. O app só lê os arquivos, não altera nem apaga nada, e não envia nenhum arquivo para a internet (o app não tem permissão de internet). O usuário também pode usar o app sem essa permissão, abrindo cada livro pelo seletor do sistema.
```

- **Vídeo:** grave a tela mostrando: o app pedindo o acesso, a biblioteca com livros de várias pastas e um livro sendo aberto. Suba no YouTube como "não listado" e cole o link.

> Se o Google recusar, o app continua funcionando sem essa permissão (botão "Abrir livro" e "Abrir com"). Aí é só tirar a permissão do manifesto e mandar de novo.

---

## 4. Teste fechado e publicação

Igual ao Gerenciador: teste fechado com 12 pessoas por 14 dias (se a conta pedir), depois **Produção → Criar versão → enviar o `.aab`**.
