package client;

import barrel.BarrelInterface;
import common.Config;
import common.SearchResult;
import common.Stats;
import gateway.GatewayInterface;
import gateway.NoBarrelAvailableException;
import gateway.StatsListener;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.NoSuchFileException;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/*
    Cliente RMI do Googol: UI de consola que só invoca os métodos remotos da Gateway.
    - Failover: todos os pedidos passam por call(). Se a Gateway não responder (RemoteException ou
      ainda não registada), o cliente volta a procurá-la no registry e repete o MESMO pedido, de
      retryMs em retryMs, até retryAttempts tentativas; depois mostra uma mensagem e volta ao menu.
      Assim nenhuma operação fica a meio: ou é concluída, ou o utilizador é avisado de que falhou.
    - Estatísticas: o cliente é um objeto remoto (StatsListener). No ecrã de estatísticas subscreve-se
      e a Gateway chama statsUpdated() sempre que mudam (callback, sem polling). Uma thread faz ping
      à Gateway: se ela avariou ou reiniciou, a subscrição perdeu-se e o cliente subscreve-se de novo.
*/

public class Client implements StatsListener {

    // Número de resultados por página (definido pelo Barrel)
    private static final int PAGE_SIZE = BarrelInterface.PAGE_SIZE;

    // Pedido à Gateway: o failover repete-o tal e qual noutra tentativa
    private interface GatewayCall<T> {
        T call(GatewayInterface gateway) throws RemoteException, NoBarrelAvailableException;
    }

