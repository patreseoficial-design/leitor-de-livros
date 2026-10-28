# Leitor de Livros

Leitor de livros para Android: EPUB, FB2, PDF e TXT.

- Visual escuro azul-noite e laranja, com três abas: **Início**, **Biblioteca** e **Loja**
- **Importar** livros em PDF ou EPUB (também FB2 e TXT): o app guarda uma cópia, então o livro não some da biblioteca
- **Início** com o cartão "Continuar lendo" e prateleiras: Lendo agora, Para ler, Favoritos e Adicionados recentemente
- **Biblioteca** em grade de capas com filtros: Todos, Lendo agora, Favoritos, Para ler e Lidos
- **Ouvir**: o celular lê o livro em voz alta (EPUB, FB2 e TXT), com a tela desligada, velocidade de 0,75x a 2x e controles na notificação
- **Loja**: mais de 1.700 livros grátis em domínio público (Project Gutenberg), em português, espanhol e inglês, separados por **faixa etária** (Infantil até 8 anos, Juvenil 9 a 14, Adulto 15+) e por **gênero** (contos de fadas, aventura, mistério, romances, poesia, teatro, história...). Toque em "Baixar e ler" e o livro vai para a Biblioteca
- **Biblioteca** também filtra por faixa etária os livros que vieram da Loja
- Menu de cada livro: detalhes, ler, ouvir, favoritos, para ler, concluído, compartilhar e remover
- Barra de progresso em cada livro e volta exatamente onde você parou
- Leitura confortável: toque nas laterais (ou botões de volume) para virar a página, no meio para ver o menu
- **Temas** claro, sépia e escuro, fonte com ou sem serifa e tamanho da letra
- **Sumário** do livro e barra para pular para qualquer parte
- PDF com zoom (pinça ou toque duplo)
- Busca por título ou autor
- Aparece no "Abrir com" de outros apps
- Em português, inglês e espanhol
- Sem anúncios e sem coleta de dados: a internet só é usada para baixar os livros e as capas da Loja

A lista da Loja fica em `app/src/main/assets/catalogo.json`. Para atualizar: **Actions → Atualizar catálogo da Loja → Run workflow** (o script `tools/gerar_catalogo.py` só aceita livros em domínio público no Brasil, com autor morto há mais de 70 anos).

Documentos:

- Como publicar na Play Store: [docs/PUBLICAR-NA-PLAY-STORE.md](docs/PUBLICAR-NA-PLAY-STORE.md)
- Política de privacidade: [docs/politica-de-privacidade.md](docs/politica-de-privacidade.md)

O GitHub Actions compila o app a cada envio para a `main`. O APK de teste fica em **Actions → (última execução) → Artifacts → app-debug**.
