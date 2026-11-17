# JFPWrite para Android (v0.5.0)

O mesmo editor do computador, empacotado como aplicativo de celular.

- Abrir e salvar documentos `.jfpw` pelo seletor de arquivos do Android.
- Exportar para Word (.docx), livro digital (.epub) e Markdown (.md).
- PDF pela tela de impressão do Android ("Salvar como PDF").
- Revisão de português (gramática, pontuação e estilo) em gaveta na parte de baixo da tela.
- A ortografia fica por conta do corretor do teclado do celular.
- Tudo funciona sem internet e o texto não sai do aparelho.

Limites desta versão: no PDF do celular não saem cabeçalho, rodapé e numeração de página (isso só existe no PC); o dicionário completo do PC não vem junto.

## Como gerar o APK

O projeto compila sozinho no GitHub (aba **Actions**, fluxo "Gerar APK"). O arquivo sai em **Artifacts → JFPWrite-APK**.
Cada APK gerado tem assinatura nova: desinstale a versão antiga antes de instalar a nova.
