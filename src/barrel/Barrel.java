package barrel;

import common.Config;
import common.IndexMessage;
import common.PageData;
import common.SearchResult;
import common.Tokenizer;
import gateway.GatewayInterface;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/*
    Index Storage Barrel: guarda o índice invertido e responde às pesquisas.
    - Todos os métodos que leem ou alteram o índice são synchronized, porque o RMI
      executa cada chamada remota numa thread diferente (Downloaders e Gateway em paralelo).
    - Recebe as páginas dos Downloaders por reliable multicast (deliver), descartando repetidas.
    - O índice é guardado num ficheiro de objetos (data/<nome>.ser) periodicamente e ao
      terminar, e é carregado ao arrancar: assim o Barrel recupera o estado depois de um crash.
*/

public class Barrel implements BarrelInterface {

    /*
        Todos os dados do índice, num único objeto para ser gravado/lido de uma só vez.
    */
    private static class IndexData implements Serializable {
        private static final long serialVersionUID = 1L;

        // Índice invertido: palavra -> URLs das páginas onde aparece
        final HashMap<String, HashSet<String>> index = new HashMap<>();

        // URL -> {título, citação} de cada página indexada
        final HashMap<String, String[]> pages = new HashMap<>();

        // URL -> URLs das páginas que têm ligação para ele (usado na ordenação e nos backlinks)
        final HashMap<String, HashSet<String>> backlinks = new HashMap<>();

        // Reliable multicast: último número de sequência entregue de cada Downloader (senderId -> seq).
        // É gravado com o índice para que as mensagens repetidas continuem a ser filtradas depois de reiniciar.
        HashMap<String, Long> lastSeq = new HashMap<>();
    }

    private final String name;
    private final Path dataFile;
    private IndexData data;

    // true quando há alterações ainda não gravadas em ficheiro
    private boolean dirty = false;

    // Referência remota (stub) deste Barrel, enviada à Gateway no registo
    private BarrelInterface self;

    public Barrel(String name, Path dataDir) throws IOException {
        this.name = name;
        Files.createDirectories(dataDir);
        this.dataFile = dataDir.resolve(name + ".ser");
        this.data = load();
    }

    @Override
    public synchronized void deliver(IndexMessage message) {
        // Filtro de duplicados: o Downloader envia por ordem e só avança depois do ACK, por isso
        // um seq menor ou igual ao último entregue é uma retransmissão já recebida
        Long last = data.lastSeq.get(message.getSenderId());
        if (last != null && message.getSeq() <= last) {
            return;
        }
        storePage(message.getPage());
        data.lastSeq.put(message.getSenderId(), message.getSeq());
    }

    // Guarda no índice a informação de uma página (idempotente: guardar duas vezes não altera nada).
    // Público para os testes; remotamente as páginas chegam por deliver().
    public synchronized void storePage(PageData page) {
        String url = page.getUrl();
        data.pages.put(url, new String[]{page.getTitle(), page.getSnippet()});

        // Cada palavra da página aponta para este URL
        for (String word : page.getWords()) {
            data.index.computeIfAbsent(word, w -> new HashSet<>()).add(url);
        }

        // Cada link da página ganha este URL como ligação recebida (ignora links para a própria página)
        for (String link : page.getLinks()) {
            if (!link.equals(url)) {
                data.backlinks.computeIfAbsent(link, l -> new HashSet<>()).add(url);
            }
        }
        dirty = true;
    }

    @Override
    public synchronized List<SearchResult> search(String query, int page) {
        Set<String> terms = Tokenizer.words(query);
        if (terms.isEmpty() || page < 0) {
            return new ArrayList<>();
        }

        // Interseção dos conjuntos de URLs de todos os termos (pesquisa AND)
        Set<String> matches = null;
        for (String term : terms) {
            Set<String> urls = data.index.getOrDefault(term, new HashSet<>());
            if (matches == null) {
                matches = new HashSet<>(urls);
            } else {
                matches.retainAll(urls);
            }
        }

        // Ordena por número de ligações recebidas (desc.); em caso de empate, pelo URL (ordem estável)
        List<String> sorted = new ArrayList<>(matches);
        sorted.sort(Comparator.comparingInt(this::inboundCount).reversed().thenComparing(u -> u));

        // Devolve só a página pedida, com PAGE_SIZE resultados
        List<SearchResult> results = new ArrayList<>();
        int from = page * PAGE_SIZE;
        for (int i = from; i < Math.min(from + PAGE_SIZE, sorted.size()); i++) {
            String url = sorted.get(i);
            String[] info = data.pages.get(url);
            results.add(new SearchResult(url, info[0], info[1], inboundCount(url)));
        }
        return results;
    }

    @Override
    public synchronized List<String> getBacklinks(String url) {
        List<String> list = new ArrayList<>(data.backlinks.getOrDefault(url, new HashSet<>()));
        list.sort(null);
        return list;
    }

    @Override
    public synchronized int getWordCount() {
        return data.index.size();
    }

    @Override
    public synchronized int getPageCount() {
        return data.pages.size();
    }

    // Número de páginas com ligação para o URL
    private int inboundCount(String url) {
        HashSet<String> links = data.backlinks.get(url);
        return links == null ? 0 : links.size();
    }

