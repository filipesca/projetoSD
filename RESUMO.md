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
                 addUrls / downloaderReady / ping (RMI)
   ┌───────────┐ ─────────────────────────────────▶ ┌──────────┐
   │Downloader │                                    │ URLQueue │  (registry próprio: rmi.port)
   │  (vários) │ ◀───────────────────────────────── └──────────┘
   └─────┬─────┘      download(url) (callback)
         │  deliver(IndexMessage) — reliable multicast (fila + thread por Barrel, ACK, retransmissão)
         │  getBarrels() à Gateway, periodicamente (lista de Barrels ativos)
         ▼
   ┌──────────┐  ┌──────────┐
   │ Barrel 1 │  │ Barrel 2 │   réplicas idênticas, índice persistente em data/<nome>.ser
   └────▲─────┘  └────▲─────┘
        │ registerBarrel (heartbeat) ▲   search / getBacklinks / tamanhos (passo 5)
   ┌────┴─────────────┴────┐
   │        Gateway        │   registry próprio: gateway.port; lista de Barrels com "lease"
   └───────────▲───────────┘
               │ RMI (+ callback das estatísticas)
          ┌────┴───┐
          │ Client │   menu de consola; failover para a Gateway; StatsListener (callback)
          └────────┘
```

### Estado de cada componente

| Componente | Pacote | Estado |
|---|---|---|
| Configuração | `common.Config` | ✅ Passo 1 |
| Fila de URLs | `queue` | ✅ Passo 2 |
| Downloader | `downloader` | ✅ Passo 2 |
| Barrel | `barrel` | ✅ Passo 3 |
| Reliable multicast | `downloader.ReliableMulticast` / `Barrel.deliver` | ✅ Passo 4 |
| Gateway | `gateway` | ✅ Passo 4 (registo de Barrels) e Passo 5 (pesquisa, balanceamento, failover, indexação, estatísticas) |
| Client | `client` | ✅ Passo 6 |
| Sincronização de Barrels | `barrel` | ⏳ Passo 7 |

### Decisão: onde se registam os Barrels?
Um programa **não pode fazer `bind` num registry de outra máquina** (o `rebind` remoto é
recusado por segurança). Por isso, os Barrels da máquina #2 não se podem registar no registry
da máquina #1. Solução escolhida: **os Barrels registam-se na Gateway por um método remoto**
(`registerBarrel`), enviando a sua referência remota (stub). Os Downloaders pedem à Gateway a
lista de Barrels. É a solução sugerida no enunciado ("a Gateway receber uma referência remota
para os Barrels"). A alternativa (registar na URLQueue) obrigaria a Gateway a consultar a Queue.

### Decisão: um registry por servidor
A Gateway e a URLQueue criam **cada uma o seu registry** (`gateway.port` e `rmi.port`). Se
partilhassem o registry criado por um deles, a morte desse processo apagava também o registo do
outro. Assim, cada componente pode avariar e reiniciar sem afetar os restantes.

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

**Receção por multicast (`deliver`, Passo 4):**
- Recebe `IndexMessage(senderId, seq, page)`. Guarda em `lastSeq` o último `seq` entregue de cada
  Downloader e **ignora mensagens com `seq <= lastSeq`** (retransmissões já recebidas). É a
  propriedade de **integridade** do reliable multicast: cada mensagem é entregue uma só vez.
- `lastSeq` é gravado com o índice, por isso o filtro continua a funcionar depois de reiniciar.
- O retorno normal de `deliver` é o **ACK** para o Downloader.

**Registo na Gateway (heartbeat, Passo 4):** uma thread chama `gateway.registerBarrel(nome, stub)`
a cada `barrel.heartbeat.ms`. Se a Gateway estiver em baixo, continua a tentar ("Gateway not
available"); quando a Gateway reinicia, o Barrel volta a aparecer na lista sozinho.

### 3.8 `downloader.ReliableMulticast`: reliable multicast sobre RMI (Passo 4)
**Objetivo (t08):** os Barrels têm de ter **informação idêntica** mesmo com **avarias de omissão**
(mensagens ou respostas perdidas, Barrel temporariamente em baixo).

**Algoritmo (ACK + retransmissão, FIFO por emissor):**
1. Cada Downloader tem um `senderId` (UUID gerado no arranque) e numera as mensagens com `seq`
   crescente: `IndexMessage(senderId, seq, page)`.
2. `multicast(page)` coloca a mensagem na **fila de cada Barrel** (um `Channel` por Barrel, com a sua
   `LinkedBlockingDeque` e a sua thread). Os Barrels são servidos **em paralelo**: um Barrel lento ou
   avariado não atrasa os outros.
3. A thread do canal envia as mensagens **por ordem**: `stub.deliver(m)`. Retorno normal = ACK, e
   a mensagem sai da fila.
4. `RemoteException` (omissão/avaria): a mensagem volta ao **início** da fila (mantém a ordem) e é
   **retransmitida** a cada `multicast.retry.ms` até ao ACK.
5. O Barrel filtra repetidas por `(senderId, seq)`, o que trata o caso do **ACK perdido**: o Barrel
   processou a mensagem, mas a resposta não chegou; a retransmissão é ignorada.

**Propriedades (t08):**
| Propriedade | Como é garantida |
|---|---|
| Integridade (entregue no máximo uma vez) | Filtro `lastSeq` no Barrel |
| Validade (o emissor entrega o que envia) | A mensagem fica na fila de cada Barrel até ao ACK |
| Acordo (se um Barrel correto entrega, todos entregam) | Retransmissão até ao ACK para todos os Barrels ativos |
| Ordem FIFO por emissor | Uma fila e uma thread por Barrel; só avança depois do ACK |

**Lista de Barrels:** o Downloader pede `getBarrels()` à Gateway a cada `downloader.reconnect.ms`
(thread própria, independente da fila):
- Barrel novo: é criado um canal (recebe as mensagens a partir daí; o que já existia vem da
  sincronização do Passo 7).
- Barrel reiniciado (novo stub): o canal passa a usar o novo stub e as mensagens pendentes são
  entregues, por isso o Barrel **recupera o que perdeu enquanto esteve em baixo**.
- Barrel que **não está na lista e cujas entregas falham** (retirado pela Gateway: sem heartbeat
  há mais de `gateway.barrel.timeout.ms`): o canal é removido e as mensagens pendentes são
  descartadas (evita memória ilimitada); o Barrel recupera o estado ao arrancar (Passo 7).
- Barrel que não está na lista **mas responde** (ex.: a Gateway acabou de reiniciar e o Barrel ainda
  não renovou o registo): **mantém-se**. Encontrámos este caso nos testes: sem esta regra, um
  reinício da Gateway fazia o Downloader deixar de enviar para um Barrel ativo.

**Sem Barrels:** `multicast()` espera com `wait()` em ciclo (t04) até `updateBarrels` fazer
`notifyAll()`. A página não se perde; o Downloader fica parado até haver um Barrel.

**A Gateway não está no caminho dos dados:** se a Gateway avariar, os Downloaders continuam a
enviar para a última lista conhecida.

**Porque não R-multicast (t08)?** No R-multicast, cada Barrel reenvia a mensagem aos outros na
primeira receção, o que garante acordo mesmo que o emissor morra a meio. Escolhemos ACK +
retransmissão pelo emissor porque é mais simples e cobre as avarias de omissão pedidas no
enunciado. O caso "Downloader morre com mensagens por entregar a um Barrel em baixo" fica coberto
pela sincronização entre Barrels do Passo 7.

### 3.9 `gateway.Gateway` (Passos 4 e 5)
**Interface remota `GatewayInterface`:**
| Para quem | Métodos |
|---|---|
| Barrels | `registerBarrel(nome, stub, páginas, palavras)` (heartbeat) |
| Downloaders | `getBarrels()` (lista para o multicast) |
| Clientes | `indexUrl(url)`, `search(query, page)`, `getBacklinks(url)`, `getStats()`, `subscribeStats(listener)`, `unsubscribeStats(listener)`, `ping()` (passo 6) |

A Gateway **não guarda dados do índice**: responde aos clientes pedindo aos Barrels.

**Registo dos Barrels (lease, t06):**
- Guarda `nome → (stub, último heartbeat, páginas, palavras)` num `ConcurrentHashMap`.
- O registo só é válido durante `gateway.barrel.timeout.ms`; o Barrel renova-o com o heartbeat,
  que leva também o **tamanho atual do índice** (usado nas estatísticas, sem a Gateway ter de
  perguntar). Uma thread retira os Barrels cujo prazo expirou. `remove(chave, valor)` garante que
  não remove um registo renovado entretanto.
- Cria o **seu próprio registry** (`gateway.port`) e faz `rebind(gateway.name)`.

**Pesquisa com balanceamento por desempenho (decisão do grupo):**
- A Gateway mede o tempo de resposta de cada pesquisa, por Barrel (`System.nanoTime`).
- O Barrel é **sorteado com probabilidade proporcional a 1/tempo médio**: um Barrel duas vezes mais
  rápido recebe o dobro das pesquisas. Se escolhesse sempre o mais rápido, com tempos parecidos um
  Barrel recebia tudo e o outro nunca era usado nem medido. Um Barrel sem medições recebe o peso do
  mais rápido, para começar a ser medido.
- **Sem cache** (decisão do grupo): cada pesquisa vai a um Barrel, por isso os resultados estão
  sempre atualizados e todas as pesquisas contam para o tempo médio.

**Failover (enunciado 5.1: "a Gateway deverá recuperar fazendo o mesmo pedido a outro Barrel"):**
- `callBarrels` tenta os Barrels pela ordem escolhida. Com `RemoteException`, marca o Barrel como
  **suspeito** e repete o **mesmo pedido** no seguinte. O cliente não vê o erro.
- Um Barrel suspeito deixa de receber pesquisas e de contar como ativo nas estatísticas, mas
  **continua na lista dos Downloaders** até a lease expirar. Assim os Downloaders continuam a
  guardar as mensagens para ele (se a Gateway o retirasse de imediato, os Downloaders descartavam-nas
  e ele perdia as páginas). Se estiver vivo, o próximo heartbeat limpa a marca.
- Sem nenhum Barrel disponível: `NoBarrelAvailableException` (exceção própria, diferente de
  `RemoteException`: a Gateway está a funcionar, por isso o cliente mostra a mensagem em vez de
  tentar de novo a ligação).

**Indexação de URLs dos utilizadores (`indexUrl`):**
- Normaliza o URL e chama `addUrlPriority` na fila (início da fila).
- Devolve `QUEUED`, `ALREADY_KNOWN`, `INVALID` ou `PENDING`.
- **A fila em baixo não faz perder pedidos** (enunciado: "as introduções de URLs para indexação não
  se percam, realizando retries"): o URL vai para `pendingUrls` e uma thread reenvia-o, por ordem,
  a cada `gateway.queue.retry.ms`, até a fila o aceitar.

**Estatísticas em tempo real (callbacks, sem polling):**
- `Stats` (record): top 10 de pesquisas e, para cada Barrel ativo, páginas, palavras, número de
  pesquisas e **tempo médio em décimas de segundo** (1 décima = 100 ms).
- Top 10: conta só a **primeira página** de cada pesquisa (avançar nos resultados não é uma nova
  pesquisa), com os termos normalizados e ordenados ("Coimbra universidade" = "universidade coimbra").
- Os clientes subscrevem-se com `subscribeStats(StatsListener)`: o `StatsListener` é um objeto
  remoto **do cliente** e a Gateway chama `statsUpdated(stats)` (**callback**, t06).
- Sempre que algo pode ter mudado (pesquisa, Barrel novo, removido, suspeito, índice com outro
  tamanho), `statsChanged()` calcula as estatísticas e **só as envia se forem diferentes das últimas**
  (`equals` do record). Sem alterações não há envios, mesmo com heartbeats a chegar.
- Os envios são feitos por uma thread própria (`notifier`), para que um cliente lento não atrase as
  pesquisas. Cliente que já não responde (`RemoteException`) é retirado da lista.
- `subscribeStats` é idempotente (`addIfAbsent`, passo 6): um cliente que volta a subscrever depois de
  uma falha de rede passageira não recebe as atualizações a dobrar.
- A lista de clientes é uma `CopyOnWriteArrayList` (t04): é percorrida nos envios enquanto outros
  clientes se subscrevem.

- Erros previsíveis no arranque (porto ocupado, configuração) dão `Error: ...`, como nos restantes programas.

### 3.10 `client.Client` (Passo 6)
O enunciado pede um cliente com uma UI simples que **só invoca métodos remotos** da Gateway: o Client
não fala com mais nenhum componente.

**Menu de consola:** `1` indexar URL (mostra o `IndexResult`), `2` pesquisar, `3` páginas com ligação
para um URL, `4` estatísticas em tempo real, `0` sair. Na pesquisa, cada resultado mostra título, URL
completo, citação e número de ligações recebidas, **de 10 em 10**: `s` seguinte, `a` anterior,
`l N` backlinks do resultado N, `m` menu. Uma página com menos de 10 resultados é a última.

**Failover (enunciado 5.1: "em caso de avaria devem reiniciar e fazer retry"):**
- Todos os pedidos passam por `call(pedido)`. Com `RemoteException` (ou Gateway ainda não registada),
  o Client volta a procurar a Gateway no registry e **repete o mesmo pedido** a cada `client.retry.ms`,
  até `client.retry.attempts` tentativas (decisão do grupo: limitado e configurável). Depois avisa
  "A Gateway não respondeu..." e volta ao menu: nenhuma operação fica a meio sem o utilizador saber.
- Uma referência antiga que falha (Gateway reiniciada) é trocada logo, sem esperar nem contar como
  tentativa: o utilizador não nota o reinício se a nova Gateway já estiver ativa.
- `NoBarrelAvailableException` não é repetida: a Gateway responde, só não há Barrels; mostra a mensagem.
- **At-most-once (t05):** uma `RemoteException` não diz se o pedido chegou a ser executado. Se o
  primeiro `indexUrl` pôs o URL na fila e só a resposta se perdeu, a repetição devolve `ALREADY_KNOWN`;
  por isso, se houve uma falha, o Client mostra "Pedido aceite" em vez de "já indexado". Repetir é
  seguro porque `indexUrl`, `search` e `getBacklinks` são idempotentes.

**Estatísticas em tempo real (callback):**
- O Client implementa `StatsListener` e exporta-se (`UnicastRemoteObject.exportObject`), por isso
  precisa de `rmi.hostname.local` correto na máquina #2 (a Gateway liga-se de volta ao Client).
- Opção 4 (decisão do grupo: ecrã próprio até Enter): `subscribeStats(stub)`. A Gateway envia logo as
  atuais e depois só quando mudam; Enter faz `unsubscribeStats` e volta ao menu.
- **Gateway reiniciada:** a subscrição perde-se com ela e não chegaria mais nenhum callback. Uma thread
  faz `ping()` à Gateway a cada `client.retry.ms` (decisão do grupo). Se falhar, procura a nova
  Gateway e **subscreve-se de novo**. O ping não traz estatísticas, por isso continua a não haver
  refrescamento periódico.
- As subscrições sob o lock do Client e a verificação de `showingStats` em `statsUpdated` evitam que
  uma atualização atrasada apareça depois de sair do ecrã.
- Ao sair (`0` ou fim da entrada), faz `unexportObject` e `System.exit(0)` (termina as threads do RMI).

---

## 4. Conceitos das aulas usados

| Conceito | Onde |
|---|---|
| Interface `Remote`, métodos com `throws RemoteException` (t06) | `URLQueueInterface`, `DownloaderInterface`, `BarrelInterface` |
| `UnicastRemoteObject.exportObject` e stubs (t06) | Queue, Downloader, Barrel, Gateway, Client (para o callback) |
| RMI registry: `createRegistry`, `rebind`, `lookup` (t06) | Queue cria; Downloader faz `lookup` |
| Passagem por valor (`Serializable`) vs por referência (`Remote`) (t06) | `PageData`/`SearchResult` por valor; stubs dos Downloaders e Barrels por referência |
| **Callbacks** (t06) | A fila chama `download(url)` no Downloader; a Gateway chama `statsUpdated(stats)` nos clientes |
| `CopyOnWriteArrayList` (t04) | Lista de clientes subscritos às estatísticas |
| Semântica *at-most-once* do RMI (t05) | Uma `RemoteException` não diz se o método chegou a executar; por isso `storePage` é idempotente (repetir é seguro) |
| Threads, `synchronized`, `java.util.concurrent` (t04) | Dispatcher, `BlockingQueue`, `ConcurrentHashMap`, Barrel `synchronized` |
| `wait`/`notifyAll` em ciclo (t04) | `ReliableMulticast.multicast` espera enquanto não há Barrels |
| Leases (DGC, t06) | Registo dos Barrels na Gateway com prazo, renovado por heartbeat |
| Modelo de falhas: *crash*, omissão (t02) | Downloader morto, URL inválido, Barrel morto e reiniciado, Gateway e fila reiniciadas |
| Reliable multicast: integridade, validade, acordo, FIFO (t08) | `ReliableMulticast` (ACK + retransmissão) e `Barrel.deliver` (filtro de repetidas) |
| Identificadores de pedido e filtragem de duplicados (t05) | `(senderId, seq)` e `lastSeq` |

---

## 5. Requisitos da checklist: o que já está coberto

| Requisito | Pts | Estado | Como |
|---|---|---|---|
| Indexar novo URL introduzido pelo utilizador | 8 | ✅ | Client opção 1 → `Gateway.indexUrl` → `addUrlPriority` (início da fila; pendente se a fila estiver em baixo) |
| Indexar iterativa/recursivamente | 8 | ✅ | Downloader devolve links à fila |
| Pesquisar páginas com um conjunto de palavras | 8 | ✅ | Client opção 2 → `Gateway.search` → `Barrel.search` (AND) |
| Ordenar por ligações recebidas | 8 | ✅ | `Barrel.search`; o Client mostra o número de ligações de cada resultado |
| Páginas com ligação para uma página | 8 | ✅ | Client opção 3, ou `l N` num resultado → `Gateway.getBacklinks` |
| Estatísticas em tempo real | 8 | ✅ | Client opção 4: `StatsListener` (callback), atualizações só quando mudam |
| Resultados de 10 em 10 | 8 | ✅ | `PAGE_SIZE = 10`; Client com `s` seguinte / `a` anterior |
| Barrels com informação idêntica (reliable multicast) | 5 | ✅ | `ReliableMulticast` + `Barrel.deliver`; testado com um Barrel morto e reiniciado |
| Funciona com ≥1 Barrel + Gateway | 5 | ✅ | Indexação e pesquisa continuam só com 1 Barrel (testado com B2 em baixo) |
| Barrels recuperam o estado depois de um crash | 5 | 🟡 | Persistência em ficheiro; o Passo 7 cobre o que falta |
| Balanceamento de carga nas pesquisas | 5 | ✅ | Por desempenho: probabilidade proporcional a 1/tempo médio |
| Downloaders em paralelo | 5 | ✅ | Vários processos + fila com downloaders livres |
| Gateway recupera de avarias internas | 5 | 🟡 | Failover entre Barrels, URLs pendentes com a fila em baixo, Barrels voltam a registar-se e o Client repete o pedido e volta a subscrever as estatísticas se a Gateway reiniciar; falta gravar o estado da Gateway (limitação 7) |
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
   `barrel.save.interval.ms`). O multicast não cobre este caso porque o ACK é dado antes de o
   Barrel gravar em disco, e o Downloader já descartou essas mensagens. *Correção planeada:*
   Passo 7, em que o Barrel, ao arrancar, copia o que lhe falta de um Barrel ativo.
3. **Downloader morre com mensagens pendentes para um Barrel em baixo**, ou um Barrel é retirado da
   lista pela Gateway (sem heartbeat durante `gateway.barrel.timeout.ms`): esse Barrel fica sem
   essas páginas. *Correção planeada:* Passo 7.
4. **A fila de URLs está em memória:** se a URLQueue morrer, perde os URLs pendentes (o enunciado
   deixa o desenho da fila livre). Os Downloaders, esses, recuperam sozinhos: detetam a falha pelo
   `ping`, esperam que a fila volte e voltam a registar-se.
5. **Erros permanentes também são tentados de novo** (ex.: 404, PDF): 2 tentativas inúteis por
   URL. Melhoria possível: só repetir erros temporários (timeout, falha de ligação, 5xx).
6. **`Barrel.save` grava enquanto segura o lock:** com um índice muito grande, as pesquisas esperam
   durante cada gravação. Melhoria possível: copiar o índice sob o lock e gravar fora dele.
7. **O estado da Gateway está em memória:** se a Gateway morrer, perde os URLs pendentes
   (`pendingUrls`, pedidos feitos com a fila em baixo) e o top 10 das pesquisas. *Correção planeada:*
   gravar esse estado em ficheiro, como o Barrel.
8. **Fila em baixo no fim de um download:** se `addUrls` falhar depois de a página ter sido enviada
   aos Barrels, os links dessa página perdem-se (e o URL é tratado como falhado). *Correção planeada:*
   repetir `addUrls` depois de voltar a ligar-se à fila.

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
- **Explica o teu reliable multicast.** Cada mensagem leva `(senderId, seq)`; há uma fila e uma
  thread por Barrel; o retorno de `deliver` é o ACK; com `RemoteException` a mensagem volta ao
  início da fila e é retransmitida; o Barrel ignora `seq` já recebidos. Dá integridade, validade,
  acordo (para Barrels ativos) e ordem FIFO por emissor.
- **E se o ACK se perder?** O Downloader retransmite; o Barrel já tem `lastSeq >= seq` e ignora a
  mensagem, por isso a página não é aplicada duas vezes.
- **E se um Barrel estiver em baixo durante a indexação?** As mensagens ficam na fila desse Barrel e
  são retransmitidas a cada `multicast.retry.ms`; quando reinicia (e volta a registar-se), recebe-as
  todas por ordem. Testado: B2 morto, 10 páginas indexadas, B2 reiniciado fica idêntico a B1.
- **Como sabe a Gateway que um Barrel morreu?** Pelo heartbeat: se o Barrel não renovar o registo
  dentro do prazo, a lease expira e é retirado da lista.
- **Porque é que cada servidor tem o seu registry?** Para que a morte da fila não apague o registo
  da Gateway, e vice-versa.
- **Como funciona o balanceamento de carga?** Por desempenho: a Gateway mede o tempo médio de cada
  Barrel e sorteia o Barrel com probabilidade proporcional a 1/tempo médio. O mais rápido recebe
  mais pedidos, mas todos continuam a ser usados (e medidos).
- **O que acontece se um Barrel falhar a meio de uma pesquisa?** A Gateway apanha a
  `RemoteException`, marca-o como suspeito e faz o mesmo pedido ao Barrel seguinte; o cliente recebe
  a resposta normalmente.
- **Porque é que a Gateway não retira logo da lista um Barrel que falhou?** Porque os Downloaders
  usam essa lista: se o Barrel saísse, descartavam as mensagens guardadas para ele e o Barrel perdia
  essas páginas quando reiniciasse. Fica "suspeito" (sem pesquisas) até a lease expirar.
- **Como funcionam as estatísticas em tempo real sem polling?** O cliente exporta um
  `StatsListener` e subscreve-se; a Gateway faz callback `statsUpdated()` sempre que as
  estatísticas mudam (compara com as últimas enviadas). Sem alterações, não envia nada.
- **Como é que a Gateway sabe o tamanho do índice de cada Barrel?** O Barrel envia-o em cada
  heartbeat (`registerBarrel(nome, stub, páginas, palavras)`).
- **E se a fila estiver em baixo quando um utilizador pede para indexar um URL?** A Gateway guarda o
  URL e reenvia-o periodicamente até a fila o aceitar; responde `PENDING` ao cliente.
- **Porque não fizeram cache?** Era opcional; sem cache os resultados estão sempre atualizados e
  todas as pesquisas medem o tempo de resposta dos Barrels.
- **O que acontece se não houver Barrels?** O Downloader espera (`wait`) antes de enviar a página;
  nada se perde.

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
| 24 | Barrel.deliver: mensagem repetida ignorada; outros emissores entregues; filtro sobrevive a reinício | PASS |
| 25 | Multicast: todos os Barrels recebem tudo por ordem; Barrel com omissões recebe tudo por retransmissão | PASS |
| 26 | Multicast: ACK perdido → retransmissão filtrada pelo Barrel (página aplicada uma vez) | PASS |
| 27 | Multicast: sem Barrels espera; Barrel retirado e a falhar é descartado; Barrel ativo fora da lista mantém-se | PASS |
| 28 | Gateway: lease renovada por heartbeat; Barrel sem heartbeat é retirado e volta ao registar-se | PASS |
| 29 | Sistema: 2 Barrels registam-se na Gateway e ficam com índices idênticos (20 páginas) | PASS |
| 30 | Sistema: pesquisa nos Barrels reais (AND, ranking, 10 em 10, backlinks) | PASS |
| 31 | Sistema: B2 morto → indexação continua com B1 → B2 reiniciado recebe as 10 páginas retransmitidas e fica idêntico | PASS |
| 32 | Sistema: Gateway reiniciada → Barrels voltam a registar-se, Downloader volta a obter a lista sem retirar Barrels | PASS |
| 33 | Sistema: segunda Gateway no mesmo porto dá mensagem clara | PASS |
| 34 | Gateway: balanceamento por desempenho (os dois Barrels usados; o mais rápido recebe mais) | PASS |
| 35 | Gateway: failover quando um Barrel falha; o suspeito sai das pesquisas e das estatísticas mas continua para o multicast; heartbeat devolve-o | PASS |
| 36 | Gateway: sem Barrels dá `NoBarrelAvailableException`; backlinks com URL normalizado | PASS |
| 37 | Estatísticas: enviadas ao subscrever e quando mudam; heartbeat igual não gera envio; cliente morto deixa de receber | PASS |
| 38 | Estatísticas: top 10 com termos normalizados e só a primeira página; tempo médio em décimas de segundo | PASS |
| 39 | Gateway.indexUrl: inválido, já conhecido, novo; com a fila em baixo fica pendente e é entregue por ordem quando ela volta | PASS |
| 40 | Sistema: pesquisa e backlinks pela Gateway iguais aos dos Barrels; URL do utilizador indexado e pesquisável | PASS |
| 41 | Sistema: estatísticas por callback (top 10, tamanhos, tempo médio), pesquisas distribuídas pelos dois Barrels; sem alterações não há envios | PASS |
| 42 | Sistema: B2 em baixo → pesquisas continuam (failover), B2 deixa de contar como ativo; volta depois de reiniciar | PASS |
| 43 | Sistema: URL pedido com a fila em baixo fica pendente, é reenviado e indexado quando a fila volta | PASS |
| 44 | Gateway: subscrever duas vezes não duplica as atualizações das estatísticas | PASS |
| 45 | Client: pesquisa de 10 em 10 com seguinte/anterior, fim dos resultados, backlinks do resultado N; pesquisa sem resultados | PASS |
| 46 | Client: com a Gateway em baixo repete o mesmo pedido e conclui-o quando ela arranca | PASS |
| 47 | Client: desiste ao fim de N tentativas, avisa e volta ao menu | PASS |
| 48 | Client: indexação com a resposta perdida é repetida e indicada como aceite | PASS |
| 49 | Client: estatísticas por callback; Gateway reiniciada é detetada (ping) e o Client volta a subscrever | PASS |
| 50 | Sistema: Client real (processo, stdin) pesquisa com navegação, backlinks e indexação pela Gateway | PASS |
| 51 | Sistema: Gateway reiniciada com o Client no ecrã de estatísticas → volta a subscrever, continua a pesquisar e sai sem erros | PASS |

---

## 9. Histórico de passos

| Passo | Conteúdo | Merge |
|---|---|---|
| 1 | `googol.properties` + `Config` | `2127a4e` |
| 2 | Fila com vários Downloaders, URLs únicos, novas tentativas, `PageData` | `f7bfb94` |
| — | Repositório só com ficheiros da entrega | `4177eea` |
| 3 | Index Storage Barrel (índice, pesquisa AND, ranking, backlinks, persistência) | `ed74d86` |
| — | README e este resumo | `c4cca52` |
| — | Revisão dos passos 1-3: palavras do título, URLs prioritários, normalização, erros claros, reconexão à fila | `0d4b624` |
| 4 | Reliable multicast + Gateway mínima (registo dos Barrels com heartbeat/lease) | `112375f` |
| 5 | Gateway: pesquisa com balanceamento por desempenho e failover, backlinks, indexação (com URLs pendentes), estatísticas por callback | `e8020cb` |
| 6 | Client: menu de consola, 10 em 10 com navegação, failover (repete o pedido), estatísticas por callback com nova subscrição se a Gateway reiniciar | `passo6-client` |
