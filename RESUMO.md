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
  Um valor não numérico dá `"Property X must be an integer, found: abc"`.
- `exitWithError(msg)`: usado pelos `main()` para **erros previsíveis no arranque** (ficheiro de
  configuração inexistente, valor inválido, porto ocupado). Mostra `Error: ...` numa linha e termina
  com código 1, **sem stack trace** (ponto obrigatório "não apresenta erros/exceções").
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
- Usa `toLowerCase(Locale.ROOT)`: o resultado não depende da língua do sistema (em turco, "I"
  minúsculo é "ı", o que partiria as pesquisas).

### 3.3b `common.UrlNormalizer` (revisão dos passos 1-3)
- `normalize(url)`: esquema e host em minúsculas, remove o porto por omissão (80/443) e o
  fragmento `#...`, e o caminho vazio passa a `/`. O caminho e a query mantêm-se (o servidor
  distingue maiúsculas no caminho). Devolve `null` se não for http/https ou não tiver host.
- Exemplo: `HTTPS://WWW.UC.PT:443#topo` e `https://www.uc.pt` dão ambos `https://www.uc.pt/`.
- Usado pela **fila** (todos os URLs que entram) e pelo **Downloader** (links). Sem isto, a mesma
  página era visitada duas vezes e as ligações recebidas ficavam divididas entre duas "páginas",
  o que estragava a ordenação.

### 3.4 `common.SearchResult` (Passo 3)
- URL, título, citação e número de ligações recebidas. `Serializable` (passa por valor do Barrel
  para a Gateway e para o Client).

### 3.5 `queue.URLQueue`: fila de URLs (Passo 2)
**Interface remota `URLQueueInterface`:** `addUrl`, `addUrls`, `addUrlPriority`, `retryUrl`,
`downloaderReady` e `ping`.

**URLs dos utilizadores (`addUrlPriority`):** o enunciado diz que um URL introduzido pelo
utilizador deve ser visitado "assim que possível". Como o crawler acrescenta milhares de links ao
fim da fila, o URL do utilizador entra pelo **início** (`addFirst`). Se já estava na fila à espera,
é retirado (`remove`, atómico) e passa para o início. Devolve `false` se já foi (ou está a ser)
visitado. A Gateway vai usar este método (Passo 5).

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
| `LinkedBlockingDeque<String> urls` | URLs por visitar; `take()` bloqueia se estiver vazia (sem espera ativa); é uma *deque* para os URLs prioritários entrarem pelo início |
| `LinkedBlockingQueue<DownloaderInterface> availableDownloaders` | Downloaders livres |
| `Set<String> seen` (`ConcurrentHashMap.newKeySet()`) | URLs que já entraram na fila. `seen.add(url)` é atómico: só o primeiro a adicionar põe o URL na fila, o que garante que **cada URL é indexado por um só Downloader** |
| `ConcurrentHashMap<String,Integer> retries` | Tentativas por URL; `merge` incrementa de forma atómica |

**Porque é que são precisas estruturas thread-safe?** O RMI executa cada chamada remota numa
thread do seu pool. Vários Downloaders chamam `addUrls` ao mesmo tempo, enquanto o dispatcher
faz `take()`.

**Falhas tratadas:**
- Download falhado: o Downloader chama `retryUrl`, e o URL volta à fila até
  `queue.max.retries` (2) vezes; depois é descartado (evita ciclos infinitos com links mortos).
- Downloader morto: `download()` lança `RemoteException`. O URL volta ao **início** da fila (não
  perde a vez) e o downloader **não volta à lista de livres** (fica descartado).
- Erro inesperado (`RuntimeException`) no dispatcher: é apanhado, o URL volta ao início e o ciclo
  continua. Sem isto, a thread dispatcher morria sem aviso e a fila deixava de distribuir URLs.
- Arranque com o porto ocupado (outra fila a correr): `ExportException`, que dá a mensagem clara
  `Error: could not use port ...`.

### 3.6 `downloader.Downloader`: web crawler (Passo 2)
- `main`: lê a configuração, **exporta-se** com `UnicastRemoteObject.exportObject(this, 0)`
  (porto 0 = qualquer porto livre), liga-se à fila e inicia a thread de verificação, que faz o
  primeiro `downloaderReady(self)`.
