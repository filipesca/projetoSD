package gateway;

import barrel.BarrelInterface;
import common.Config;
import common.SearchResult;
import common.Stats;
import common.Tokenizer;
import common.UrlNormalizer;
import queue.URLQueueInterface;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadLocalRandom;

/*
    Gateway RMI: porta de entrada do sistema. Não guarda dados do índice.
    - Barrels ativos: cada Barrel regista-se e renova o registo periodicamente (heartbeat), como
      uma "lease"; uma thread retira os Barrels que deixaram de renovar o registo.
    - Pesquisas: escolhe um Barrel por desempenho (probabilidade proporcional a 1/tempo médio) e,
      se ele falhar (RemoteException), marca-o como suspeito e repete o pedido noutro Barrel (failover).
    - Indexação: envia os URLs dos utilizadores para o início da fila; se a fila estiver em baixo,
      guarda-os e reenvia-os quando ela voltar (os pedidos de indexação não se perdem).
    - Estatísticas: enviadas aos clientes subscritos por callback, só quando mudam.
    - Recuperação de avarias: os URLs pendentes e o número de vezes que cada pesquisa foi feita são
      gravados num ficheiro de objetos (gateway.data.file) e carregados ao arrancar. Os URLs pendentes
      são gravados logo em cada alteração; as contagens periodicamente. Os tempos de resposta dos
      Barrels não são gravados (decisão do grupo): são medições e voltam a ser medidos.
    A Gateway cria o seu próprio RMI registry, para não depender do processo da fila.
*/

public class Gateway implements GatewayInterface {

    // Registo de um Barrel: referência remota, instante do último heartbeat e tamanho do índice.
    // suspect = falhou um pedido desde o último heartbeat: deixa de receber pesquisas e não conta
    // como ativo nas estatísticas, mas continua na lista dos Downloaders (que guardam as mensagens
    // para ele) até a lease expirar. Se estiver vivo, o próximo heartbeat cria um registo novo.
    // ready = false enquanto o Barrel se sincroniza ao arrancar (índice ainda incompleto): tal como
    // um suspeito, não recebe pesquisas nem conta como ativo, mas recebe as mensagens dos Downloaders.
    private static class BarrelEntry {
        final BarrelInterface stub;
        final long lastHeartbeat;
        final int pages;
        final int words;
        final boolean ready;
        volatile boolean suspect = false;

        BarrelEntry(BarrelInterface stub, long lastHeartbeat, int pages, int words, boolean ready) {
            this.stub = stub;
            this.lastHeartbeat = lastHeartbeat;
            this.pages = pages;
            this.words = words;
            this.ready = ready;
        }

        // Pode responder a pesquisas e aparece nas estatísticas
        boolean available() {
            return ready && !suspect;
        }
    }

    // Tempo de resposta de um Barrel às pesquisas (para o balanceamento e as estatísticas)
    private static class Performance {
        long searches = 0;
        long totalNanos = 0;

        synchronized void record(long nanos) {
            searches++;
            totalNanos += nanos;
        }

        synchronized long searches() {
            return searches;
        }

        // Tempo médio em milissegundos (0 se ainda não houver medições)
        synchronized double avgMillis() {
            return searches == 0 ? 0 : totalNanos / 1e6 / searches;
        }
    }

    // Chamada a um Barrel (usada pelo failover para repetir o mesmo pedido noutro Barrel)
    private interface BarrelCall<T> {
        T call(BarrelInterface barrel) throws RemoteException;
    }

    // Barrels registados (thread-safe: os registos chegam em paralelo por threads do RMI)
    private final Map<String, BarrelEntry> barrels = new ConcurrentHashMap<>();

    // Tempo de resposta por Barrel (mantém-se se o Barrel reiniciar com o mesmo nome)
    private final Map<String, Performance> performance = new ConcurrentHashMap<>();

    // Número de vezes que cada pesquisa foi feita (termos normalizados e ordenados)
    private final Map<String, Integer> searchCounts = new ConcurrentHashMap<>();

    // Clientes subscritos às estatísticas (CopyOnWriteArrayList: percorrida enquanto outros se subscrevem)
    private final CopyOnWriteArrayList<StatsListener> listeners = new CopyOnWriteArrayList<>();

