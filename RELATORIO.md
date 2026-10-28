# Relatório — enxugamento do núcleo, onda 2

Pacote: `pushinbr/pam-native-notifications`. Branch: `main`. Versão pública: `0.1.2`.

## O que foi feito

- A capacidade opcional passou a pertencer ao plugin; SDK mínimo 1.46.0.
- O núcleo conserva aliases PHP depreciados por uma versão, até 1.47.
- Contratos nativos e dados tipados foram preservados; testes acompanham a implementação.
- Release: [0.1.2](https://github.com/push-in/pam-native-notifications/releases/tag/v0.1.2). Atualização do Packagist solicitada e aceita pela API.

## Validação

- PHP: 35/35 asserções.
- Compile Kotlin conjunto do núcleo e cinco plugins: aprovado.
- Testes JVM conjuntos: 360/360, zero falhas.
- Compilação dos testes instrumentados conjuntos: aprovada; execução em dispositivo pendente.
- SDK PHP com contratos de compatibilidade dos plugins: aprovado.
- Swift: pendente; Mac proibido.
- QA em dispositivo e medição de CPU/PSS: pendentes; o emulador compartilhado estava ocupado. S10 não utilizado.

## Commits de implementação

```text
a193bc4 fix: preserve the host notification glyph without linking its R class [skip ci]
aeb4c81 fix: bound background rendering and add standalone PHP contracts [skip ci]
ab35ca7 fix: reset action listeners with the PHP runtime [skip ci]
```

O commit deste relatório é posterior aos commits acima. Nenhuma release do núcleo 1.46.0 foi publicada.
