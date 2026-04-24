# Implementação da G1 de Sistemas Paralelos e Distribuídos

Alunos: João Victor da Silva Santos e Weversson Lucas Vital da Silva

Implementação do enunciado `G1-SPD-2026-1` usando Java com sockets, threads, autenticação por usuário e persistência em JSON.

## O que foi implementado

- Servidor de leilão com múltiplos clientes simultâneos.
- Interface gráfica do servidor para cadastrar usuários.
- Usuários com senha e roles (`ADMIN`, `COMPRADOR`, `OBSERVADOR`).
- Cadastro de item do leilão.
- Recebimento e validação de lances.
- Notificação em tempo real para todos os participantes.
- Encerramento do leilão com anúncio do vencedor.
- Histórico salvo em arquivo JSON ao encerrar o leilão.
- Usuários persistidos em `usuarios.json`.
- Comunicação por TCP e envio de eventos também por UDP.

## Arquivos

- `AuctionServer.java`: servidor principal.
- `AuctionClient.java`: cliente com interface gráfica em Swing.

## Como compilar

```bash
javac AuctionServer.java AuctionClient.java
```

## Como executar

Inicie o servidor:

```bash
java AuctionServer 5000
```

Abra um ou mais clientes:

```bash
java AuctionClient localhost 5000
```

Ao abrir o cliente:

- preencha `Host`, `Porta`, `Usuário` e `Senha`
- clique em `Conectar`
- use os campos da janela para cadastrar item, enviar lance, atualizar status e encerrar o leilão
- acompanhe tudo no painel em tempo real

## Usuários e roles

Ao iniciar pela primeira vez, o sistema cria um usuário padrão:

- usuário: `admin`
- senha: `admin123`
- role: `ADMIN`

As permissões são:

- `ADMIN`: cadastra item, acompanha o leilão e encerra o leilão.
- `COMPRADOR`: acompanha o leilão e envia lances.
- `OBSERVADOR`: apenas acompanha o leilão.

O cadastro de novos usuários é feito pela interface gráfica do servidor.

## Arquivos gerados

- `usuarios.json`: base simples de usuários, senha e role.
- `historico/leilao-AAAAmmdd-HHMMSS.json`: resultado final do leilão com vencedor, valor final e eventos em memória persistidos ao final.
