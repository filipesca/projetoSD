# Googol: motor de pesquisa distribuído (SD 2026/27, Meta 1)

Motor de pesquisa de páginas Web em **Java RMI**: Downloaders descarregam e analisam páginas
(jsoup), Index Storage Barrels guardam o índice invertido de forma persistente e replicada, e
uma Gateway RMI serve de única porta de entrada para os clientes.

> Explicação completa da arquitetura e das decisões técnicas: [RESUMO.md](RESUMO.md).

## Estado atual

| Componente | Estado |
|---|---|
| `queue.URLQueue`: fila de URLs | ✅ |
| `downloader.Downloader`: crawler (jsoup) | ✅ (ainda não envia para os Barrels) |
| `barrel.Barrel`: índice invertido persistente | ✅ (ainda não ligado à Gateway nem aos Downloaders) |
| Reliable multicast Downloader → Barrels | ⏳ |
| `gateway.Gateway` | ⏳ |
| `client.Client` | ⏳ |

## Requisitos

- **JDK 17 ou superior** (`java` e `javac` no PATH). Verificar com `java -version`.
- A biblioteca **jsoup** já vem incluída em `lib/jsoup-1.23.2.jar`.

## Estrutura

```
googol.properties        configuração (lida em tempo de execução, sem recompilar)
lib/jsoup-1.23.2.jar     biblioteca de parsing HTML
src/common/              classes partilhadas (Config, PageData, SearchResult, Tokenizer)
src/queue/               URLQueue + interface remota
src/downloader/          Downloader + interface remota (callback)
src/barrel/              Barrel + interface remota
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

1. **Fila de URLs** (arranca primeiro: cria o RMI registry)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" queue.URLQueue
   ```
2. **Downloaders** (um ou mais, cada um num terminal)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" downloader.Downloader
   ```
3. **Barrels** (o nome é obrigatório e identifica o ficheiro de dados)
   ```
   java -cp "out;lib\jsoup-1.23.2.jar" barrel.Barrel Barrel1
   ```

Todos os programas aceitam, como último argumento, o caminho de outro ficheiro de configuração
(por omissão usam `googol.properties` da pasta atual). Para terminar: `Ctrl+C`.

## Configuração (`googol.properties`)

| Chave | Significado |
|---|---|
| `rmi.host`, `rmi.port` | Máquina e porto do RMI registry (onde corre a URLQueue) |
| `rmi.hostname.local` | IP **desta** máquina, anunciado nas referências remotas (callbacks) |
| `queue.name` | Nome da fila no registry |
| `queue.seeds` | URLs iniciais, separados por vírgulas |
| `queue.max.retries` | Novas tentativas para um URL cujo download falhou |
| `downloader.delay.ms` | Pausa entre páginas de cada Downloader |
| `barrel.data.dir` | Pasta onde os Barrels guardam o índice |
| `barrel.save.interval.ms` | Intervalo entre gravações do índice em disco |

## Execução em duas máquinas

| | Máquina #1 | Máquina #2 |
|---|---|---|
| Programas | URLQueue, Downloader, Barrel | Downloader, Barrel |
| `rmi.host` | IP da #1 | IP da #1 |
| `rmi.hostname.local` | IP da #1 | IP da #2 |

As duas máquinas têm de estar na mesma rede e a firewall tem de permitir o `java` (o RMI usa o
porto do registry e portos dinâmicos para os objetos exportados).

## Apagar os dados de teste

O índice de cada Barrel fica em `data/<nome>.ser` (pasta definida em `barrel.data.dir`).
Para começar do zero, parar os Barrels e apagar a pasta `data/`.