- `download(url)` (chamado pela fila) **cria uma thread e retorna logo**: assim a chamada remota
  do dispatcher não fica bloqueada durante o download.
- `parsePage`: `Jsoup.connect(url).get()` seguido de `extract(url, doc)`, que obtém o título, a
  citação (os primeiros 150 caracteres do texto), as palavras **do título e do texto** (`Tokenizer`;
  o `<title>` está no `<head>`, fora do `body`, e sem isto uma palavra que só aparecesse no
  título não era encontrada) e os links (`absUrl` + `UrlNormalizer`).
- Envia todos os links de uma vez com `addUrls` (**uma** chamada remota por página em vez de uma por link).
- No `finally` faz a pausa (`downloader.delay.ms`) e chama sempre `downloaderReady`: mesmo depois de um
  erro o Downloader volta a ficar disponível (no projeto de referência do ano passado isto
  falhava e o downloader ficava parado).
- **Paralelismo:** cada processo trata um URL de cada vez; o paralelismo vem de correr vários
  processos Downloader (em uma ou várias máquinas).

**Tolerância a falhas da fila (ligação perdida, fila reiniciada):**
- Problema: um Downloader **livre** está só à espera de um callback e nunca chama a fila. Se a fila
  reiniciar, a nova fila não o conhece e ele ficava parado para sempre.
- Solução: uma thread de verificação (`heartbeatLoop`) chama `queue.ping()` a cada
  `downloader.reconnect.ms`. Se falhar, `reconnect()` faz `lookup` no registry até a fila voltar
  (mostra "URLQueue not available ..., retrying") e o Downloader regista-se na nova fila.
- O mesmo `reconnect()` é usado no arranque: **o Downloader pode arrancar antes da fila** e espera
  por ela, em vez de rebentar.
- Para não se registar **duas vezes** (o que lhe daria dois URLs ao mesmo tempo), o estado
  `busy` / `registeredWith` é protegido por um `lock`. A comparação usa `equals()` dos stubs
  (compara o objeto remoto, não a referência Java): depois de uma falha de rede passageira o novo
  stub é igual ao antigo e não há novo registo.
- As mensagens de falha mostram só a causa numa linha (`reason(e)`): a mensagem de uma
  `RemoteException` ocupa várias linhas e parece uma stack trace.

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
| Indexar novo URL introduzido pelo utilizador | 8 | 🟡 | `addUrlPriority` (vai para o início da fila); falta o Client e a Gateway |
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
| Cada URL indexado por um só Downloader | 5 | ✅ | Conjunto `seen` atómico + URLs normalizados |
| `.properties` sem recompilar | obrig. | ✅ | `Config` |
| Corre em várias máquinas | obrig. | ✅ | `rmi.host` + `java.rmi.server.hostname` |
| Não apresenta erros/exceções | obrig. | ✅ (até agora) | Mensagens `Error: ...` sem stack trace; Downloader espera pela fila e volta a ligar-se |

---

## 6. Limitações conhecidas (e respostas para a defesa)

1. **Downloader que morre a meio de um download:** o URL em curso perde-se, porque a fila só deteta
   downloaders mortos quando lhes tenta enviar um URL. *Correção planeada:* a fila guarda
   o URL em curso de cada downloader e verifica periodicamente se o downloader está vivo; se não estiver, o URL volta à fila.
2. **Barrel morto com `kill -9`:** perde o que recebeu depois da última gravação (no máximo
   `barrel.save.interval.ms`). *Correção planeada:* Passo 7, em que o Barrel, ao arrancar, copia o que lhe falta
   de um Barrel ativo.
3. **A fila de URLs está em memória:** se a URLQueue morrer, perde os URLs pendentes (o enunciado
   deixa o desenho da fila livre). Os Downloaders, esses, recuperam sozinhos: detetam a falha pelo
   `ping`, esperam que a fila volte e voltam a registar-se.
4. **Erros permanentes também são tentados de novo** (ex.: 404, PDF): 2 tentativas inúteis por
   URL. Melhoria possível: só repetir erros temporários (timeout, falha de ligação, 5xx).
