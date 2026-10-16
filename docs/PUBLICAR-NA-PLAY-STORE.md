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
Importe seus livros PDF e EPUB, leia ou ouça em voz alta. Sem anúncios.
```

**Descrição completa:**

```
Sua biblioteca no bolso. Leia livros EPUB, FB2, PDF e TXT com conforto, sem anúncios.

📚 BIBLIOTECA COM CAPAS
• Importe livros em PDF ou EPUB (também FB2 e TXT)
• Continuar lendo, Lendo agora, Favoritos, Para ler e Lidos
• Busque por título ou autor

🎧 OUÇA SEUS LIVROS
• O celular lê o livro em voz alta, até com a tela desligada
• Velocidade de 0,75x a 2x e controles na notificação
• Continue lendo exatamente onde a voz parou

🛍️ LOJA (EM BREVE)
• Nossa própria biblioteca de livros para você ler direto no app

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
- Pelo menos 2 capturas de tela: o Início com "Continuar lendo", a biblioteca com as capas, um livro aberto no tema sépia e o player "Ouvir"

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

### Declaração: Serviço em primeiro plano (FOREGROUND_SERVICE_MEDIA_PLAYBACK)

O Play Console pergunta por que o app usa um serviço em primeiro plano. Escolha **Reprodução de mídia** (Media playback) e use o texto:

```
O recurso "Ouvir" lê o livro em voz alta usando o Text-to-Speech do aparelho. A leitura é iniciada pelo usuário e continua com a tela desligada ou com outro app aberto, como um audiolivro. Uma notificação mostra o livro e tem os botões de pausar, avançar, voltar e fechar. O serviço para quando o usuário fecha a notificação ou o livro termina.
```

- **Vídeo:** grave a tela abrindo um livro, tocando em "Ouvir", desligando a tela e mostrando a notificação com os controles. Suba no YouTube como "não listado" e cole o link.

O app **não** pede mais acesso a todos os arquivos: os livros entram pelo botão **Importar** (seletor do sistema) ou pelo "Abrir com".

---

## 4. Teste fechado e publicação

Igual ao Gerenciador: teste fechado com 12 pessoas por 14 dias (se a conta pedir), depois **Produção → Criar versão → enviar o `.aab`**.