    // Últimas estatísticas enviadas (para só enviar quando mudam) e thread que faz os envios,
    // para que um cliente lento ou avariado não atrase as pesquisas
    private Stats lastStats = null;
    private final ExecutorService notifier = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "stats-notifier");
        t.setDaemon(true);
        return t;
    });

    // Fila de URLs: onde está, referência atual e URLs à espera de serem enviados (fila em baixo)
    private final String queueHost;
    private final int queuePort;
    private final String queueName;
    private volatile URLQueueInterface queue;
    private final BlockingDeque<String> pendingUrls = new LinkedBlockingDeque<>();
    private final long queueRetryMs;

    // Intervalo entre verificações da lista de URLs pendentes quando está vazia (milissegundos)
    private static final long PENDING_POLL_MS = 100;

    // Tempo sem heartbeat até um Barrel ser retirado da lista
    private final long barrelTimeoutMs;

    // Estado gravado em ficheiro (null = sem persistência, usado nos testes) e intervalo de gravação
    private final Path dataFile;
    private final long saveIntervalMs;

    // true quando as contagens de pesquisas mudaram e ainda não foram gravadas
    private volatile boolean countsDirty = false;

    // Estado da Gateway gravado em ficheiro (só o que não pode ser recalculado)
    private static class GatewayState implements Serializable {
        private static final long serialVersionUID = 1L;

        final HashMap<String, Integer> searchCounts;
        final ArrayList<String> pendingUrls;

        GatewayState(HashMap<String, Integer> searchCounts, ArrayList<String> pendingUrls) {
            this.searchCounts = searchCounts;
            this.pendingUrls = pendingUrls;
        }
    }

    // Gateway sem persistência (testes)
    public Gateway(long barrelTimeoutMs, String queueHost, int queuePort, String queueName, long queueRetryMs) {
        this(barrelTimeoutMs, queueHost, queuePort, queueName, queueRetryMs, null, 0);
    }

    // Carrega o estado gravado (se existir) para recuperar de um crash
    public Gateway(long barrelTimeoutMs, String queueHost, int queuePort, String queueName, long queueRetryMs,
                   Path dataFile, long saveIntervalMs) {
        this.barrelTimeoutMs = barrelTimeoutMs;
        this.queueHost = queueHost;
        this.queuePort = queuePort;
        this.queueName = queueName;
        this.queueRetryMs = queueRetryMs;
        this.dataFile = dataFile;
        this.saveIntervalMs = saveIntervalMs;
        if (dataFile != null) {
            load();
        }
    }

    // Inicia as threads internas: expiração dos registos dos Barrels, reenvio dos URLs pendentes e
    // gravação periódica das contagens de pesquisas
    public void start() {
        Thread expiry = new Thread(this::expiryLoop, "barrel-expiry");
        expiry.setDaemon(true);
        expiry.start();
        Thread pending = new Thread(this::pendingUrlsLoop, "pending-urls");
        pending.setDaemon(true);
        pending.start();
        if (dataFile != null) {
            Thread saver = new Thread(this::saveLoop, "gateway-saver");
            saver.setDaemon(true);
            saver.start();
        }
    }

    // ------------------------------------------------------------------ Persistência

    // Grava o estado: escreve num ficheiro temporário e substitui o anterior (um crash a meio da
    // escrita nunca corrompe o ficheiro). synchronized: gravações de threads diferentes não se misturam.
    public synchronized void save() {
        if (dataFile == null) {
            return;
        }
        countsDirty = false;
        GatewayState state = new GatewayState(new HashMap<>(searchCounts), new ArrayList<>(pendingUrls));
        Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try {
            if (dataFile.getParent() != null) {
                Files.createDirectories(dataFile.getParent());
            }
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
                out.writeObject(state);
            }
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            countsDirty = true; // tenta de novo na próxima gravação periódica
            System.out.println("Could not save Gateway state: " + e.getMessage());
        }
    }

    // Lê o estado gravado; se não existir ou estiver corrompido, começa vazio
    private void load() {
        if (!Files.exists(dataFile)) {
            System.out.println("No saved Gateway state found, starting empty: " + dataFile);
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(dataFile))) {
            GatewayState state = (GatewayState) in.readObject();
            searchCounts.putAll(state.searchCounts);
            pendingUrls.addAll(state.pendingUrls);
            System.out.println("Gateway state loaded from " + dataFile + ": " + state.pendingUrls.size()
                    + " pending URLs, " + state.searchCounts.size() + " distinct searches.");
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            System.out.println("Saved Gateway state is unreadable, starting empty: " + dataFile + " (" + e + ")");
        }
    }

    // Grava as contagens de pesquisas periodicamente, se tiverem mudado
    private void saveLoop() {
        while (true) {
            try {
                Thread.sleep(saveIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
            if (countsDirty) {
                save();
            }
        }
    }

    // ------------------------------------------------------------------ Barrels

    @Override
    public boolean registerBarrel(String name, BarrelInterface barrel, int pages, int words, boolean ready) {
        BarrelEntry old = barrels.put(name, new BarrelEntry(barrel, System.currentTimeMillis(), pages, words, ready));
        // Só mostra a mensagem quando o Barrel é novo ou reiniciou (nova referência remota)
        if (old == null || !old.stub.equals(barrel)) {
            System.out.println("Barrel " + name + " registered" + (ready ? "" : " (synchronizing)")
                    + " (" + barrels.size() + " registered).");
        } else if (ready && !old.ready) {
            System.out.println("Barrel " + name + " synchronized: now answering searches.");
        }
        // Barrel novo, de novo disponível, pronto, ou índice com outro tamanho: as estatísticas mudaram
        if (old == null || old.suspect || old.ready != ready || old.pages != pages || old.words != words) {
            statsChanged();
        }
        // Registo novo (Barrel desconhecido, ou retirado por a lease ter expirado)
        return old == null;
    }

    @Override
    public HashMap<String, BarrelInterface> getBarrels() {
        HashMap<String, BarrelInterface> active = new HashMap<>();
        for (Map.Entry<String, BarrelEntry> e : barrels.entrySet()) {
            active.put(e.getKey(), e.getValue().stub);
        }
        return active;
    }

    // Retira os Barrels sem heartbeat dentro do prazo (avariados ou desligados)
    public void removeExpiredBarrels() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, BarrelEntry> e : barrels.entrySet()) {
            BarrelEntry entry = e.getValue();
            // remove(chave, valor) só remove se o registo não tiver sido renovado entretanto
            if (now - entry.lastHeartbeat > barrelTimeoutMs && barrels.remove(e.getKey(), entry)) {
                System.out.println("Barrel " + e.getKey() + " removed: no heartbeat for more than "
                        + barrelTimeoutMs + " ms (" + barrels.size() + " active).");
                statsChanged();
            }
        }
    }

    // Thread que verifica os prazos periodicamente
    private void expiryLoop() {
        while (true) {
            try {
                Thread.sleep(Math.max(100, barrelTimeoutMs / 4));
            } catch (InterruptedException e) {
                return;
            }
            removeExpiredBarrels();
        }
    }

    // Ordem pela qual os Barrels são tentados num pedido (balanceamento por desempenho):
    // o primeiro é sorteado com probabilidade proporcional a 1/tempo médio (um Barrel duas vezes
    // mais rápido recebe o dobro das pesquisas, mas todos continuam a ser usados e medidos);
    // os restantes ficam por ordem de rapidez, para o failover.
    // Um Barrel ainda sem medições recebe o peso do mais rápido, para começar a ser medido.
    private List<String> chooseBarrels() {
        List<String> available = availableBarrels();
        Map<String, Double> weights = new HashMap<>();
        double best = 0;
        for (String name : available) {
            Performance p = performance.get(name);
            if (p != null && p.searches() > 0) {
                double weight = 1.0 / Math.max(p.avgMillis(), 0.01);
                weights.put(name, weight);
                best = Math.max(best, weight);
            }
        }
        for (String name : available) {
            weights.putIfAbsent(name, best > 0 ? best : 1.0);
        }

        List<String> order = new ArrayList<>(weights.keySet());
        order.sort(Comparator.comparingDouble((String n) -> weights.get(n)).reversed());
        if (order.size() > 1) {
            double total = weights.values().stream().mapToDouble(Double::doubleValue).sum();
            double r = ThreadLocalRandom.current().nextDouble(total);
            for (String name : order) {
                r -= weights.get(name);
                if (r < 0) {
                    order.remove(name);
                    order.add(0, name);
                    break;
                }
            }
        }
        return order;
    }

    // Barrels que podem responder a pedidos (registados, sincronizados e não suspeitos), por nome
    private List<String> availableBarrels() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, BarrelEntry> e : barrels.entrySet()) {
            if (e.getValue().available()) {
                names.add(e.getKey());
            }
        }
        names.sort(null);
        return names;
    }

    // Faz o pedido a um Barrel; se ele falhar (RemoteException), marca-o como suspeito (volta no
    // próximo heartbeat, se estiver vivo) e repete o mesmo pedido no Barrel seguinte (failover)
    private <T> T callBarrels(String what, BarrelCall<T> request, boolean measure) throws NoBarrelAvailableException {
        for (String name : chooseBarrels()) {
            BarrelEntry entry = barrels.get(name);
            if (entry == null || !entry.available()) {
                continue;
            }
            long start = System.nanoTime();
            try {
                T result = request.call(entry.stub);
                if (measure) {
                    performance.computeIfAbsent(name, n -> new Performance()).record(System.nanoTime() - start);
                    statsChanged();
                }
                return result;
            } catch (RemoteException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                System.out.println("Barrel " + name + " failed during " + what + " (" + cause.getClass().getSimpleName()
                        + "): trying another Barrel.");
                entry.suspect = true;
                statsChanged();
            }
        }
        throw new NoBarrelAvailableException("No active Storage Barrel available. Try again later.");
    }

    // ------------------------------------------------------------------ Clientes

    @Override
    public List<SearchResult> search(String query, int page) throws NoBarrelAvailableException {
        List<SearchResult> results = callBarrels("search", b -> b.search(query, page), true);

        // Conta para o top 10 só a primeira página (avançar nos resultados não é uma nova pesquisa).
        // Termos normalizados e ordenados: "Coimbra universidade" e "universidade coimbra" contam como iguais.
        String key = String.join(" ", new TreeSet<>(Tokenizer.words(query)));
        if (page == 0 && !key.isEmpty()) {
            searchCounts.merge(key, 1, Integer::sum);
            countsDirty = true;
            statsChanged();
        }
        return results;
    }

    @Override
    public List<String> getBacklinks(String url) throws NoBarrelAvailableException {
        // Normaliza o URL tal como os Downloaders (ex.: "https://uc.pt" -> "https://uc.pt/")
        String normalized = UrlNormalizer.normalize(url);
        if (normalized == null) {
            return new ArrayList<>();
        }
        return callBarrels("backlinks", b -> b.getBacklinks(normalized), false);
    }

    @Override
    public IndexResult indexUrl(String url) {
        String normalized = UrlNormalizer.normalize(url);
        if (normalized == null) {
            return IndexResult.INVALID;
        }
        // Se já há URLs à espera, este fica atrás deles (mantém a ordem dos pedidos)
        if (!pendingUrls.isEmpty()) {
            pendingUrls.add(normalized);
            save(); // gravado antes de responder: um crash da Gateway não perde o pedido
            return IndexResult.PENDING;
        }
        try {
            return queue().addUrlPriority(normalized) ? IndexResult.QUEUED : IndexResult.ALREADY_KNOWN;
        } catch (Exception e) {
            // Fila em baixo: o pedido não se perde, é gravado e reenviado pela thread de retry
            queue = null;
            pendingUrls.add(normalized);
            save();
            System.out.println("URL queue unavailable: " + normalized + " kept for retry.");
            return IndexResult.PENDING;
        }
    }

    // Referência para a fila (procura-a no registry se ainda não a tiver)
    private URLQueueInterface queue() throws Exception {
        URLQueueInterface q = queue;
        if (q == null) {
            q = (URLQueueInterface) LocateRegistry.getRegistry(queueHost, queuePort).lookup(queueName);
            queue = q;
        }
        return q;
    }

    // Thread que reenvia à fila os URLs pendentes, por ordem, até a fila os aceitar.
    // O URL só sai da lista DEPOIS de a fila o aceitar: até lá continua a ser gravado no ficheiro,
    // por isso um crash da Gateway a meio do envio não o perde (no pior caso é reenviado, e a fila
    // ignora URLs repetidos).
    private void pendingUrlsLoop() {
        while (true) {
            String url = pendingUrls.peekFirst();
            long waitMs = PENDING_POLL_MS; // sem URLs pendentes: verifica de novo daqui a pouco
            if (url != null) {
                try {
                    queue().addUrlPriority(url);
                    pendingUrls.removeFirstOccurrence(url);
                    save(); // já não está pendente
                    System.out.println("Pending URL sent to the queue: " + url);
                    continue;
                } catch (Exception e) {
                    queue = null;
                    waitMs = queueRetryMs; // fila ainda em baixo
                }
            }
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException ie) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ Estatísticas

    @Override
    public synchronized Stats getStats() {
        // Top 10: mais pesquisadas primeiro; em caso de empate, por ordem alfabética
        List<Stats.SearchCount> top = new ArrayList<>();
        searchCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(10)
                .forEach(e -> top.add(new Stats.SearchCount(e.getKey(), e.getValue())));

        // Barrels ativos (por nome, sem os suspeitos nem os que se estão a sincronizar), com o tamanho do índice e o tempo médio em décimas de segundo
        List<Stats.BarrelStats> active = new ArrayList<>();
        availableBarrels().forEach(name -> {
            BarrelEntry entry = barrels.get(name);
            if (entry != null && entry.available()) {
                Performance p = performance.getOrDefault(name, new Performance());
                double tenths = Math.round(p.avgMillis() / 100.0 * 100) / 100.0; // 1 décima = 100 ms (2 casas)
                active.add(new Stats.BarrelStats(name, entry.pages, entry.words, p.searches(), tenths));
            }
        });
        return new Stats(top, active);
    }

    @Override
    public void subscribeStats(StatsListener listener) {
        // Idempotente: um cliente que volta a subscrever (ex.: depois de uma falha de rede passageira)
        // não fica duas vezes na lista (equals dos stubs compara o objeto remoto)
        listeners.addIfAbsent(listener);
        // O novo cliente recebe logo as estatísticas atuais
        notifier.submit(() -> send(listener, getStats()));
    }

    @Override
    public void unsubscribeStats(StatsListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void ping() {
        // Não faz nada: se a chamada chegar aqui, a Gateway está ativa
    }

    // Algo que pode alterar as estatísticas aconteceu: calcula-as e envia-as numa thread à parte
    private void statsChanged() {
        notifier.submit(this::publishIfChanged);
    }

    // Envia as estatísticas a todos os clientes subscritos, só se forem diferentes das últimas enviadas
    private void publishIfChanged() {
        Stats stats = getStats();
        synchronized (this) {
            if (stats.equals(lastStats)) {
                return;
            }
            lastStats = stats;
        }
        for (StatsListener listener : listeners) {
            send(listener, stats);
        }
    }

    // Envia a um cliente; se o cliente já não existir (RemoteException), deixa de lhe enviar
    private void send(StatsListener listener, Stats stats) {
        try {
            listener.statsUpdated(stats);
        } catch (RemoteException e) {
            listeners.remove(listener);
            System.out.println("Stats listener unreachable: removed (" + listeners.size() + " subscribed).");
        }
    }

    public static void main(String[] args) {
        int port = -1;
        try {
            // Lê a configuração e define o IP desta máquina para o RMI
            Config config = Config.load(args);
            config.applyLocalHostname();
            port = config.getInt("gateway.port");

            // Cria a Gateway e exporta-a como objeto remoto
            // Carrega o estado gravado (URLs pendentes e contagens de pesquisas) para recuperar de um crash
            Gateway gateway = new Gateway(config.getInt("gateway.barrel.timeout.ms"), config.getRmiHost(),
                    config.getRmiPort(), config.get("queue.name"), config.getInt("gateway.queue.retry.ms"),
                    Paths.get(config.get("gateway.data.file")), config.getInt("gateway.save.interval.ms"));
            GatewayInterface stub = (GatewayInterface) UnicastRemoteObject.exportObject(gateway, 0);

            // Registry próprio da Gateway (independente do registry da fila)
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind(config.get("gateway.name"), stub);

            // Ao terminar normalmente (ex.: Ctrl+C) grava o que ainda faltar. Só depois de o registry
            // arrancar: uma segunda Gateway que falha no porto não substitui o ficheiro da primeira.
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::save));
            gateway.start();
            System.out.println("Gateway RMI Server ready.");

            // Erros previsíveis: mensagem clara em vez de stack trace
        } catch (NoSuchFileException e) {
            Config.exitWithError("configuration file not found: " + e.getFile());
        } catch (IllegalArgumentException e) {
            Config.exitWithError("invalid configuration: " + e.getMessage());
        } catch (ExportException e) {
            Config.exitWithError("could not use port " + port + " (is another Gateway already running?)");
        } catch (Exception e) {
            Config.exitWithError("Gateway could not start: " + e);
        }
    }
}
