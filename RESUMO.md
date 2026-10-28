# Googol: resumo do projeto para a defesa

Documento de estudo, atualizado a cada passo do desenvolvimento. Explica **o que** cada parte
faz, **porquê** foi feita assim e **que conceitos das aulas** usa. Na defesa individual é
preciso saber explicar e alterar qualquer funcionalidade, por isso este resumo cobre tudo o que
já está implementado.

---

## 1. O que é o Googol

Um motor de pesquisa Web distribuído (enunciado `sd_projeto_meta1_2027.pdf`):

1. **Indexar** um URL introduzido pelo utilizador.
2. **Indexar iterativamente** todos os links encontrados (web crawler).
3. **Pesquisar** páginas que contêm **todos** os termos (título, URL, citação).
4. **Ordenar** por número de ligações recebidas de outras páginas.
5. **Consultar** as páginas com ligação para um URL (backlinks).
6. **Estatísticas em tempo real** (top 10 pesquisas, Barrels ativos e tamanho do índice,
   tempo médio de resposta por Barrel em décimas de segundo), enviadas só quando mudam.
7. Resultados **de 10 em 10**.

---

## 2. Arquitetura

```
                 addUrls / downloaderReady (RMI)
   ┌───────────┐ ─────────────────────────────▶ ┌──────────┐
   │Downloader │                                │ URLQueue │  (cria o RMI registry)
   │  (vários) │ ◀───────────────────────────── └──────────┘
   └─────┬─────┘      download(url) (callback)
         │ storePage(PageData) — reliable multicast (passo 4)
         ▼
   ┌──────────┐  ┌──────────┐
   │ Barrel 1 │  │ Barrel 2 │   réplicas idênticas, índice persistente em data/<nome>.ser
   └────▲─────┘  └────▲─────┘
        │ search / getBacklinks / tamanhos (RMI)
   ┌────┴─────────────┴────┐
   │        Gateway        │   (passo 5) única entrada dos clientes; Barrels registam-se nela
   └───────────▲───────────┘
               │ RMI (+ callback das estatísticas)
          ┌────┴───┐
          │ Client │   (passo 6)
          └────────┘
```

### Estado de cada componente

| Componente | Pacote | Estado |
|---|---|---|
| Configuração | `common.Config` | ✅ Passo 1 |
| Fila de URLs | `queue` | ✅ Passo 2 |
| Downloader | `downloader` | ✅ Passo 2 (ainda não envia para os Barrels) |
| Barrel | `barrel` | ✅ Passo 3 (ainda não ligado) |
| Reliable multicast | `downloader` / `barrel` | ⏳ Passo 4 |
| Gateway | `gateway` | ⏳ Passo 4 (registo de Barrels) e Passo 5 (pesquisa, estatísticas) |
| Client | `client` | ⏳ Passo 6 |
| Sincronização de Barrels | `barrel` | ⏳ Passo 7 |

