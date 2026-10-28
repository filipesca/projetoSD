package barrel;

import common.Config;
import common.PageData;
import common.SearchResult;
import common.Tokenizer;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    }

    private final String name;
    private final Path dataFile;
    private IndexData data;

    // true quando há alterações ainda não gravadas em ficheiro
    private boolean dirty = false;

    public Barrel(String name, Path dataDir) throws IOException {
        this.name = name;
        Files.createDirectories(dataDir);
        this.dataFile = dataDir.resolve(name + ".ser");
        this.data = load();
    }

    @Override
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

    // Arranque: java barrel.Barrel <nome> [ficheiro.properties]
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java barrel.Barrel <name> [config.properties]");
            return;
        }
        try {
            // O nome vem nos argumentos para várias máquinas poderem usar o mesmo ficheiro de configuração
            String name = args[0];
            Config config = new Config(args.length > 1 ? args[1] : Config.DEFAULT_FILE);
            config.applyLocalHostname();

            // Carrega o índice guardado (recuperação depois de um crash) e ativa a gravação periódica
            Barrel barrel = new Barrel(name, Paths.get(config.get("barrel.data.dir")));
            barrel.startAutoSave(config.getInt("barrel.save.interval.ms"));

            // Ao terminar normalmente (ex.: Ctrl+C) grava o que ainda faltar
            Runtime.getRuntime().addShutdownHook(new Thread(barrel::save));

            // Exporta o Barrel como objeto remoto (o registo na Gateway é feito no passo 5)
            UnicastRemoteObject.exportObject(barrel, 0);

            System.out.println("Barrel " + name + " ready: " + barrel.getPageCount() + " pages, "
                    + barrel.getWordCount() + " words.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
