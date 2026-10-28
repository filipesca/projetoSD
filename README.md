# Googol: motor de pesquisa distribuído (SD 2026/27, Meta 1)

Motor de pesquisa de páginas Web em **Java RMI**: Downloaders descarregam e analisam páginas
(jsoup), Index Storage Barrels guardam o índice invertido de forma persistente e replicada, e
uma Gateway RMI serve de única porta de entrada para os clientes.

> Explicação completa da arquitetura e das decisões técnicas: [RESUMO.md](RESUMO.md).

## Estado atual

| Componente | Estado |
|---|---|
| `queue.URLQueue`: fila de URLs | ✅ |
| `downloader.Downloader`: crawler (jsoup) | ✅ |
| `barrel.Barrel`: índice invertido persistente | ✅ |
| Reliable multicast Downloader → Barrels | ✅ |
| `gateway.Gateway` | 🟡 registo dos Barrels (pesquisa e estatísticas: próximo passo) |
| `client.Client` | ⏳ |

## Requisitos

- **JDK 17 ou superior** (`java` e `javac` no PATH). Verificar com `java -version`.
- A biblioteca **jsoup** já vem incluída em `lib/jsoup-1.23.2.jar`.

## Estrutura

```
googol.properties        configuração (lida em tempo de execução, sem recompilar)
lib/jsoup-1.23.2.jar     biblioteca de parsing HTML
src/common/              classes partilhadas (Config, PageData, IndexMessage, SearchResult, Tokenizer, UrlNormalizer)
src/queue/               URLQueue + interface remota
src/downloader/          Downloader + interface remota (callback) + ReliableMulticast
src/barrel/              Barrel + interface remota
src/gateway/             Gateway + interface remota
```

## Compilar

Na pasta do projeto:

**Windows (PowerShell)**
```powershell
javac -encoding UTF-8 -d out -cp lib\jsoup-1.23.2.jar (Get-ChildItem src -Recurse -Filter *.java).FullName
```

**Linux / macOS**
```bash
javac -encoding UTF-8 -d out -cp lib/jsoup-1.23.2.jar $(find src -name "*.java")
```

## Executar

Cada programa corre num terminal próprio, a partir da pasta do projeto. No Linux/macOS o
separador do classpath é `:` em vez de `;` (ex.: `-cp "out:lib/jsoup-1.23.2.jar"`).

Ordem recomendada (os programas toleram outra ordem: esperam pelos que ainda não arrancaram):

1. **Gateway** (cria o seu RMI registry, no porto `gateway.port`)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" gateway.Gateway
   ```
2. **Barrels** (o nome é obrigatório e identifica o ficheiro de dados; registam-se na Gateway)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" barrel.Barrel Barrel1
   java -cp "out;lib\jsoup-1.23.2.jar" barrel.Barrel Barrel2
   ```
3. **Fila de URLs** (cria o RMI registry da fila, no porto `rmi.port`)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" queue.URLQueue
   ```
4. **Downloaders** (um ou mais, cada um num terminal)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" downloader.Downloader
   ```

Todos os programas aceitam, como último argumento, o caminho de outro ficheiro de configuração
(por omissão usam `googol.properties` da pasta atual). Para terminar: `Ctrl+C`.

Os Downloaders podem arrancar antes da fila: esperam por ela ("URLQueue not available ...,
retrying"). Se a fila for reiniciada, os Downloaders detetam a falha e voltam a ligar-se sozinhos.
Sem nenhum Barrel ativo, os Downloaders esperam antes de enviar páginas ("No active Barrels").
Os Barrels voltam a registar-se sozinhos se a Gateway reiniciar.
Erros de configuração (ficheiro inexistente, valor inválido, porto ocupado) aparecem como uma
linha `Error: ...`.

## Configuração (`googol.properties`)

| Chave | Significado |
|---|---|
| `rmi.host`, `rmi.port` | Máquina e porto do RMI registry da fila (onde corre a URLQueue) |
| `rmi.hostname.local` | IP **desta** máquina, anunciado nas referências remotas (callbacks) |
| `queue.name` | Nome da fila no registry |
| `queue.seeds` | URLs iniciais, separados por vírgulas |
| `queue.max.retries` | Novas tentativas para um URL cujo download falhou |
| `gateway.host`, `gateway.port`, `gateway.name` | Máquina, porto do registry e nome da Gateway |
| `gateway.barrel.timeout.ms` | Tempo sem heartbeat até a Gateway retirar um Barrel da lista |
| `downloader.delay.ms` | Pausa entre páginas de cada Downloader |
| `downloader.reconnect.ms` | Intervalo entre verificações da fila e da lista de Barrels, e entre tentativas de nova ligação |
| `multicast.retry.ms` | Intervalo entre retransmissões a um Barrel que não respondeu |
| `barrel.data.dir` | Pasta onde os Barrels guardam o índice |
| `barrel.save.interval.ms` | Intervalo entre gravações do índice em disco |
| `barrel.heartbeat.ms` | Intervalo entre heartbeats (registos) de cada Barrel na Gateway |

## Execução em duas máquinas

| | Máquina #1 | Máquina #2 |
|---|---|---|
| Programas | Gateway, URLQueue, Downloader, Barrel | Downloader, Barrel |
| `rmi.host` | IP da #1 | IP da #1 |
| `gateway.host` | IP da #1 | IP da #1 |
| `rmi.hostname.local` | IP da #1 | IP da #2 |

Os dois Barrels têm de ter **nomes diferentes** (ex.: `Barrel1` na #1 e `Barrel2` na #2).

As duas máquinas têm de estar na mesma rede e a firewall tem de permitir o `java` (o RMI usa o
porto do registry e portos dinâmicos para os objetos exportados).

## Apagar os dados de teste

O índice de cada Barrel fica em `data/<nome>.ser` (pasta definida em `barrel.data.dir`).
Para começar do zero, parar os Barrels e apagar a pasta `data/`.