    // A Gateway não respondeu em nenhuma das tentativas
    public static class GatewayUnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        public GatewayUnavailableException(String message) {
            super(message);
        }
    }

    // Onde está a Gateway (registry) e com que nome
    private final String gatewayHost;
    private final int gatewayPort;
    private final String gatewayName;

    // Intervalo entre tentativas e número máximo de tentativas de um pedido
    private final long retryMs;
    private final int retryAttempts;

    // Teclado e ecrã (passados no construtor para os testes poderem simular o utilizador)
    private final BufferedReader in;
    private final PrintStream out;

    // Referência remota atual para a Gateway (null = procurar no registry no próximo pedido).
    // volatile: é usada pela thread do menu e pela thread de verificação das estatísticas.
    private volatile GatewayInterface gateway;

    // Referência remota (stub) deste cliente, enviada à Gateway na subscrição das estatísticas
    private StatsListener self;

    // true enquanto o ecrã de estatísticas está aberto (só então as atualizações são mostradas)
    private volatile boolean showingStats = false;

    // true se o último pedido feito por call() falhou pelo menos uma vez antes de ser concluído
    private boolean lastCallRetried = false;

    public Client(String gatewayHost, int gatewayPort, String gatewayName, long retryMs, int retryAttempts,
                  BufferedReader in, PrintStream out) {
        this.gatewayHost = gatewayHost;
        this.gatewayPort = gatewayPort;
        this.gatewayName = gatewayName;
        this.retryMs = retryMs;
        this.retryAttempts = Math.max(1, retryAttempts);
        this.in = in;
        this.out = out;
    }

    // ------------------------------------------------------------------ Ligação à Gateway

    // Referência para a Gateway (procura-a no registry se ainda não a tiver)
    private synchronized GatewayInterface gateway() throws RemoteException, NotBoundException {
        GatewayInterface g = gateway;
        if (g == null) {
            g = (GatewayInterface) LocateRegistry.getRegistry(gatewayHost, gatewayPort).lookup(gatewayName);
            gateway = g;
        }
        return g;
    }

    // Faz o pedido à Gateway com failover: se falhar, procura de novo a Gateway e repete o pedido.
    // Uma referência antiga que falha (Gateway reiniciada) é trocada logo, sem contar como tentativa;
    // só as falhas com uma referência acabada de obter contam e esperam retryMs.
    // NoBarrelAvailableException não é repetida: a Gateway está a funcionar e a mensagem é para o utilizador.
    private <T> T call(GatewayCall<T> request) throws GatewayUnavailableException, NoBarrelAvailableException {
        lastCallRetried = false;
        int failures = 0;
        while (true) {
            boolean fresh = gateway == null;
            try {
                return request.call(gateway());
            } catch (RemoteException | NotBoundException e) {
                gateway = null;
                lastCallRetried = true;
                if (!fresh) {
                    continue;
                }
                failures++;
                if (failures >= retryAttempts) {
                    throw new GatewayUnavailableException("A Gateway não respondeu depois de " + retryAttempts
                            + " tentativas: tente de novo mais tarde.");
                }
                out.println("Sem ligação à Gateway: nova tentativa (" + (failures + 1) + "/" + retryAttempts
                        + ") dentro de " + retryMs + " ms...");
                try {
                    Thread.sleep(retryMs);
                } catch (InterruptedException ie) {
                    throw new GatewayUnavailableException("Pedido interrompido.");
                }
            }
        }
    }

    // ------------------------------------------------------------------ Menu

    // Ciclo do menu; termina com a opção 0 ou quando a entrada acaba
    public void run() throws IOException {
        // Exporta-se como objeto remoto para receber as estatísticas por callback
        self = (StatsListener) UnicastRemoteObject.exportObject(this, 0);
        try {
            while (true) {
                out.println();
                out.println("===== Googol =====");
                out.println("1. Indexar URL");
                out.println("2. Pesquisar");
                out.println("3. Páginas com ligação para um URL");
                out.println("4. Estatísticas em tempo real");
                out.println("0. Sair");
                String option = prompt("Opção: ");
                if (option == null || option.equals("0")) {
                    return;
                }
                try {
                    switch (option) {
                        case "1" -> indexUrl();
                        case "2" -> search();
                        case "3" -> backlinks();
                        case "4" -> stats();
                        default -> out.println("Opção inválida.");
                    }
                } catch (GatewayUnavailableException | NoBarrelAvailableException e) {
                    out.println(e.getMessage());
                }
            }
        } finally {
            try {
                UnicastRemoteObject.unexportObject(this, true);
            } catch (NoSuchObjectException e) {
                // já não estava exportado
            }
        }
    }

    // Mostra a pergunta e lê uma linha (sem espaços nas pontas); null se a entrada acabou
    private String prompt(String question) throws IOException {
        out.print(question);
        out.flush(); // a pergunta não acaba em \n: sem flush podia não aparecer antes de ler
        String line = in.readLine();
        return line == null ? null : line.trim();
    }

    // Opção 1: pedido de indexação de um URL
    private void indexUrl() throws IOException, GatewayUnavailableException, NoBarrelAvailableException {
        String url = prompt("URL a indexar: ");
        if (url == null || url.isEmpty()) {
            return;
        }
        GatewayInterface.IndexResult result = call(g -> g.indexUrl(url));
        switch (result) {
            case QUEUED -> out.println("URL colocado no início da fila: vai ser indexado assim que possível.");
            // Se uma tentativa falhou, o primeiro pedido pode ter chegado à Gateway e só a resposta se
            // perdeu: nesse caso "já conhecido" quer dizer que foi o nosso pedido que o pôs na fila
            case ALREADY_KNOWN -> out.println(lastCallRetried
                    ? "Pedido aceite: o URL está na fila ou já foi indexado."
                    : "Este URL já foi (ou está a ser) indexado.");
            case INVALID -> out.println("URL inválido: tem de começar por http:// ou https://.");
            case PENDING -> out.println("A fila de URLs está indisponível: a Gateway guardou o pedido e envia-o "
                    + "assim que a fila voltar.");
        }
    }

    // Opção 2: pesquisa, com os resultados de 10 em 10 e navegação entre páginas
    private void search() throws IOException, GatewayUnavailableException, NoBarrelAvailableException {
        String query = prompt("Termos de pesquisa: ");
        if (query == null || query.isEmpty()) {
            return;
        }
        int page = 0;
        List<SearchResult> results = call(g -> g.search(query, 0));
        if (results.isEmpty()) {
            out.println("Nenhuma página contém todos os termos.");
            return;
        }
        boolean show = true;
        while (true) {
            if (show) {
                showResults(results, page);
                show = false;
            }
            String command = prompt("[s] seguinte  [a] anterior  [l N] ligações para o resultado N  [m] menu: ");
            if (command == null || command.equalsIgnoreCase("m")) {
                return;
            }
            if (command.equalsIgnoreCase("s")) {
                // Uma página incompleta é a última; uma página cheia pode ser seguida de uma vazia
                int next = page + 1;
                List<SearchResult> nextResults = results.size() < PAGE_SIZE ? List.of() : call(g -> g.search(query, next));
                if (nextResults.isEmpty()) {
                    out.println("Não há mais resultados.");
                } else {
                    page = next;
                    results = nextResults;
                    show = true;
                }
            } else if (command.equalsIgnoreCase("a")) {
                if (page == 0) {
                    out.println("Já está na primeira página.");
                } else {
                    int previous = page - 1;
                    results = call(g -> g.search(query, previous));
                    page = previous;
                    show = true;
                }
            } else if (command.toLowerCase().startsWith("l")) {
                // Número do resultado tal como aparece no ecrã (11 a 20 na segunda página, ...)
                int first = page * PAGE_SIZE + 1;
                int last = page * PAGE_SIZE + results.size();
                int n;
                try {
                    n = Integer.parseInt(command.substring(1).trim());
                } catch (NumberFormatException e) {
                    n = -1;
                }
                if (n < first || n > last) {
                    out.println("Número de resultado inválido: escolha entre " + first + " e " + last + ".");
                } else {
                    showBacklinks(results.get(n - first).getUrl());
                }
            } else {
                out.println("Comando inválido.");
            }
        }
    }

    // Mostra uma página de resultados: título, URL completo, citação e número de ligações recebidas
    private void showResults(List<SearchResult> results, int page) {
        int from = page * PAGE_SIZE;
        out.println("--- Resultados " + (from + 1) + "-" + (from + results.size()) + " (página " + (page + 1) + ") ---");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            String title = r.getTitle() == null || r.getTitle().isBlank() ? "(sem título)" : r.getTitle();
            out.println((from + i + 1) + ". " + title);
            out.println("   " + r.getUrl());
            if (r.getSnippet() != null && !r.getSnippet().isBlank()) {
                out.println("   " + r.getSnippet());
            }
            out.println("   (" + r.getInboundLinks() + " ligações recebidas)");
        }
    }

    // Opção 3: páginas com ligação para um URL
    private void backlinks() throws IOException, GatewayUnavailableException, NoBarrelAvailableException {
        String url = prompt("URL: ");
        if (url == null || url.isEmpty()) {
            return;
        }
        showBacklinks(url);
    }

    private void showBacklinks(String url) throws GatewayUnavailableException, NoBarrelAvailableException {
        List<String> links = call(g -> g.getBacklinks(url));
        if (links.isEmpty()) {
            out.println("Nenhuma página conhecida tem ligação para " + url);
            return;
        }
        out.println("--- " + links.size() + " páginas com ligação para " + url + " ---");
        for (String link : links) {
            out.println("  " + link);
        }
    }

    // ------------------------------------------------------------------ Estatísticas

    // Opção 4: subscreve as estatísticas e mostra cada atualização até o utilizador carregar em Enter
    private void stats() throws IOException, GatewayUnavailableException, NoBarrelAvailableException {
        out.println("--- Estatísticas em tempo real (Enter para voltar ao menu) ---");
        Thread monitor = null;
        try {
            // showingStats antes de subscrever: a Gateway envia logo as estatísticas atuais
            showingStats = true;
            call(g -> {
                g.subscribeStats(self);
                return null;
            });
            monitor = new Thread(this::statsMonitor, "stats-monitor");
            monitor.setDaemon(true);
            monitor.start();
            in.readLine(); // espera pelo Enter
        } finally {
            // Sob o lock: a thread de verificação não pode voltar a subscrever depois deste ponto
            synchronized (this) {
                showingStats = false;
            }
            if (monitor != null) {
                monitor.interrupt();
            }
            try {
                GatewayInterface g = gateway;
                if (g != null) {
                    g.unsubscribeStats(self);
                }
            } catch (RemoteException e) {
                // Gateway em baixo: a subscrição perdeu-se com ela
            }
        }
    }

    // Thread do ecrã de estatísticas: verifica (ping) se a Gateway continua ativa. Se não estiver
    // (avaria ou reinício), a subscrição perdeu-se: procura a Gateway e subscreve-se de novo
    // (a Gateway envia logo as estatísticas atuais). O ping não pede estatísticas, por isso não é
    // um refrescamento periódico: as estatísticas só chegam quando mudam.
    private void statsMonitor() {
        boolean lost = false;
        while (showingStats) {
            try {
                Thread.sleep(retryMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (lost) {
                    synchronized (this) {
                        if (!showingStats) {
                            return;
                        }
                        gateway().subscribeStats(self);
                    }
                    out.println("Ligação à Gateway restabelecida: estatísticas subscritas de novo.");
                    lost = false;
                } else {
                    gateway().ping();
                }
            } catch (RemoteException | NotBoundException e) {
                gateway = null;
                if (!lost) {
                    out.println("Ligação à Gateway perdida: a tentar de novo a cada " + retryMs
                            + " ms (Enter para voltar ao menu)...");
                    lost = true;
                }
            }
        }
    }

    // Callback chamado pela Gateway quando as estatísticas mudam
    @Override
    public void statsUpdated(Stats stats) {
        if (!showingStats) {
            return; // atualização atrasada, já depois de sair do ecrã de estatísticas
        }
        // Um único bloco: as linhas não se misturam com mensagens de outras threads
        synchronized (out) {
            out.println();
            out.println("=== Estatísticas (" + LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + ") ===");
            out.print(stats.format());
            out.println("(Enter para voltar ao menu)");
            out.flush();
        }
    }

    // ------------------------------------------------------------------ Arranque

    // Codificação do teclado: a indicada pelo Java (stdin.encoding) ou a da consola; senão a do sistema
    private static Charset inputCharset() {
        String encoding = System.getProperty("stdin.encoding");
        if (encoding != null && Charset.isSupported(encoding)) {
            return Charset.forName(encoding);
        }
        Console console = System.console();
        return console != null ? console.charset() : Charset.defaultCharset();
    }

    // Arranque: java client.Client [ficheiro.properties]
    public static void main(String[] args) {
        try {
            // Lê a configuração e define o IP desta máquina para o RMI (callback das estatísticas)
            Config config = Config.load(args);
            config.applyLocalHostname();

            Client client = new Client(config.get("gateway.host"), config.getInt("gateway.port"),
                    config.get("gateway.name"), config.getInt("client.retry.ms"), config.getInt("client.retry.attempts"),
                    new BufferedReader(new InputStreamReader(System.in, inputCharset())), System.out);
            client.run();

            // Erros previsíveis: mensagem clara em vez de stack trace
        } catch (NoSuchFileException e) {
            Config.exitWithError("configuration file not found: " + e.getFile());
        } catch (IllegalArgumentException e) {
            Config.exitWithError("invalid configuration: " + e.getMessage());
        } catch (Exception e) {
            Config.exitWithError("Client could not start: " + e);
        }
        // Termina também as threads do RMI
        System.exit(0);
    }
}
