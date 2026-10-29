# Googol: guia para a defesa

Este documento explica o projeto em pormenor: **o que** cada parte faz, **como** funciona,
**porquê** foi feita assim e **que conceitos das aulas** usa. Na defesa individual é preciso saber
explicar e alterar qualquer funcionalidade.

Ordem sugerida de estudo: secções 1 a 3 (visão geral e fluxos), 6 e 7 (replicação e falhas, as
partes mais perguntadas), depois as perguntas da secção 12 e o guião da demonstração da secção 13.

## Índice

1. [Visão geral](#1-visão-geral)
2. [Arquitetura](#2-arquitetura)
3. [Fluxos principais](#3-fluxos-principais)
4. [Componentes em detalhe](#4-componentes-em-detalhe)
5. [Interfaces remotas (RMI)](#5-interfaces-remotas-rmi)
6. [Replicação: reliable multicast e sincronização](#6-replicação-reliable-multicast-e-sincronização)
7. [Tolerância a falhas](#7-tolerância-a-falhas)
8. [Concorrência](#8-concorrência)
9. [Conceitos das aulas](#9-conceitos-das-aulas)
10. [Checklist](#10-checklist)
11. [Limitações conhecidas](#11-limitações-conhecidas)
12. [Perguntas prováveis](#12-perguntas-prováveis)
13. [Demonstração na defesa](#13-demonstração-na-defesa)

---

## 1. Visão geral

O Googol é um motor de pesquisa Web distribuído, feito em Java RMI. Funcionalidades pedidas no
enunciado:

1. **Indexar** um URL introduzido pelo utilizador.
2. **Indexar iterativamente** todos os links encontrados (web crawler).
3. **Pesquisar** páginas que contêm **todos** os termos; cada resultado mostra título, URL e citação.
4. **Ordenar** os resultados pelo número de ligações recebidas de outras páginas.
5. **Consultar** as páginas que têm ligação para um URL (backlinks).
6. **Estatísticas em tempo real**: as 10 pesquisas mais comuns, os Barrels ativos com o tamanho do
   índice, e o tempo médio de resposta por Barrel em décimas de segundo. São enviadas só quando
   mudam.
7. Resultados **de 10 em 10**.

Requisitos não funcionais: redundância (dois Barrels idênticos via reliable multicast), failover
(o sistema funciona com pelo menos um Barrel e a Gateway), recuperação de crashes, balanceamento de
carga, Downloaders em paralelo, configuração em `.properties` e execução em várias máquinas.

### Os programas e o código

| Pacote | Classes | Papel |
|---|---|---|
| `common` | `Config`, `PageData`, `IndexMessage`, `SearchResult`, `Stats`, `Tokenizer`, `UrlNormalizer` | Configuração e classes partilhadas |
| `queue` | `URLQueue`, `URLQueueInterface` | Fila de URLs (programa próprio) |
| `downloader` | `Downloader`, `DownloaderInterface`, `ReliableMulticast` | Web crawler e envio para os Barrels |
| `barrel` | `Barrel`, `BarrelInterface` | Índice invertido replicado e persistente |
| `gateway` | `Gateway`, `GatewayInterface`, `StatsListener`, `NoBarrelAvailableException` | Porta de entrada dos clientes |
| `client` | `Client` | Interface de consola |

---

## 2. Arquitetura

```
   ┌────────┐  indexUrl / search / getBacklinks / subscribeStats / ping
   │ Client │ ─────────────────────────────────────────────┐
   └────────┘ ◀── statsUpdated (callback) ──┐               ▼
                                         ┌──┴───────────────────────┐
                                         │         Gateway          │  registry próprio (gateway.port)
                                         │ data/gateway-state.ser   │  lista de Barrels com lease
                                         └──┬────────▲──────────┬───┘
                     search / getBacklinks  │        │          │ addUrlPriority
                                            ▼        │          ▼
   ┌──────────┐   getSnapshot /   ┌──────────┐       │     ┌──────────┐
   │ Barrel 1 │◀─ getLastSeqs ──▶│ Barrel 2 │       │     │ URLQueue │  registry próprio (rmi.port)
   └────▲─────┘  (sincronização)  └────▲─────┘       │     └─┬────▲───┘
        │ registerBarrel (heartbeat)   │ ────────────┘       │    │ addUrls / retryUrl /
        │                              │                     │    │ downloaderReady / ping
        │   deliver(IndexMessage)      │          download(url) (callback)
        │   reliable multicast         │                     ▼    │
        └──────────────┬───────────────┘               ┌──────────┴─┐
                       └─────────────────────────────── │ Downloader │ (vários) ── getBarrels() à Gateway
                                                        └────────────┘
```

**Quem chama quem:**

| Quem chama | Método remoto | Em quem | Para quê |
|---|---|---|---|
| Client | `indexUrl`, `search`, `getBacklinks`, `getStats`, `subscribeStats`, `unsubscribeStats`, `ping` | Gateway | Todas as funcionalidades do utilizador |
| Gateway | `statsUpdated` (**callback**) | Client | Enviar as estatísticas quando mudam |
| Gateway | `search`, `getBacklinks` | Barrel | Responder aos clientes |
| Gateway | `addUrlPriority` | URLQueue | URL pedido por um utilizador |
| Barrel | `registerBarrel` (heartbeat) | Gateway | Entrar e manter-se na lista de Barrels ativos |
| Barrel | `getBarrels`, e `getSnapshot`/`getLastSeqs` nos outros Barrels | Gateway, Barrel | Sincronização entre réplicas |
| Downloader | `getBarrels` | Gateway | Saber para que Barrels enviar as páginas |
| Downloader | `deliver` | Barrel | Reliable multicast das páginas |
| Downloader | `addUrls`, `retryUrl`, `downloaderReady`, `ping` | URLQueue | Devolver links, pedir nova tentativa, avisar que está livre |
| URLQueue | `download` (**callback**) | Downloader | Entregar um URL a um Downloader livre |

### Decisões de arquitetura

- **Os Barrels registam-se na Gateway, não num registry.** Um programa não pode fazer `bind` num
  registry de outra máquina (o RMI recusa por segurança), por isso o Barrel da máquina #2 não se
  podia registar no registry da #1. Cada Barrel chama `registerBarrel(nome, stub, ...)` na Gateway,
  que é a solução sugerida pelo enunciado ("a Gateway receber uma referência remota para os
  Barrels"). Os Downloaders pedem a lista à Gateway.
- **Um registry por servidor.** A Gateway e a URLQueue criam cada uma o seu (`gateway.port` e
  `rmi.port`). Se partilhassem um, a morte do processo que o criou apagava também o registo do outro.
- **Fila como programa separado.** O enunciado deixa a escolha livre. Separada, pode avariar e
  reiniciar sem afetar a Gateway nem os Barrels.
- **Modelo "push" na fila.** A fila entrega os URLs por callback (`download(url)`) aos Downloaders
  livres, em vez de cada Downloader perguntar periodicamente. Não há espera ativa, e cada URL é
  entregue a um só Downloader.
- **A Gateway não está no caminho dos dados.** As páginas vão do Downloader diretamente para os
  Barrels. Se a Gateway avariar, a indexação continua com a última lista de Barrels conhecida.

---

## 3. Fluxos principais

### 3.1 Um utilizador pede para indexar um URL
1. Client → `gateway.indexUrl(url)`.
2. A Gateway normaliza o URL (`UrlNormalizer`); se não for http/https, devolve `INVALID`.
3. A Gateway chama `queue.addUrlPriority(url)`. O URL entra no **início** da fila (o enunciado pede
   que seja visitado "assim que possível"). A Gateway devolve `QUEUED`, ou `ALREADY_KNOWN` se já foi
   visitado.
4. Se a fila estiver em baixo, a Gateway grava o URL em `pendingUrls` (e no ficheiro) e devolve
   `PENDING`; uma thread reenvia-o quando a fila voltar.
5. O dispatcher da fila entrega o URL a um Downloader livre: `downloader.download(url)` (callback).
6. O Downloader descarrega a página com o jsoup e extrai título, citação, palavras e links.
7. Envia a página a **todos** os Barrels por reliable multicast (`deliver`).
8. Devolve os links à fila (`addUrls`), que guarda os que ainda não viu: é a indexação iterativa.

### 3.2 Pesquisa
1. Client → `gateway.search(termos, página)`.
2. A Gateway escolhe um Barrel (balanceamento por desempenho) e chama `barrel.search`.
3. O Barrel faz a interseção dos conjuntos de URLs de cada termo, ordena por ligações recebidas e
   devolve só os 10 resultados da página pedida.
4. Se o Barrel falhar (`RemoteException`), a Gateway marca-o como suspeito e repete o pedido no
   seguinte (failover). O cliente não vê o erro.
5. A Gateway mede o tempo de resposta e, se for a primeira página, conta a pesquisa para o top 10.
   Ambos alteram as estatísticas, que são enviadas aos clientes subscritos.

### 3.3 Estatísticas em tempo real
1. O Client exporta-se como objeto remoto (`StatsListener`) e chama `subscribeStats(stub)`.
2. A Gateway envia logo as estatísticas atuais e, a partir daí, chama `statsUpdated(stats)` **só
   quando mudam** (pesquisa, Barrel novo, removido ou com outro tamanho de índice).
3. Não há polling das estatísticas: o cliente nunca as pede periodicamente.

### 3.4 Um Barrel arranca (novo, ou depois de um crash)
1. Carrega o índice do ficheiro `data/<nome>.ser`, se existir.
2. Regista-se na Gateway como **não pronto**. Os Downloaders passam a enviar-lhe as páginas novas,
   mas a Gateway ainda não lhe envia pesquisas.
3. Copia o índice (snapshot) do Barrel ativo com mais páginas e junta-o ao seu (união).
4. Grava e regista-se como **pronto**.

---

## 4. Componentes em detalhe

### 4.1 `common`: configuração e classes partilhadas

**`Config`**
- Lê o `googol.properties` (ou o ficheiro passado como último argumento) com `java.util.Properties`,
  em UTF-8. Tem `get`, `getInt` e `getList`.
- Uma chave em falta ou um número inválido dá uma mensagem clara com o nome da chave
  (`Missing property: ...`, `Property X must be an integer, found: abc`).
- `exitWithError(msg)` é usado pelos `main()` para erros previsíveis no arranque (ficheiro em falta,
  valor inválido, porto ocupado). Mostra `Error: ...` numa linha e termina, sem stack trace.
- `applyLocalHostname()`, chamado no arranque de todos os programas, faz duas coisas:
  - Define `java.rmi.server.hostname`, **o IP que fica escrito nos stubs** que esta máquina exporta.
    Sem isto, um stub criado na máquina #2 podia dizer "liga-me em 127.0.0.1" e a máquina #1 ligava
    a si própria. É indispensável para os callbacks entre máquinas.
  - Define os **tempos limite do RMI** (`rmi.timeout.ms`). Por omissão o RMI espera para sempre pela
    resposta (`sun.rmi.transport.tcp.responseTimeout = 0`), e ligar a uma máquina desligada só falha
    ao fim do tempo do sistema operativo (~21 s no Windows). Uma máquina que desaparece sem fechar as
    ligações (rede desligada, VM suspensa) bloqueava assim a Gateway, sem nunca dar
    `RemoteException` nem failover. O tempo de resposta é definido por essa propriedade, e o tempo de
    ligação por uma fábrica de sockets própria (`TimeoutSocketFactory`). O valor por omissão, 10 s, é
    largo o suficiente para gravar ou copiar um índice grande.

**`PageData`, `IndexMessage`, `SearchResult`, `Stats`**: são **passadas por valor** em RMI
(`Serializable`, com `serialVersionUID` fixo): são serializadas, enviadas e reconstruídas do outro
lado.
- `PageData`: URL, título, citação, palavras (`HashSet`) e links (`HashSet`).
- `IndexMessage`: `(senderId, seq, PageData)`, a mensagem do reliable multicast.
- `SearchResult`: URL, título, citação e número de ligações recebidas.
- `Stats` (record): top 10 de pesquisas e, por Barrel ativo, páginas, palavras, número de pesquisas e
  tempo médio em décimas de segundo. O `equals` gerado pelo record é usado para só enviar as
  estatísticas quando mudam.

**`Tokenizer.words(texto)`**
- Passa o texto a minúsculas e separa-o por tudo o que não é letra nem dígito (`[^\p{L}\p{N}]+`).
  Mantém os acentos e remove repetidos. Exemplo: "UC-2026" dá `uc` e `2026`.
- É usado pelo Downloader (texto das páginas) **e** pelo Barrel (termos de pesquisa). Se
  normalizassem de forma diferente, "Coimbra" não encontrava "coimbra".
- Usa `Locale.ROOT`: o resultado não depende da língua do sistema (em turco, "I" minúsculo é "ı").

**`UrlNormalizer.normalize(url)`**
- Esquema e host em minúsculas, sem o porto por omissão (80/443) nem o fragmento `#...`; um caminho
  vazio passa a `/`. O caminho e a query mantêm-se (o servidor distingue maiúsculas no caminho).
  Devolve `null` se não for http/https.
- Exemplo: `HTTPS://WWW.UC.PT:443#topo` e `https://www.uc.pt` dão ambos `https://www.uc.pt/`.
- Sem isto, a mesma página era visitada duas vezes e as ligações recebidas ficavam divididas entre
  duas "páginas", o que estragava a ordenação.

### 4.2 `queue.URLQueue`: fila de URLs

**Estruturas** (todas de `java.util.concurrent`, porque o RMI executa cada chamada remota numa thread
própria):

| Estrutura | Para quê |
|---|---|
| `LinkedBlockingDeque<String> urls` | URLs por visitar. `take()` bloqueia enquanto estiver vazia (sem espera ativa). É uma *deque* para os URLs dos utilizadores entrarem pelo início. |
| `LinkedBlockingQueue<DownloaderInterface> availableDownloaders` | Downloaders livres |
| `Set<String> seen` (`ConcurrentHashMap.newKeySet()`) | URLs que já entraram na fila. `seen.add(url)` é atómico: só quem o adiciona primeiro põe o URL na fila. É isto que garante que **cada URL é indexado por um só Downloader**. |
| `ConcurrentHashMap<String,Integer> retries` | Tentativas por URL (`merge` incrementa de forma atómica) |

**Dispatcher:** uma thread repete `availableDownloaders.take()` → `urls.take()` →
`downloader.download(url)`. A última chamada é um callback: é a fila que invoca o Downloader.

**URLs dos utilizadores (`addUrlPriority`):** entram pelo início (`addFirst`). Se já estavam na fila,
passam para o início. Devolve `false` se o URL já foi (ou está a ser) visitado. Sem isto, o URL do
utilizador ficava atrás de milhares de links descobertos pelo crawler.

**Falhas:**
- **Download com erro temporário:** o Downloader chama `retryUrl`, e o URL volta à fila até
  `queue.max.retries` vezes. Depois é descartado, para não ficar a tentar links mortos para sempre.
- **Downloader morto:** `download()` lança `RemoteException`. O URL volta ao **início** da fila e
  esse Downloader não volta à lista de livres.
- **Erro inesperado no dispatcher (`RuntimeException`):** é apanhado e o URL volta à fila. Sem isto,
  a thread morria sem aviso e a fila deixava de distribuir URLs.

### 4.3 `downloader.Downloader`: web crawler

**Ciclo de trabalho:**
1. Ao arrancar, exporta-se (`UnicastRemoteObject.exportObject(this, 0)`), liga-se à fila e chama
   `downloaderReady(self)`.
2. `download(url)` **cria uma thread e retorna logo**. Se o download fosse feito dentro da chamada
   remota, o dispatcher da fila ficava bloqueado e não entregava URLs aos outros Downloaders.
3. `Jsoup.connect(url).get()` e depois `extract(url, doc)`:
   - **Título:** o `<title>` da página.
   - **Citação:** os primeiros 150 caracteres do texto.
   - **Palavras:** as do título e do texto. O título está no `<head>`, fora do `body`; sem isto, uma
     palavra que só aparecesse no título não era encontrada.
   - **Links:** absolutos (`absUrl`) e normalizados, só http/https.
4. Envia a página aos Barrels (`multicast`) e depois os links à fila (`addUrls`, uma só chamada
   remota por página).
5. No `finally`, faz a pausa `downloader.delay.ms` e chama sempre `downloaderReady`. Mesmo depois de
   um erro, o Downloader volta a ficar disponível.

**Falhas de download:**
- `isPermanent` separa os erros permanentes, que **não** voltam à fila (HTTP 4xx exceto 408 e 429,
  PDF e outros conteúdos que não são HTML, URL inválido), dos temporários (timeout, ligação recusada,
  5xx), que voltam com `retryUrl`.
- O log mostra uma descrição curta (`describeFailure`: "HTTP 404", "not an HTML page
  (application/pdf)", "timeout") em vez do nome da exceção.

**Ligação à fila:**
- Um Downloader **livre** só espera por um callback e nunca chama a fila. Se a fila reiniciasse, a
  nova fila não o conhecia e ele ficava parado para sempre.
- Por isso, uma thread (`heartbeatLoop`) chama `queue.ping()` a cada `downloader.reconnect.ms`. Se
  falhar, `reconnect()` procura a fila no registry até ela voltar, e o Downloader regista-se na nova
  fila. O mesmo mecanismo permite arrancar o Downloader **antes** da fila.
- Para não se registar duas vezes (receberia dois URLs ao mesmo tempo), o estado `busy` /
  `registeredWith` é protegido por um `lock`, e os stubs são comparados com `equals()` (que compara o
  objeto remoto).
- **Fila em baixo no fim de um download** (`sendLinks`): a página já foi para os Barrels, por isso
  não é uma falha do URL. O Downloader volta a ligar-se à fila e repete o envio dos links até
  conseguir; a fila ignora os que já viu.

**Lista de Barrels:** outra thread pede `getBarrels()` à Gateway a cada `downloader.reconnect.ms` e
atualiza o multicast (secção 6).

**Paralelismo:** cada processo trata um URL de cada vez. O paralelismo vem de correr vários
Downloaders, numa ou em várias máquinas.

### 4.4 `barrel.Barrel`: Index Storage Barrel

**Dados** (classe interna `IndexData`, `Serializable`):

| Mapa | Conteúdo |
|---|---|
| `index` | palavra → URLs onde aparece (**índice invertido**) |
| `pages` | URL → {título, citação} |
| `backlinks` | URL → URLs das páginas que têm ligação para ele |
| `lastSeq` | Downloader (`senderId`) → último `seq` entregue (reliable multicast) |

**Pesquisa (`search(query, page)`):**
1. Divide a pesquisa em termos com o `Tokenizer`.
2. Faz a **interseção** (`retainAll`) dos conjuntos de URLs de cada termo: só ficam as páginas com
   **todos** os termos (AND).
3. Ordena por número de backlinks (decrescente) e, em caso de empate, pelo URL. A ordem tem de ser
   sempre a mesma para a página 2 não repetir nem saltar resultados da página 1.
4. Devolve só os resultados `[page×10, page×10+10)`.

**Receção (`deliver`):** ignora mensagens com `seq <= lastSeq[senderId]` (retransmissões já
recebidas). Guardar uma página (`storePage`) é idempotente, porque usa conjuntos.

**Concorrência:** todos os métodos que tocam no índice são `synchronized`. As pesquisas da Gateway,
as entregas dos Downloaders e as cópias para outros Barrels chegam em paralelo.

**Persistência:**
- Grava o `IndexData` inteiro com `ObjectOutputStream` em `data/<nome>.ser`: periodicamente
  (`barrel.save.interval.ms`, só se houver alterações), depois de cada sincronização e num shutdown
  hook (Ctrl+C).
- **Escrita atómica:** escreve em `<nome>.ser.tmp` e faz `Files.move(..., ATOMIC_MOVE)`. Um crash a
  meio da escrita nunca corrompe o ficheiro principal.
- Ao arrancar, carrega o ficheiro; se estiver corrompido, arranca vazio.
- O nome do Barrel vem nos argumentos, para as duas máquinas poderem usar o mesmo `.properties`.

**Heartbeat:** uma thread chama `gateway.registerBarrel(nome, stub, páginas, palavras, pronto)` a
cada `barrel.heartbeat.ms`. O heartbeat leva o tamanho do índice, por isso a Gateway mostra-o nas
estatísticas sem ter de perguntar. Se a Gateway estiver em baixo, o Barrel continua a tentar e volta
a aparecer na lista quando ela reinicia. A sincronização (secção 6.2) também corre nesta thread.

### 4.5 `gateway.Gateway`

A Gateway **não guarda o índice**: responde aos clientes pedindo aos Barrels.

**Registo dos Barrels (lease):**
- `nome → (stub, último heartbeat, páginas, palavras, pronto, suspeito)` num `ConcurrentHashMap`.
- Um registo só vale durante `gateway.barrel.timeout.ms`. Uma thread retira os Barrels que deixaram de
  o renovar. `remove(chave, valor)` garante que não retira um registo renovado entretanto.
- `registerBarrel` devolve `true` se o registo é novo (Barrel desconhecido, ou retirado por a lease ter
  expirado). O Barrel usa isto para saber que pode ter perdido mensagens.
- Um Barrel só recebe pesquisas e aparece nas estatísticas se estiver **pronto e não suspeito**.

**Balanceamento por desempenho (decisão do grupo):**
- A Gateway mede o tempo de cada pesquisa por Barrel (`System.nanoTime`).
- O Barrel é **sorteado com probabilidade proporcional a 1/tempo médio**: um Barrel duas vezes mais
  rápido recebe o dobro das pesquisas. Se escolhesse sempre o mais rápido, com tempos parecidos um
  Barrel recebia tudo e o outro nunca era usado nem medido. Um Barrel ainda sem medições recebe o
  peso do mais rápido, para começar a ser medido.
- **Sem cache** (decisão do grupo; era opcional): os resultados estão sempre atualizados e todas as
  pesquisas contam para o tempo médio.

**Failover:**
- `callBarrels` tenta os Barrels pela ordem escolhida. Com `RemoteException`, marca o Barrel como
  **suspeito** e repete o **mesmo pedido** no seguinte.
- Um Barrel suspeito deixa de receber pesquisas e de contar como ativo, mas **continua na lista dos
  Downloaders** até a lease expirar. Se a Gateway o retirasse logo, os Downloaders descartavam as
  mensagens guardadas para ele. O próximo heartbeat, se o Barrel estiver vivo, limpa a marca.
- Sem nenhum Barrel disponível: `NoBarrelAvailableException`. É uma exceção própria, diferente de
  `RemoteException`, porque a Gateway está a funcionar: o cliente mostra a mensagem em vez de tentar
  outra ligação.

**Indexação (`indexUrl`):** devolve `QUEUED`, `ALREADY_KNOWN`, `INVALID` ou `PENDING` (secção 3.1).
- Com a fila em baixo, o URL vai para `pendingUrls`. Uma thread reenvia os pendentes, por ordem, a
  cada `gateway.queue.retry.ms`.
- Um URL só sai da lista **depois** de a fila o aceitar (`peekFirst` → envio → `removeFirstOccurrence`).

**Estatísticas:**
- Top 10: conta só a **primeira página** de cada pesquisa (avançar nos resultados não é uma nova
  pesquisa), com os termos normalizados e ordenados ("Coimbra universidade" = "universidade coimbra").
- Tempo médio em **décimas de segundo** (1 décima = 100 ms).
- Sempre que algo pode ter mudado, `statsChanged()` calcula as estatísticas e **só as envia se forem
  diferentes das últimas** (`equals` do record). Heartbeats sem alterações não geram envios.
- Os envios são feitos por uma thread própria (`notifier`), para um cliente lento não atrasar as
  pesquisas. Um cliente que já não responde é retirado da lista.
- A lista de clientes é uma `CopyOnWriteArrayList` (percorrida nos envios enquanto outros se
  subscrevem). `subscribeStats` usa `addIfAbsent`: uma subscrição repetida não duplica os envios.

**Recuperação de um crash da própria Gateway:**
- O enunciado diz que a Gateway "não tem de" guardar dados, mas não o proíbe. Guarda num ficheiro
  (`gateway.data.file`) só o que não se consegue recalcular: os **URLs pendentes** e as **contagens de
  pesquisas** (decisão do grupo).
- Os URLs pendentes são gravados **antes** de responder `PENDING` (um crash logo a seguir não perde o
  pedido); as contagens, periodicamente e ao terminar.
- Os tempos médios por Barrel não são gravados (decisão do grupo): são medições do momento e voltam
  a ser medidos.
- Escrita atómica como no Barrel. Ao arrancar, carrega o ficheiro. Os Barrels voltam a registar-se
  sozinhos pelo heartbeat, e os clientes voltam a subscrever as estatísticas.

### 4.6 `client.Client`

O enunciado pede uma UI simples que **só invoca métodos remotos** da Gateway: o Client não fala com
mais nenhum componente.

**Menu:** `1` indexar, `2` pesquisar, `3` ligações para um URL, `4` estatísticas, `0` sair.
- Na pesquisa, cada resultado mostra título, URL, citação e número de ligações recebidas, **de 10 em
  10**: `s` seguinte, `a` anterior, `l N` backlinks do resultado N, `m` menu.
- Uma página com menos de 10 resultados é a última.

**Failover (enunciado 5.1: "em caso de avaria devem reiniciar e fazer retry"):**
- Todos os pedidos passam por `call(pedido)`. Com `RemoteException`, o Client volta a procurar a
  Gateway no registry e **repete o mesmo pedido** a cada `client.retry.ms`, até `client.retry.attempts`
  tentativas (decisão do grupo: limitado e configurável). Depois avisa e volta ao menu. Nenhuma
  operação fica a meio sem o utilizador saber.
- Se a referência que tinha falha mas a Gateway já reiniciou, o Client troca-a logo, sem esperar nem
  contar como tentativa: o utilizador nem nota o reinício.
- `NoBarrelAvailableException` não é repetida: mostra a mensagem.
- Um erro inesperado vindo da Gateway (`RuntimeException`) é apanhado: o Client avisa e continua.
- **At-most-once:** uma `RemoteException` não diz se o pedido chegou a ser executado. Se o primeiro
  `indexUrl` pôs o URL na fila e só a resposta se perdeu, a repetição devolve `ALREADY_KNOWN`. Por
  isso, se houve uma falha pelo caminho, o Client mostra "Pedido aceite" em vez de "já indexado".
  Repetir é seguro porque `indexUrl`, `search` e `getBacklinks` são idempotentes.

**Estatísticas (ecrã próprio até Enter; decisão do grupo):**
- O Client implementa `StatsListener` e exporta-se, por isso precisa de `rmi.hostname.local` correto:
  é a Gateway que se liga de volta ao Client.
- Se a Gateway reiniciar, a subscrição perde-se e não chegaria mais nenhum callback. Por isso, uma
  thread faz `ping()` à Gateway a cada `client.retry.ms`. Se falhar, procura a nova Gateway e
  **subscreve-se de novo**. O ping não traz estatísticas, por isso continua a não haver refrescamento
  periódico.

---

## 5. Interfaces remotas (RMI)

Todas estendem `Remote`, e todos os métodos declaram `throws RemoteException`. Os objetos remotos são
exportados com `UnicastRemoteObject.exportObject(obj, 0)` (porto escolhido pelo sistema). Os stubs
passam **por referência**; `PageData`, `SearchResult`, `Stats`, etc. passam **por valor**.

| Interface | Método | Chamado por | Descrição |
|---|---|---|---|
| `URLQueueInterface` | `addUrls(urls)` | Downloader | Acrescenta os links de uma página ao fim (ignorados se já vistos) |
| | `addUrl(url)` | (disponível; a fila usa-o internamente) | Acrescenta um URL ao fim |
| | `addUrlPriority(url)` | Gateway | URL de utilizador no início; `false` se já visitado |
| | `retryUrl(url)` | Downloader | Nova tentativa depois de um erro temporário (limitada) |
| | `downloaderReady(stub)` | Downloader | O Downloader está livre (a fila guarda o stub para o callback) |
| | `ping()` | Downloader | Verifica se a fila está viva |
| `DownloaderInterface` | `download(url)` | URLQueue (**callback**) | Processa o URL numa thread nova e retorna logo |
| `BarrelInterface` | `deliver(IndexMessage)` | Downloader | Reliable multicast; o retorno normal é o ACK |
| | `search(query, page)` | Gateway | Página `page` (10 resultados) das páginas com todos os termos |
| | `getBacklinks(url)` | Gateway | Páginas com ligação para o URL |
| | `getPageCount()`, `getWordCount()` | Barrel | Tamanho do índice (escolha do dador na sincronização) |
| | `getSnapshot()` | Barrel | Cópia serializada do índice inteiro |
| | `getLastSeqs()` | Barrel | Vetor `lastSeq` (deteção de mensagens em falta) |
| `GatewayInterface` | `registerBarrel(nome, stub, páginas, palavras, pronto)` | Barrel | Heartbeat; devolve `true` se o registo é novo |
| | `getBarrels()` | Downloader, Barrel | Barrels registados (nome → stub) |
| | `indexUrl(url)` | Client | `QUEUED` / `ALREADY_KNOWN` / `INVALID` / `PENDING` |
| | `search(query, page)` | Client | Pesquisa com balanceamento e failover |
| | `getBacklinks(url)` | Client | Ligações para um URL |
| | `getStats()` | Client | Estatísticas atuais |
| | `subscribeStats(listener)`, `unsubscribeStats(listener)` | Client | Subscrição das estatísticas |
| | `ping()` | Client | Deteta uma Gateway reiniciada (para voltar a subscrever) |
| `StatsListener` | `statsUpdated(stats)` | Gateway (**callback**) | Novas estatísticas, só quando mudam |

**Callbacks:** a fila → Downloader (`download`) e a Gateway → Client (`statsUpdated`). Em ambos os
casos, quem recebe o callback exporta-se e envia o seu stub ao servidor. Por isso é preciso
`java.rmi.server.hostname` com o IP certo em cada máquina.

---

## 6. Replicação: reliable multicast e sincronização

Objetivo (enunciado e t08): os Barrels têm de ter **informação idêntica**, mesmo com avarias de
omissão (mensagens ou respostas perdidas, Barrel temporariamente em baixo).

### 6.1 Reliable multicast (`ReliableMulticast` + `Barrel.deliver`)

**Algoritmo:** ACK e retransmissão pelo emissor, com ordem FIFO por emissor.
1. Cada Downloader tem um `senderId` (UUID gerado no arranque) e numera as mensagens com `seq`
   crescente: `IndexMessage(senderId, seq, page)`.
2. `multicast(page)` coloca a mensagem na fila de **cada** Barrel. Há um `Channel` por Barrel, com a
   sua `LinkedBlockingDeque` e a sua thread, por isso os Barrels são servidos **em paralelo**: um
   Barrel lento ou avariado não atrasa os outros.
3. A thread do canal envia as mensagens **por ordem**: `stub.deliver(m)`. O retorno normal é o
   **ACK**, e só então a mensagem sai da fila.
4. Com `RemoteException`, a mensagem volta ao **início** da fila (mantém a ordem) e é retransmitida a
   cada `multicast.retry.ms` até haver ACK.
5. O Barrel ignora `seq <= lastSeq[senderId]`. Isto trata o **ACK perdido**: o Barrel processou a
   mensagem, a resposta não chegou, e a retransmissão é descartada.

| Propriedade (t08) | Como é garantida |
|---|---|
| Integridade (entregue no máximo uma vez) | Filtro `lastSeq` no Barrel; `storePage` idempotente |
| Validade (o emissor entrega o que envia) | A mensagem fica na fila de cada Barrel até ao ACK |
| Acordo (se um Barrel correto entrega, todos entregam) | Retransmissão até ao ACK para todos os Barrels, mais a sincronização (6.2) para os casos que a retransmissão não cobre |
| Ordem FIFO por emissor | Uma fila e uma thread por Barrel; só avança depois do ACK |

**Lista de Barrels** (pedida à Gateway a cada `downloader.reconnect.ms`):
- **Barrel novo:** é criado um canal; o que já existia antes chega pela sincronização.
- **Barrel reiniciado** (stub novo): o canal passa a usar o novo stub e entrega-lhe as mensagens
  pendentes.
- **Barrel fora da lista e cujas entregas falham** (retirado pela Gateway): o canal é removido e as
  pendentes descartadas, para não acumular memória sem limite. O Barrel recupera-as pela
  sincronização quando voltar.
- **Barrel fora da lista mas que responde:** mantém-se. Acontece logo depois de a Gateway reiniciar,
  antes de o Barrel renovar o registo. Encontrámos este caso nos testes: sem esta regra, um reinício
  da Gateway fazia o Downloader deixar de enviar para um Barrel ativo.
- **Sem Barrels:** `multicast()` espera com `wait()` em ciclo até `updateBarrels` fazer `notifyAll()`.
  A página não se perde.

**Porque não R-multicast?** No R-multicast, cada Barrel reenvia a mensagem aos outros na primeira
receção, o que dá acordo mesmo que o emissor morra a meio. Escolhemos ACK + retransmissão pelo
emissor porque é mais simples e cobre as omissões pedidas no enunciado. Os casos que não cobre
(Downloader que morre com mensagens pendentes, Barrel retirado da lista) ficam cobertos pela
sincronização entre Barrels.

### 6.2 Sincronização entre Barrels

Cobre o que o multicast sozinho não garante:
- Barrel morto com `kill -9`: o ACK é dado antes de gravar em disco, por isso perde o que recebeu
  depois da última gravação.
- Barrel em baixo mais tempo do que a lease: os Downloaders descartaram as mensagens para ele.
- Downloader que morre com mensagens pendentes para um Barrel em baixo.
- Barrel novo (vazio): é o extra "novo Barrel sincroniza-se com os existentes".

**Snapshot e união:**
- `getSnapshot()` devolve o índice serializado para `byte[]` **dentro do lock**. Se devolvesse o
  objeto, o RMI serializava-o depois de o método retornar, já sem lock, enquanto outras threads o
  alteravam.
- `merge(snapshot)` junta-o ao índice local. O índice só cresce (conjuntos), por isso juntar é uma
  **união**: a ordem não importa e repetir não altera nada. As mensagens que chegam por multicast
  durante a sincronização não se perdem.
- `lastSeq` fica com o **máximo** por Downloader: as mensagens pendentes que já vieram no snapshot são
  depois descartadas como repetidas.

**Quando sincroniza:**
1. **No arranque:** regista-se como não pronto (recebe mensagens, mas não pesquisas), copia o snapshot
   do Barrel com mais páginas (se falhar, tenta o seguinte), grava e fica pronto. Sem outro Barrel,
   usa só o seu ficheiro.
2. **Quando a Gateway diz que o registo é novo** (lease expirada ou Gateway reiniciada): sincroniza de
   novo, mas continua pronto (decisão do grupo: depois de um reinício da Gateway, os dois Barrels
   deixariam de responder ao mesmo tempo).
3. **Quando lhe faltam mensagens (`checkMissing`, em cada heartbeat):** pede aos outros Barrels o
   vetor `lastSeq` (`getLastSeqs`). Se outro já tinha, **no heartbeat anterior**, um `seq` maior para
   algum Downloader, faltam mensagens a este, e sincroniza-se com esse Barrel. Comparar com o
   heartbeat anterior evita sincronizar por causa de mensagens que estão só a caminho.

**Porque é preciso o ponto 3? (caso encontrado pelos testes):** um Barrel novo tira o snapshot, mas o
Downloader só atualiza a lista de Barrels a cada `downloader.reconnect.ms`. Uma página indexada nesse
intervalo ia só para os outros: não estava no snapshot nem era enviada ao novo Barrel. A comparação
dos números de sequência deteta essa falta. Detetar só quando chega a mensagem seguinte não
chegava, porque essa página podia ser a última.

**Único caso não coberto:** os dois Barrels morrerem ao mesmo tempo com `kill -9`. Perde-se o que
receberam depois da última gravação (no máximo `barrel.save.interval.ms`).

---

## 7. Tolerância a falhas

| Falha | O que acontece | Testado |
|---|---|---|
| Barrel morre durante uma pesquisa | A Gateway repete o pedido noutro Barrel; o que falhou fica suspeito | ✅ |
| Barrel em baixo durante a indexação | Os Downloaders guardam as mensagens para ele e retransmitem-nas; ao reiniciar, também se sincroniza | ✅ |
| Barrel morto antes de gravar (`kill -9`) | Ao reiniciar, carrega o ficheiro e copia o que falta de outro Barrel | ✅ |
| Barrel que deixa de responder (máquina desaparecida, rede desligada) | Ao fim de `rmi.timeout.ms` a chamada dá erro e há failover | ✅ (Barrel "pendurado" simulado) |
| Barrel novo | Sincroniza-se e só depois responde a pesquisas | ✅ |
| Só um Barrel ativo | Tudo continua a funcionar com ele | ✅ |
| Nenhum Barrel ativo | Pesquisas: mensagem `NoBarrelAvailableException`. Downloaders: esperam antes de enviar | ✅ |
| Downloader livre morre | A fila deteta-o quando lhe tenta enviar um URL; o URL volta ao início da fila | ✅ |
| Downloader morre a meio de um download | O URL em curso perde-se (limitação 1) | ❌ |
| Download falha (temporário / permanente) | Temporário: até `queue.max.retries` novas tentativas. Permanente: não se repete | ✅ |
| Fila morre e reinicia | Downloaders detetam pelo `ping` e registam-se na nova fila; os URLs que estavam na fila perdem-se (limitação 4) | ✅ (reinício) |
| Fila em baixo quando um utilizador pede um URL | A Gateway guarda-o (também em ficheiro) e reenvia-o quando a fila voltar | ✅ |
| Fila em baixo no fim de um download | O Downloader guarda os links e reenvia-os quando a fila voltar | ✅ |
| Gateway morre e reinicia | Estado (pendentes, top 10) lido do ficheiro; Barrels voltam a registar-se; Downloaders voltam a obter a lista; Clients repetem os pedidos e voltam a subscrever as estatísticas | ✅ |
| Gateway em baixo durante muito tempo | Os Downloaders continuam a indexar com a última lista conhecida; o Client avisa ao fim de N tentativas | ✅ |
| Resposta perdida a um pedido do Client | O Client repete; pedidos idempotentes; "Pedido aceite" na indexação | ✅ |
| Erro previsível no arranque (porto ocupado, configuração) | `Error: ...` numa linha, sem stack trace | ✅ |

**Deteção de falhas:**
- Por **exceção**: `RemoteException` numa chamada. Com `rmi.timeout.ms`, isto acontece também quando
  a outra máquina simplesmente deixa de responder.
- Por **heartbeat**:
  - os Barrels renovam a lease na Gateway;
  - os Downloaders fazem `ping` à fila;
  - os Clients fazem `ping` à Gateway no ecrã de estatísticas.

---

## 8. Concorrência

| Programa | Threads | Sincronização |
|---|---|---|
| URLQueue | Threads do RMI (chamadas remotas) + `dispatcher` | `LinkedBlockingDeque`/`Queue`, `ConcurrentHashMap` (sem locks explícitos) |
| Downloader | Uma por download, `queue-heartbeat`, `barrel-list`, uma `multicast-<Barrel>` por Barrel | `lock` para `busy`/`registeredWith`; `ReliableMulticast` com `synchronized` + `wait`/`notifyAll`; uma `BlockingDeque` por canal |
| Barrel | Threads do RMI, `gateway-heartbeat`, `barrel-saver` | Métodos do índice `synchronized`; a leitura do snapshot recebido é feita fora do lock |
| Gateway | Threads do RMI, `barrel-expiry`, `pending-urls`, `gateway-saver`, `stats-notifier` | `ConcurrentHashMap`, `CopyOnWriteArrayList`, `LinkedBlockingDeque`; `getStats`/`save` `synchronized` |
| Client | Menu (thread principal), `stats-monitor`, threads do RMI (callback) | `volatile` para a referência da Gateway e o estado do ecrã; subscrições sob lock |

**Porque é preciso:** o RMI executa cada chamada remota numa thread do seu pool. Vários Downloaders
chamam `addUrls` ao mesmo tempo; a Gateway pesquisa num Barrel enquanto os Downloaders lhe entregam
páginas; os callbacks das estatísticas chegam ao Client enquanto o menu espera pelo teclado.

---

## 9. Conceitos das aulas

| Conceito | Onde |
|---|---|
| Interfaces `Remote`, `RemoteException`, stubs, `exportObject` (t06) | Todas as interfaces remotas; os cinco programas exportam objetos |
| RMI registry: `createRegistry`, `rebind`, `lookup` (t06) | URLQueue e Gateway criam um registry cada; os outros fazem `lookup` |
| Passagem por valor vs. por referência (t06) | `PageData`, `SearchResult`, `Stats`, `IndexMessage` por valor; stubs por referência |
| **Callbacks** (t06) | Fila → Downloader (`download`); Gateway → Client (`statsUpdated`) |
| Leases (t06, DGC) | Registo dos Barrels na Gateway com prazo, renovado por heartbeat |
| Semântica *at-most-once* e idempotência (t05) | `storePage`, `indexUrl`, `search`: repetir é seguro; "Pedido aceite" no Client |
| Identificadores de pedido e filtragem de duplicados (t05) | `(senderId, seq)` e `lastSeq` |
| Modelo de falhas: crash e omissão (t02) | Secção 7 |
| Reliable multicast: integridade, validade, acordo, FIFO (t08) | Secção 6.1 |
| Deteção de omissões por números de sequência (t08) | `checkMissing` (secção 6.2) |
| Threads, `synchronized`, `wait`/`notifyAll` em ciclo (t04) | Barrel, `ReliableMulticast.multicast` |
| `java.util.concurrent`: `BlockingQueue`, `ConcurrentHashMap`, `CopyOnWriteArrayList` (t04) | Fila, Gateway, multicast |
| Persistência e escrita atómica | Barrel e Gateway (`.tmp` + `ATOMIC_MOVE`) |

---

## 10. Checklist

| Requisito | Pts | Como é cumprido |
|---|---|---|
| Indexar novo URL introduzido por utilizador | 8 | Client `1` → `Gateway.indexUrl` → `addUrlPriority` (início da fila) |
| Indexar iterativamente todos os URLs encontrados | 8 | O Downloader devolve os links à fila (`addUrls`) |
| Pesquisar páginas com um conjunto de palavras | 8 | `Barrel.search`: interseção (AND) no índice invertido |
| Páginas ordenadas por ligações recebidas | 8 | Ordenação por número de backlinks |
| Lista de páginas com ligação para uma página | 8 | Client `3` ou `l N` → `getBacklinks` |
| Estatísticas em tempo real | 8 | Callback `StatsListener`, enviadas só quando mudam |
| Resultados de 10 em 10 | 8 | `PAGE_SIZE = 10`; navegação `s`/`a` no Client |
| Informação idêntica nos Barrels (reliable multicast) | 5 | Secção 6 |
| Serviço correto com pelo menos um Barrel e a Gateway | 5 | Failover; Downloaders guardam as mensagens para o Barrel em baixo |
| Barrels recuperam o estado depois de um crash | 5 | Ficheiro + sincronização com um Barrel ativo |
| Balanceamento da carga nas pesquisas | 5 | Sorteio proporcional a 1/tempo médio |
| Downloaders em paralelo | 5 | Vários processos; a fila distribui os URLs pelos livres |
| Gateway recupera de avarias internas | 5 | Failover entre Barrels, URLs pendentes, estado em ficheiro, Barrels e Clients voltam a ligar-se sozinhos |
| Pedidos de indexação respondidos por um só Downloader | 5 | `seen.add` atómico + URLs normalizados |
| Extra: novo Barrel sincroniza-se com os existentes | +5 | Secção 6.2 |
| Configuração em `.properties` sem recompilar | obrig. | `Config` + `googol.properties` |
| Corre distribuído por várias máquinas | obrig. | `rmi.host`, `gateway.host`, `rmi.hostname.local`; registo dos Barrels por método remoto |
| Não apresenta erros/exceções | obrig. | Mensagens `Error: ...` sem stack trace; falhas de download descritas sem nomes de exceções |
| Código legível e documentado | obrig. | Comentários em português em todas as classes e métodos importantes |
| Apagar os dados de teste | defesa | Apagar a pasta `data/` (README) |

---

## 11. Limitações conhecidas

1. **Downloader que morre a meio de um download:** o URL em curso perde-se. A fila só deteta
   Downloaders mortos quando lhes tenta enviar um URL. *Como corrigir:* a fila guardaria o URL em
   curso de cada Downloader e verificaria periodicamente se ele está vivo; se não estivesse, o URL
   voltava à fila.
2. **A fila de URLs está só em memória:** se a URLQueue morrer, perde os URLs por visitar. O enunciado
   deixa o desenho da fila livre. Os Downloaders recuperam sozinhos quando a fila volta, e os pedidos
   dos utilizadores feitos com a fila em baixo ficam guardados na Gateway. *Como corrigir:* gravar a
   fila e o conjunto `seen` em ficheiro, como o Barrel.
3. **`Barrel.save` grava enquanto segura o lock:** com um índice muito grande, as pesquisas esperam
   durante cada gravação. *Como corrigir:* serializar o índice para memória sob o lock (como o
   `getSnapshot`) e escrever o ficheiro fora dele.
4. **Os dois Barrels morrem ao mesmo tempo com `kill -9`:** perde-se o que receberam depois da última
   gravação (no máximo `barrel.save.interval.ms`), porque o ACK é dado antes de gravar.
5. **A Gateway e a fila não são replicadas:** enquanto estão em baixo, o serviço correspondente
   pára, mas recupera sozinho quando reiniciam. O enunciado só pede redundância nos Barrels.
6. **O snapshot copia o índice inteiro:** simples e correto (união), mas com um índice enorme seria
   pesado. Seria possível enviar só o que falta, com um log de mensagens por Barrel.

---

## 12. Perguntas prováveis

### Arquitetura e RMI
- **Porque é que os Barrels se registam na Gateway e não no registry?** Não se pode fazer `bind`
  num registry de outra máquina, e o enunciado sugere que a Gateway receba as referências dos
  Barrels.
- **Porque é que cada servidor tem o seu registry?** Para que a morte da fila não apague o registo
  da Gateway, e vice-versa.
- **Porque é que é preciso `java.rmi.server.hostname`?** O stub leva o IP onde o objeto está à
  escuta; se for `127.0.0.1`, a outra máquina não consegue fazer o callback.
- **Que callbacks usam?** Fila → Downloader (`download`) e Gateway → Client (`statsUpdated`).
- **Porque é que definiram tempos limite no RMI?** Por omissão o RMI espera para sempre pela
  resposta. Uma máquina que desaparece sem fechar as ligações bloqueava a Gateway, sem nunca haver
  `RemoteException` nem failover.

### Fila e Downloaders
- **Porque é que o Downloader cria uma thread em `download()`?** Para a chamada remota retornar logo;
  senão, o dispatcher da fila ficava bloqueado durante o download inteiro.
- **Como garantem que um URL é descarregado por um só Downloader?** `seen.add(url)` num conjunto
  concorrente é atómico: só quem o adiciona primeiro põe o URL na fila, e cada `take()` entrega-o a um
  só Downloader.
- **Porque é que o URL do utilizador vai para o início da fila?** O enunciado pede que seja visitado
  "assim que possível".
- **Porque normalizam os URLs?** `https://uc.pt` e `https://uc.pt/` são a mesma página: sem
  normalização era visitada duas vezes e as ligações recebidas ficavam divididas.
- **O que acontece se a fila reiniciar?** Os Downloaders detetam a falha pelo `ping`, procuram a fila
  no registry e registam-se na nova.
- **Porque é que um 404 não é repetido?** É um erro permanente: repetir não adianta. Só os erros
  temporários (timeout, 5xx) voltam à fila.

### Barrels e pesquisa
- **Como funciona a pesquisa AND?** Interseção dos conjuntos de URLs de cada termo no índice
  invertido.
- **Porque é que os empates são ordenados pelo URL?** Para a ordem ser sempre a mesma: a página 2
  não pode repetir resultados da página 1.
- **Porque é que a gravação usa um ficheiro temporário?** Um crash durante a escrita não pode deixar
  o índice corrompido; o `move` atómico troca o ficheiro de uma só vez.
- **Porque é que `storePage` tem de ser idempotente?** O RMI é *at-most-once*: depois de uma falha,
  quem envia não sabe se o Barrel recebeu, e a retransmissão pode gerar duplicados.

### Replicação
- **Explica o reliable multicast.** Cada mensagem leva `(senderId, seq)`; há uma fila e uma thread
  por Barrel; o retorno de `deliver` é o ACK; com `RemoteException` a mensagem volta ao início da
  fila e é retransmitida; o Barrel ignora `seq` já recebidos. Dá integridade, validade, acordo e
  ordem FIFO por emissor.
- **E se o ACK se perder?** O Downloader retransmite; o Barrel já tem `lastSeq >= seq` e ignora-a.
- **E se um Barrel estiver em baixo durante a indexação?** As mensagens ficam na fila desse Barrel e
  são retransmitidas quando ele volta; além disso, ao arrancar, sincroniza-se com o outro.
- **Porque é que a sincronização não usa um log?** O índice só cresce, por isso juntar dois índices
  é uma união (comutativa e idempotente). Basta copiar o índice de outro Barrel; um log teria de ser
  guardado e limpo.
- **Como sabe um Barrel que lhe faltam mensagens?** Compara o seu vetor `lastSeq` com o dos outros
  Barrels a cada heartbeat; se outro já estava à frente no heartbeat anterior, sincroniza-se.
- **Porque é que um Barrel a sincronizar-se não responde a pesquisas?** O índice ainda está
  incompleto; os resultados podiam faltar. Mas já recebe as mensagens, para não perder nenhuma.

### Gateway, Client e falhas
- **Como funciona o balanceamento?** Sorteio com probabilidade proporcional a 1/tempo médio: o
  Barrel mais rápido recebe mais pesquisas, mas todos continuam a ser usados e medidos.
- **O que acontece se um Barrel falhar a meio de uma pesquisa?** A Gateway apanha a
  `RemoteException`, marca-o como suspeito e faz o mesmo pedido a outro Barrel.
- **Porque é que a Gateway não retira logo um Barrel que falhou?** Os Downloaders usam essa lista:
  se o Barrel saísse, descartavam as mensagens guardadas para ele. Fica suspeito até a lease expirar.
- **Como sabe a Gateway que um Barrel morreu?** Pela lease: se o Barrel não renovar o registo dentro
  do prazo, é retirado.
- **Como funcionam as estatísticas sem polling?** O Client exporta um `StatsListener`; a Gateway faz
  o callback só quando as estatísticas mudam (compara com as últimas enviadas).
- **E se a Gateway reiniciar?** Lê do ficheiro os URLs pendentes e o top 10; os Barrels voltam a
  registar-se pelo heartbeat; os Clients repetem os pedidos e, no ecrã de estatísticas, detetam o
  reinício pelo `ping` e voltam a subscrever.
- **A Gateway não devia guardar dados?** O enunciado diz que "não tem de", não que não pode. O índice
  continua só nos Barrels; a Gateway guarda só o que não se recalcula (pedidos pendentes e contagens).
- **O que é o "Pedido aceite" no Client?** Se a resposta a um `indexUrl` se perdeu e o pedido foi
  repetido, "já conhecido" quer dizer que foi o primeiro pedido que pôs o URL na fila.
- **Porque não fizeram cache?** Era opcional; sem cache, os resultados estão sempre atualizados.

---

## 13. Demonstração na defesa

**Preparação** (checklist: duas máquinas; chegar 15 minutos antes):
1. Compilar nas duas máquinas e configurar o `googol.properties` como no README (`rmi.hostname.local`
   com o IP de cada máquina; `rmi.host` e `gateway.host` com o IP da #1).
2. Confirmar que a firewall deixa passar o `java` nas duas máquinas.
3. Apagar a pasta `data/` nas duas máquinas para começar do zero.

**Arranque:**
- Máquina #1: Gateway → `Barrel Barrel1` → URLQueue → Downloader.
- Máquina #2: `Barrel Barrel2` → Downloader → Client.

**Guião sugerido:**

| # | Ação | O que mostra |
|---|---|---|
| 1 | Client `1`: indexar `https://www.uc.pt` | O URL vai para a fila; os dois Downloaders trabalham em paralelo |
| 2 | Client `4`: estatísticas | Os dois Barrels com o tamanho do índice a crescer; atualizações só quando mudam |
| 3 | Client `2`: pesquisar "universidade coimbra"; `s`, `a` | Pesquisa AND, ordenação por ligações, 10 em 10, título/URL/citação |
| 4 | `l 1` num resultado | Backlinks |
| 5 | Várias pesquisas; voltar ao `4` | Top 10 e tempo médio por Barrel; as pesquisas distribuídas pelos dois |
| 6 | Matar o Barrel2 (Ctrl+C ou fechar o terminal) e pesquisar | Failover: a pesquisa continua com o Barrel1 |
| 7 | Reiniciar o Barrel2 | Sincroniza-se ("Synchronized with Barrel Barrel1") e fica idêntico |
| 8 | Matar a Gateway, pesquisar no Client, reiniciar a Gateway | O Client repete o pedido e conclui-o; o top 10 mantém-se |
| 9 | Apagar `data/Barrel2.ser` com o Barrel2 parado e reiniciá-lo | Barrel "novo" copia o índice do outro (extra) |
| 10 | Mostrar o `googol.properties` | Configuração sem recompilar |
