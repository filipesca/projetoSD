# Googol: motor de pesquisa distribuído (SD 2026/27, Meta 1)

O Googol é um motor de pesquisa de páginas Web escrito em **Java RMI**. Indexa automaticamente as
páginas a partir de URLs iniciais e dos que os utilizadores sugerem, e permite pesquisar páginas que
contêm um conjunto de palavras. Os resultados vêm ordenados pelo número de ligações recebidas e são
mostrados de 10 em 10.

O sistema é formado por cinco programas, que podem correr em máquinas diferentes:

| Programa | Classe | Função |
|---|---|---|
| Fila de URLs | `queue.URLQueue` | Guarda os URLs por visitar e entrega cada um a um único Downloader |
| Downloader | `downloader.Downloader` | Descarrega e analisa páginas (jsoup) e envia-as aos Barrels por *reliable multicast*; podem correr vários em paralelo |
| Storage Barrel | `barrel.Barrel` | Guarda o índice invertido, replicado e persistente; responde às pesquisas |
| Gateway | `gateway.Gateway` | Única porta de entrada dos clientes: distribui as pesquisas pelos Barrels e envia as estatísticas em tempo real |
| Cliente | `client.Client` | Menu de consola para indexar URLs, pesquisar, ver ligações e estatísticas |

## Requisitos

- **JDK 17 ou superior**, com `java` e `javac` no PATH (verificar com `java -version`).
- A biblioteca **jsoup** já vem incluída em `lib/jsoup-1.23.2.jar`.

## Estrutura

```
googol.properties   configuração (lida ao arrancar; alterar não exige recompilar)
lib/                jsoup-1.23.2.jar
src/common/         classes partilhadas (configuração, mensagens, normalização de texto e URLs)
src/queue/          fila de URLs
src/downloader/     Downloader e reliable multicast
src/barrel/         Storage Barrel
src/gateway/        Gateway
src/client/         cliente de consola
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

## Executar (uma máquina)

Com o `googol.properties` incluído, tudo corre na mesma máquina sem alterar nada. Cada programa
corre num terminal próprio, a partir da pasta do projeto. No Linux/macOS o separador do classpath é
`:` em vez de `;` (ex.: `-cp "out:lib/jsoup-1.23.2.jar"`).

Ordem recomendada (os programas toleram outra ordem: esperam pelos que ainda não arrancaram):

```
java -cp "out;lib\jsoup-1.23.2.jar" gateway.Gateway
java -cp "out;lib\jsoup-1.23.2.jar" barrel.Barrel Barrel1
java -cp "out;lib\jsoup-1.23.2.jar" barrel.Barrel Barrel2
java -cp "out;lib\jsoup-1.23.2.jar" queue.URLQueue
java -cp "out;lib\jsoup-1.23.2.jar" downloader.Downloader
java -cp "out;lib\jsoup-1.23.2.jar" downloader.Downloader
java -cp "out;lib\jsoup-1.23.2.jar" client.Client
```

- O **nome do Barrel** é obrigatório e identifica o seu ficheiro de dados. Dois Barrels nunca podem
  ter o mesmo nome.
- Pode haver vários Downloaders e vários clientes.
- Todos os programas aceitam, como último argumento, o caminho de outro ficheiro de configuração
  (por omissão usam o `googol.properties` da pasta atual).
- Para terminar um programa: `Ctrl+C`.

### Usar o cliente

| Opção | O que faz |
|---|---|
| `1` | Indexar um URL (entra no início da fila) |
| `2` | Pesquisar. Os resultados aparecem de 10 em 10: `s` seguinte, `a` anterior, `l N` páginas com ligação para o resultado N, `m` voltar ao menu |
| `3` | Páginas com ligação para um URL |
| `4` | Estatísticas em tempo real (atualizadas só quando mudam); Enter volta ao menu |
| `0` | Sair |

## Executar em duas máquinas

Copiar o projeto compilado para as duas máquinas e ajustar o `googol.properties` de cada uma:

| | Máquina #1 | Máquina #2 |
|---|---|---|
| Programas | Gateway, URLQueue, Barrel, Downloader | Barrel, Downloader, Client |
| `rmi.host` (onde está a fila) | IP da #1 | IP da #1 |
| `gateway.host` (onde está a Gateway) | IP da #1 | IP da #1 |
| `rmi.hostname.local` (IP **desta** máquina) | IP da #1 | IP da #2 |

- Os dois Barrels têm de ter nomes diferentes (ex.: `Barrel1` na #1 e `Barrel2` na #2).
- As máquinas têm de estar na mesma rede, e a firewall tem de permitir o `java`. O RMI usa os portos
  dos registries (`rmi.port`, `gateway.port`) e portos dinâmicos para os objetos remotos.

## Configuração (`googol.properties`)

| Chave | Significado |
|---|---|
| `rmi.host`, `rmi.port` | Máquina e porto do RMI registry da fila de URLs |
| `rmi.hostname.local` | IP desta máquina, anunciado nas referências remotas (necessário para os callbacks entre máquinas) |
| `rmi.timeout.ms` | Tempo máximo para ligar a outra máquina e esperar a resposta de uma chamada remota |
| `queue.name` | Nome da fila no registry |
| `queue.seeds` | URLs iniciais, separados por vírgulas |
| `queue.max.retries` | Novas tentativas para um URL cujo download falhou com um erro temporário |
| `gateway.host`, `gateway.port`, `gateway.name` | Máquina, porto do registry e nome da Gateway |
| `gateway.barrel.timeout.ms` | Tempo sem heartbeat até a Gateway retirar um Barrel da lista |
| `gateway.queue.retry.ms` | Intervalo entre tentativas de reenviar à fila os URLs pedidos enquanto ela estava em baixo |
| `gateway.data.file` | Ficheiro onde a Gateway guarda os URLs pendentes e as contagens de pesquisas |
| `gateway.save.interval.ms` | Intervalo entre gravações das contagens de pesquisas |
| `downloader.delay.ms` | Pausa entre páginas de cada Downloader |
| `downloader.reconnect.ms` | Intervalo entre verificações da fila e da lista de Barrels |
| `multicast.retry.ms` | Intervalo entre retransmissões a um Barrel que não respondeu |
| `barrel.data.dir` | Pasta onde os Barrels guardam o índice |
| `barrel.save.interval.ms` | Intervalo entre gravações do índice |
| `barrel.heartbeat.ms` | Intervalo entre heartbeats de cada Barrel na Gateway |
| `client.retry.ms` | Intervalo entre tentativas do cliente quando a Gateway não responde |
| `client.retry.attempts` | Tentativas de um pedido antes de o cliente desistir e voltar ao menu |

Erros de configuração (ficheiro inexistente, valor inválido, porto ocupado) aparecem numa linha
`Error: ...` e o programa termina.

## Apagar os dados de teste

- Cada Barrel guarda o índice em `data/<nome>.ser`.
- A Gateway guarda o seu estado em `data/gateway-state.ser`.

Para começar do zero, parar a Gateway e todos os Barrels e apagar a pasta `data/` em **todas** as
máquinas. Um Barrel que arranca copia o índice de outro Barrel ativo, por isso, se algum continuar
ligado, os dados voltam.