5. **`Barrel.save` grava enquanto segura o lock:** com um índice muito grande, as pesquisas esperam
   durante cada gravação. Melhoria possível: copiar o índice sob o lock e gravar fora dele.

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
- **O que acontece se a fila reiniciar?** Os Downloaders fazem `ping` periódico; quando falha,
  procuram a fila de novo no registry até ela voltar e registam-se na nova fila.
- **Porque é que o URL do utilizador vai para o início da fila?** O enunciado pede que seja visitado
  "assim que possível"; no fim, ficaria atrás de milhares de links descobertos pelo crawler.
- **Porque é que se normalizam os URLs?** `https://uc.pt` e `https://uc.pt/` são a mesma página;
  sem normalização era visitada duas vezes e as ligações recebidas ficavam divididas.
- **Como evitas que um Downloader se registe duas vezes na fila?** O estado `busy`/`registeredWith`
  é protegido por um `lock` e os stubs são comparados com `equals()` (mesmo objeto remoto).

---

## 8. Testes realizados (tabela para o relatório)

Os testes automáticos ficam fora do repositório (`tests/`), mas os resultados são os de execuções reais.

| # | Teste | Resultado |
|---|---|---|
| 1 | Configuração: valores, listas, UTF-8, chave em falta, valor não numérico, ficheiro inexistente | PASS |
| 2 | Extração: título, citação de 150 caracteres, palavras normalizadas (incluindo as do título), links normalizados e só http/https | PASS |
| 3 | Normalização de URLs equivalentes; rejeita URLs não http/https | PASS |
| 4 | Fila: URL repetido ou equivalente é entregue uma só vez | PASS |
| 5 | Fila: no máximo 2 novas tentativas para um URL que falha | PASS |
| 6 | Fila: URL do utilizador passa à frente; URL já visitado não se repete | PASS |
| 7 | Fila: downloader que falha (RemoteException ou erro inesperado): URL volta ao início e o dispatcher continua | PASS |
| 8 | Indexação iterativa: 20 páginas visitadas a partir de uma | PASS |
| 9 | Dois Downloaders em paralelo recebem URLs diferentes | PASS |
| 10 | Página 404 tentada 3 vezes e descartada | PASS |
| 11 | Downloader morto: a fila deteta a falha, devolve o URL e o outro Downloader continua | PASS |
| 12 | 30 URLs, cada um descarregado uma única vez | PASS |
| 13 | Downloader arrancado antes da fila espera por ela e regista-se | PASS |
| 14 | Fila reiniciada: o Downloader deteta (ping), volta a ligar-se e continua a trabalhar | PASS |
| 15 | Erros no arranque (porto ocupado, configuração inexistente ou inválida): mensagem clara, sem stack trace | PASS |
| 16 | Barrel: pesquisa só devolve páginas com todos os termos | PASS |
| 17 | Barrel: ordenação por ligações recebidas | PASS |
| 18 | Barrel: backlinks | PASS |
| 19 | Barrel: guardar a mesma página duas vezes não altera o índice | PASS |
| 20 | Barrel: paginação 10/10/5/0 | PASS |
| 21 | Barrel: índice recuperado depois de reiniciar; ficheiro corrompido não impede o arranque | PASS |
| 22 | Processo Barrel morto (`destroyForcibly`) e reiniciado recupera o índice | PASS |
| 23 | Logs sem exceções inesperadas | PASS |

---

## 9. Histórico de passos

| Passo | Conteúdo | Merge |
|---|---|---|
| 1 | `googol.properties` + `Config` | `2127a4e` |
| 2 | Fila com vários Downloaders, URLs únicos, novas tentativas, `PageData` | `f7bfb94` |
| — | Repositório só com ficheiros da entrega | `4177eea` |
| 3 | Index Storage Barrel (índice, pesquisa AND, ranking, backlinks, persistência) | `ed74d86` |
| — | README e este resumo | `c4cca52` |
| — | Revisão dos passos 1-3: palavras do título, URLs prioritários, normalização, erros claros, reconexão à fila | `revisao-passos-1-3` |