    // Grava o índice em ficheiro, se houver alterações.
    // Escreve primeiro num ficheiro temporário e depois substitui o anterior, para que um crash
    // a meio da escrita nunca deixe o ficheiro principal corrompido.
    public synchronized void save() {
        if (!dirty) {
            return;
        }
        Path tmp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tmp))) {
            out.writeObject(data);
        } catch (IOException e) {
            System.out.println("Could not save index: " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            System.out.println("Could not save index: " + e.getMessage());
        }
    }

    // Lê o índice do ficheiro; se não existir ou estiver corrompido, começa com um índice vazio
    private IndexData load() {
        if (!Files.exists(dataFile)) {
            System.out.println("No saved index found, starting empty: " + dataFile);
            return new IndexData();
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(dataFile))) {
            IndexData loaded = (IndexData) in.readObject();
            // Ficheiros gravados antes do passo 4 não têm lastSeq
            if (loaded.lastSeq == null) {
                loaded.lastSeq = new HashMap<>();
            }
            System.out.println("Index loaded from " + dataFile + ": " + loaded.pages.size() + " pages, "
                    + loaded.index.size() + " words");
            return loaded;
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            System.out.println("Saved index is unreadable, starting empty: " + dataFile + " (" + e + ")");
            return new IndexData();
        }
    }

    // Grava periodicamente (thread daemon, não impede o programa de terminar)
    public void startAutoSave(long intervalMs) {
        ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "barrel-saver");
            t.setDaemon(true);
            return t;
        });
        saver.scheduleAtFixedRate(this::save, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public String getName() {
        return name;
    }

    // Heartbeat: regista-se na Gateway e renova o registo periodicamente. Se a Gateway estiver
    // desligada ou reiniciar, continua a tentar e volta a aparecer na lista sem ser reiniciado.
    private void gatewayHeartbeatLoop(String host, int port, String gatewayName, long intervalMs) {
        GatewayInterface gateway = null;
        boolean connected = false; // o último registo foi bem sucedido
        boolean warned = false;    // já avisou que a Gateway não está disponível (evita repetir a mensagem)
        while (true) {
            try {
                if (gateway == null) {
                    gateway = (GatewayInterface) LocateRegistry.getRegistry(host, port).lookup(gatewayName);
                }
                // O heartbeat leva o tamanho atual do índice (para as estatísticas da Gateway)
                gateway.registerBarrel(name, self, getPageCount(), getWordCount());
                if (!connected) {
                    System.out.println("Registered in Gateway at " + host + ":" + port + ".");
                    connected = true;
                    warned = false;
                }
            } catch (Exception e) {
                gateway = null; // na próxima tentativa volta a procurar a Gateway no registry
                if (!warned) {
                    System.out.println((connected ? "Lost connection to Gateway" : "Gateway not available at "
                            + host + ":" + port) + ", retrying every " + intervalMs + " ms.");
                    warned = true;
                }
                connected = false;
            }
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // Arranque: java barrel.Barrel <nome> [ficheiro.properties]
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java barrel.Barrel <name> [config.properties]");
            return;
        }
        Path dataDir = null;
        try {
            // O nome vem nos argumentos para várias máquinas poderem usar o mesmo ficheiro de configuração
            String name = args[0];
            Config config = Config.load(args.length > 1 ? args[1] : Config.DEFAULT_FILE);
            config.applyLocalHostname();

            // Lê toda a configuração antes de arrancar (um valor inválido termina logo com mensagem clara)
            dataDir = Paths.get(config.get("barrel.data.dir"));
            int saveIntervalMs = config.getInt("barrel.save.interval.ms");
            int heartbeatMs = config.getInt("barrel.heartbeat.ms");
            String gatewayHost = config.get("gateway.host");
            int gatewayPort = config.getInt("gateway.port");
            String gatewayName = config.get("gateway.name");

            // Carrega o índice guardado (recuperação depois de um crash) e ativa a gravação periódica
            Barrel barrel = new Barrel(name, dataDir);
            barrel.startAutoSave(saveIntervalMs);

            // Ao terminar normalmente (ex.: Ctrl+C) grava o que ainda faltar
            Runtime.getRuntime().addShutdownHook(new Thread(barrel::save));

            // Exporta o Barrel como objeto remoto e regista-se na Gateway (com heartbeat periódico)
            barrel.self = (BarrelInterface) UnicastRemoteObject.exportObject(barrel, 0);
            new Thread(() -> barrel.gatewayHeartbeatLoop(gatewayHost, gatewayPort, gatewayName, heartbeatMs),
                    "gateway-heartbeat").start();

            System.out.println("Barrel " + name + " ready: " + barrel.getPageCount() + " pages, "
                    + barrel.getWordCount() + " words.");

            // Erros previsíveis: mensagem clara em vez de stack trace
        } catch (NoSuchFileException e) {
            Config.exitWithError("configuration file not found: " + e.getFile());
        } catch (IllegalArgumentException e) {
            Config.exitWithError("invalid configuration: " + e.getMessage());
        } catch (IOException e) {
            Config.exitWithError("could not use data directory " + dataDir + ": " + e);
        } catch (Exception e) {
            Config.exitWithError("Barrel could not start: " + e);
        }
    }
}