### Decisão: onde se registam os Barrels?
Um programa **não pode fazer `bind` num registry de outra máquina** (o `rebind` remoto é
recusado por segurança). Por isso, os Barrels da máquina #2 não se podem registar no registry
da máquina #1. Solução escolhida: **os Barrels registam-se na Gateway por um método remoto**
(`registerBarrel`), enviando a sua referência remota (stub). Os Downloaders pedem à Gateway a
lista de Barrels. É a solução sugerida no enunciado ("a Gateway receber uma referência remota
para os Barrels"). A alternativa (registar na URLQueue) obrigaria a Gateway a consultar a Queue.

---

## 3. Componentes em detalhe

### 3.1 `common.Config`: configuração (Passo 1)
- Lê `googol.properties` (ou o ficheiro em `args[0]`) com `java.util.Properties`, em UTF-8.
- `get`, `getInt` e `getList` (valores separados por vírgulas); uma chave em falta dá
  `IllegalArgumentException("Missing property: ...")`, uma mensagem clara em vez de `NullPointerException`.
- `applyLocalHostname()` define `java.rmi.server.hostname`, ou seja, **o IP que fica escrito dentro
  dos stubs que esta máquina exporta**. Sem isto, um stub criado na máquina #2 podia dizer
  "liga-me em 127.0.0.1" e a máquina #1 ligava a si própria. É essencial para os **callbacks
  entre máquinas**.
- Checklist: "Configuração em .properties não requer recompilação" (-5 se faltar) e
  "corre distribuído por várias máquinas" (-10).

### 3.2 `common.PageData`: página processada (Passo 2)
- URL, título, citação, palavras (`HashSet`) e links (`HashSet`).
- `implements Serializable` com `serialVersionUID` fixo: **objetos não remotos passam por valor**
  em RMI (são serializados, enviados e reconstruídos do outro lado, t06). O `HashSet` é
  serializável; a interface `Set` não garante isso (o compilador avisa).

### 3.3 `common.Tokenizer` (Passo 3)
- `words(text)`: minúsculas, separa por tudo o que não é letra nem dígito (`[^\p{L}\p{N}]+`),
  mantém acentos e remove repetidos. Exemplo: "UC-2026" dá `uc` e `2026`.
- Usado **pelo Downloader (texto) e pelo Barrel (pesquisa)**: se normalizassem de forma
  diferente, uma pesquisa por "Coimbra" podia não encontrar "coimbra".

### 3.4 `common.SearchResult` (Passo 3)
- URL, título, citação e número de ligações recebidas. `Serializable` (passa por valor do Barrel
  para a Gateway e para o Client).

### 3.5 `queue.URLQueue`: fila de URLs (Passo 2)
**Interface remota `URLQueueInterface`:** `addUrl`, `addUrls`, `retryUrl` e `downloaderReady`.

**Funcionamento (modelo "push" com callbacks):**
1. Ao arrancar, cria o **RMI registry** (`LocateRegistry.createRegistry(rmi.port)`) e faz
   `rebind(queue.name, stub)`.
2. Cada Downloader, quando está livre, chama `downloaderReady(stub_do_downloader)`.
3. Uma thread **dispatcher** repete: `availableDownloaders.take()`, depois `urls.take()`, depois
   `downloader.download(url)`. Esta última chamada é um **callback**: é a fila que invoca um
   método remoto no Downloader.

**Estruturas (todas de `java.util.concurrent`, t04):**
| Estrutura | Para quê |
|---|---|
| `LinkedBlockingQueue<String> urls` | URLs por visitar; `take()` bloqueia se estiver vazia (sem espera ativa) |
| `LinkedBlockingQueue<DownloaderInterface> availableDownloaders` | Downloaders livres |
| `Set<String> seen` (`ConcurrentHashMap.newKeySet()`) | URLs que já entraram na fila. `seen.add(url)` é atómico: só o primeiro a adicionar põe o URL na fila, o que garante que **cada URL é indexado por um só Downloader** |
| `ConcurrentHashMap<String,Integer> retries` | Tentativas por URL; `merge` incrementa de forma atómica |

**Porque é que são precisas estruturas thread-safe?** O RMI executa cada chamada remota numa
thread do seu pool. Vários Downloaders chamam `addUrls` ao mesmo tempo, enquanto o dispatcher
faz `take()`.

**Falhas tratadas:**
- Download falhado: o Downloader chama `retryUrl`, e o URL volta à fila até
  `queue.max.retries` (2) vezes; depois é descartado (evita ciclos infinitos com links mortos).
- Downloader morto: `download()` lança `RemoteException`. O URL volta à fila e o downloader
  **não volta à lista de livres** (fica descartado).

### 3.6 `downloader.Downloader`: web crawler (Passo 2)
- `main`: lê a configuração, faz `lookup` da fila, **exporta-se** com
  `UnicastRemoteObject.exportObject(this, 0)` (porto 0 = qualquer porto livre) e chama
  `downloaderReady(self)`.
- `download(url)` (chamado pela fila) **cria uma thread e retorna logo**: assim a chamada remota
  do dispatcher não fica bloqueada durante o download.
- `parsePage`: `Jsoup.connect(url).get()` seguido de `extract(url, doc)`, que obtém o título, a
  citação (os primeiros 150 caracteres do texto), as palavras (`Tokenizer`) e os links (`absUrl`,
  só `http`/`https`, sem `#...`).
- Envia todos os links de uma vez com `addUrls` (**uma** chamada remota por página em vez de uma por link).
- No `finally` faz a pausa (`downloader.delay.ms`) e chama sempre `downloaderReady`: mesmo depois de um
  erro o Downloader volta a ficar disponível (no projeto de referência do ano passado isto
  falhava e o downloader ficava parado).
- **Paralelismo:** cada processo trata um URL de cada vez; o paralelismo vem de correr vários
  processos Downloader (em uma ou várias máquinas).

### 3.7 `barrel.Barrel`: Index Storage Barrel (Passo 3)
**Interface remota `BarrelInterface`:** `storePage(PageData)`, `search(query, page)`,
`getBacklinks(url)`, `getWordCount()`, `getPageCount()` e a constante `PAGE_SIZE = 10`.

**Dados (classe interna `IndexData`, `Serializable`):**
| Mapa | Conteúdo |
|---|---|
| `index` | palavra → URLs onde aparece (**índice invertido**) |
| `pages` | URL → {título, citação} |
| `backlinks` | URL → URLs das páginas que ligam para ele |

**Pesquisa (`search`):**
1. Divide a pesquisa em termos com o `Tokenizer`.
2. **Interseção** (`retainAll`) dos conjuntos de URLs de cada termo, ou seja, só páginas com **todos** os termos (AND).
3. Ordena por número de backlinks (decrescente); em caso de empate, pelo URL (ordem estável
   entre pedidos, necessária para a paginação não repetir nem saltar resultados).
4. Devolve só a página pedida: `[page*10, page*10+10)`.

**`storePage` é idempotente:** guardar a mesma página duas vezes não muda nada (usa conjuntos).
Isto é importante para o reliable multicast, porque as retransmissões podem entregar
duplicados.

**Concorrência:** todos os métodos que tocam no índice são `synchronized` (monitor do objeto,
t04), porque as pesquisas da Gateway e as inserções dos Downloaders chegam em paralelo.

**Persistência (recuperação depois de um crash):**
- Grava o `IndexData` inteiro com `ObjectOutputStream` em `data/<nome>.ser`.
- Grava **periodicamente** (`barrel.save.interval.ms`, só se houver alterações, através de uma flag `dirty`) e
  num **shutdown hook** (Ctrl+C).
- **Escrita atómica:** escreve primeiro em `<nome>.ser.tmp` e depois faz `Files.move(..., ATOMIC_MOVE)`.
  Um crash a meio da escrita nunca corrompe o ficheiro principal.
- Ao arrancar, carrega o ficheiro; se estiver corrompido, arranca vazio (sem rebentar).
- Arranque: `java barrel.Barrel <nome> [config]`. O nome vem nos argumentos para as duas máquinas
  poderem usar o mesmo `.properties`.

---

## 4. Conceitos das aulas usados

| Conceito | Onde |
|---|---|
| Interface `Remote`, métodos com `throws RemoteException` (t06) | `URLQueueInterface`, `DownloaderInterface`, `BarrelInterface` |
| `UnicastRemoteObject.exportObject` e stubs (t06) | Queue, Downloader, Barrel |
| RMI registry: `createRegistry`, `rebind`, `lookup` (t06) | Queue cria; Downloader faz `lookup` |
| Passagem por valor (`Serializable`) vs por referência (`Remote`) (t06) | `PageData`/`SearchResult` por valor; stubs dos Downloaders e Barrels por referência |
| **Callbacks** (t06) | A fila chama `download(url)` no Downloader |
| Semântica *at-most-once* do RMI (t05) | Uma `RemoteException` não diz se o método chegou a executar; por isso `storePage` é idempotente (repetir é seguro) |
| Threads, `synchronized`, `java.util.concurrent` (t04) | Dispatcher, `BlockingQueue`, `ConcurrentHashMap`, Barrel `synchronized` |
| Modelo de falhas: *crash*, omissão (t02) | Downloader morto, URL inválido, Barrel morto e reiniciado |
| Reliable multicast: integridade, validade, acordo (t08) | ⏳ Passo 4 |

---

## 5. Requisitos da checklist: o que já está coberto

| Requisito | Pts | Estado | Como |
|---|---|---|---|
| Indexar novo URL introduzido pelo utilizador | 8 | ⏳ | `addUrl` existe; falta o Client e a Gateway |
| Indexar iterativa/recursivamente | 8 | ✅ | Downloader devolve links à fila |
| Pesquisar páginas com um conjunto de palavras | 8 | 🟡 | `Barrel.search` (AND) feito; falta ligar à Gateway e ao Client |
| Ordenar por ligações recebidas | 8 | 🟡 | `Barrel.search` |
| Páginas com ligação para uma página | 8 | 🟡 | `Barrel.getBacklinks` |
| Estatísticas em tempo real | 8 | ⏳ | Passo 5/6 |
| Resultados de 10 em 10 | 8 | 🟡 | `PAGE_SIZE = 10` |
| Barrels com informação idêntica (reliable multicast) | 5 | ⏳ | Passo 4 |
| Funciona com ≥1 Barrel + Gateway | 5 | ⏳ | Passo 5 |
| Barrels recuperam o estado depois de um crash | 5 | 🟡 | Persistência em ficheiro; o Passo 7 cobre o que falta |
| Balanceamento de carga nas pesquisas | 5 | ⏳ | Passo 5 |
| Downloaders em paralelo | 5 | ✅ | Vários processos + fila com downloaders livres |
| Gateway recupera de avarias internas | 5 | ⏳ | Passo 5 |
| Cada URL indexado por um só Downloader | 5 | ✅ | Conjunto `seen` atómico |
| `.properties` sem recompilar | obrig. | ✅ | `Config` |
| Corre em várias máquinas | obrig. | ✅ | `rmi.host` + `java.rmi.server.hostname` |

---

## 6. Limitações conhecidas (e respostas para a defesa)

1. **Downloader que morre a meio de um download:** o URL em curso perde-se, porque a fila só deteta
   downloaders mortos quando lhes tenta enviar um URL. *Correção planeada:* a fila guarda
   o URL em curso de cada downloader e verifica periodicamente se o downloader está vivo; se não estiver, o URL volta à fila.
2. **Barrel morto com `kill -9`:** perde o que recebeu depois da última gravação (no máximo
   `barrel.save.interval.ms`). *Correção planeada:* Passo 7, em que o Barrel, ao arrancar, copia o que lhe falta
   de um Barrel ativo.
3. **A fila de URLs está em memória:** se a URLQueue morrer, perde os URLs pendentes. (O enunciado
   deixa o desenho da fila livre.)

---

## 7. Perguntas prováveis na defesa

- **Porque é que o Downloader cria uma thread em `download()`?** Para a chamada remota retornar logo;
  caso contrário, o dispatcher da fila ficava bloqueado durante todo o download e não
  distribuía URLs aos outros Downloaders.
- **Como garantes que um URL não é descarregado duas vezes?** `seen.add(url)` num conjunto concorrente
  é atómico; só quem o adiciona primeiro coloca o URL na fila, e cada `take()` entrega o URL a um só
  Downloader.
- **O que acontece se um Downloader avariar?** A próxima chamada `download()` lança
  `RemoteException`; o URL volta à fila e esse Downloader deixa de receber trabalho.
- **Porque é que é preciso `java.rmi.server.hostname`?** O stub leva o IP onde o objeto
  está à escuta; se for `127.0.0.1`, a outra máquina não consegue fazer o callback.
- **Porque é que `storePage` tem de ser idempotente?** O RMI é *at-most-once* e, depois de uma falha, quem
  envia não sabe se o Barrel recebeu a mensagem; a retransmissão pode gerar duplicados.
- **Porque é que a gravação usa um ficheiro temporário?** Para que um crash durante a escrita não deixe o
  índice corrompido (o `move` atómico troca o ficheiro de uma só vez).
- **Como funciona a pesquisa AND?** É a interseção dos conjuntos de URLs de cada termo no índice
  invertido.
- **Porque é que os resultados com o mesmo número de links são ordenados pelo URL?** Para a ordem ser
  sempre a mesma: a página 2 de uma pesquisa não pode repetir resultados da página 1.

---

## 8. Testes realizados (tabela para o relatório)

Os testes automáticos ficam fora do repositório (`tests/`), mas os resultados são os de execuções reais.

| # | Teste | Resultado |
|---|---|---|
| 1 | Configuração: valores, listas, UTF-8 e chave em falta | PASS |
| 2 | Extração: título, citação de 150 caracteres, palavras normalizadas, links sem `#` e só http/https | PASS |
| 3 | Fila: URL repetido é entregue uma só vez | PASS |
| 4 | Fila: no máximo 2 novas tentativas para um URL que falha | PASS |
| 5 | Indexação iterativa: 20 páginas visitadas a partir de uma | PASS |
| 6 | Dois Downloaders em paralelo recebem URLs diferentes | PASS |
| 7 | Página 404 tentada 3 vezes e descartada | PASS |
| 8 | Downloader morto: a fila deteta a falha, devolve o URL e o outro Downloader continua | PASS |
| 9 | 30 URLs, cada um descarregado uma única vez | PASS |
| 10 | Barrel: pesquisa só devolve páginas com todos os termos | PASS |
| 11 | Barrel: ordenação por ligações recebidas | PASS |
| 12 | Barrel: backlinks | PASS |
| 13 | Barrel: guardar a mesma página duas vezes não altera o índice | PASS |
| 14 | Barrel: paginação 10/10/5/0 | PASS |
| 15 | Barrel: índice recuperado depois de reiniciar; ficheiro corrompido não impede o arranque | PASS |
| 16 | Processo Barrel morto (`destroyForcibly`) e reiniciado recupera o índice | PASS |
| 17 | Logs sem exceções inesperadas | PASS |

---

## 9. Histórico de passos

| Passo | Conteúdo | Merge |
|---|---|---|
| 1 | `googol.properties` + `Config` | `2127a4e` |
| 2 | Fila com vários Downloaders, URLs únicos, novas tentativas, `PageData` | `f7bfb94` |
| — | Repositório só com ficheiros da entrega | `4177eea` |
| 3 | Index Storage Barrel (índice, pesquisa AND, ranking, backlinks, persistência) | `ed74d86` |
| — | README e este resumo | (este commit) |
