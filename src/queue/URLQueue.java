package queue;

import common.Config;
import common.UrlNormalizer;
import downloader.DownloaderInterface;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/*
    Fila de URLs (programa separado).
    - Guarda os URLs por visitar e o conjunto de URLs já vistos, para que cada URL
      seja entregue a um único downloader.
    - Guarda os downloaders livres; uma thread "dispatcher" junta um URL a um downloader livre.
    - Guarda o URL em curso de cada downloader. Uma thread faz ping periódico a esses downloaders:
      se um morrer a meio de um download, o URL volta ao início da fila (não se perde).
    - O estado (URLs por visitar, vistos, tentativas e URLs em curso) é gravado num ficheiro de
      objetos periodicamente e ao terminar, e é carregado ao arrancar: a fila recupera de um crash.
    Os URLs, os vistos, as tentativas e os URLs em curso são protegidos pelo lock da fila (this):
    o RMI chama os métodos remotos em paralelo, e a cópia gravada em ficheiro tem de ser coerente
    (um URL nunca pode estar "entre" a fila e os URLs em curso no momento da cópia).
*/

public class URLQueue implements URLQueueInterface {

    // URLs à espera de serem visitados. É uma deque para os URLs dos utilizadores (e os que voltam
    // por avaria de um downloader) poderem entrar pelo início.
    private final ArrayDeque<String> urls = new ArrayDeque<>();

    // URLs que já entraram na fila alguma vez (evita visitar o mesmo URL duas vezes)
    private final HashSet<String> seen = new HashSet<>();

    // Número de vezes que cada URL já voltou à fila por falha no download
    private final HashMap<String, Integer> retries = new HashMap<>();

    // URL em curso de cada downloader ocupado (sai daqui quando o downloader volta a estar livre).
    // equals() dos stubs RMI compara o objeto remoto, por isso o stub serve de chave.
    private final HashMap<DownloaderInterface, String> inProgress = new HashMap<>();

    // Downloaders livres (take() bloqueia enquanto não houver nenhum livre)
    private final BlockingQueue<DownloaderInterface> availableDownloaders = new LinkedBlockingQueue<>();

    // Número máximo de novas tentativas para um URL que falhou
    private final int maxRetries;

    // Estado gravado em ficheiro (null = sem persistência, usado nos testes) e intervalo de gravação
    private final Path dataFile;
    private final long saveIntervalMs;

    // true quando o estado mudou e ainda não foi gravado (protegido pelo lock da fila)
    private boolean dirty = false;

    // Lock só da gravação em ficheiro: duas gravações nunca escrevem ao mesmo tempo
    private final Object saveLock = new Object();

    // Estado da fila gravado em ficheiro. Os URLs em curso são gravados no início da lista de URLs
    // por visitar: depois de reiniciar, a fila não sabe se esses downloads terminaram.
    private static class QueueState implements Serializable {
        private static final long serialVersionUID = 1L;

        final ArrayList<String> urls;
        final HashSet<String> seen;
        final HashMap<String, Integer> retries;

        QueueState(ArrayList<String> urls, HashSet<String> seen, HashMap<String, Integer> retries) {
            this.urls = urls;
            this.seen = seen;
            this.retries = retries;
        }
    }

    // Fila sem persistência nem verificação dos downloaders (testes)
    public URLQueue(List<String> seeds, int maxRetries) {
        this(seeds, maxRetries, null, 0, 0);
    }

    // Carrega o estado gravado (se existir), junta os URLs iniciais e inicia as threads internas.
    // checkMs = intervalo do ping aos downloaders com URL em curso (0 = sem verificação).
    public URLQueue(List<String> seeds, int maxRetries, Path dataFile, long saveIntervalMs, long checkMs) {
        this.maxRetries = maxRetries;
        this.dataFile = dataFile;
        this.saveIntervalMs = saveIntervalMs;
        if (dataFile != null) {
            load();
        }

        // URLs iniciais (lidos do ficheiro de configuração); os que já foram vistos são ignorados
        for (String url : seeds) {
            addUrl(url);
        }

        // Thread que distribui os URLs pelos downloaders livres
        startDaemon(this::dispatchLoop, "dispatcher");

        // Thread que verifica se os downloaders com URL em curso continuam vivos
        if (checkMs > 0) {
            startDaemon(() -> checkLoop(checkMs), "downloader-check");
        }

        // Thread que grava o estado periodicamente
        if (dataFile != null) {
            startDaemon(this::saveLoop, "queue-saver");
        }
    }

    private static void startDaemon(Runnable task, String name) {
        Thread t = new Thread(task, name);
        t.setDaemon(true);
        t.start();
    }

    @Override
    public synchronized void addUrl(String url) {
        // URLs equivalentes (ex.: com e sem "/" final) contam como o mesmo; URLs inválidos são ignorados
        String normalized = UrlNormalizer.normalize(url);
        if (normalized != null && seen.add(normalized)) {
            urls.addLast(normalized);
            dirty = true;
            notifyAll(); // acorda o dispatcher, se estiver à espera de URLs
        }
    }

    @Override
    public synchronized boolean addUrlPriority(String url) {
        String normalized = UrlNormalizer.normalize(url);
        if (normalized == null) {
            return false;
        }
        // URL novo, ou ainda à espera na fila: entra/passa para o início.
        // Se já foi entregue a um downloader, não é visitado outra vez.
        if (seen.add(normalized) || urls.remove(normalized)) {
            urls.addFirst(normalized);
            dirty = true;
            notifyAll();
            System.out.println("Priority URL added to the front of the queue: " + normalized);
            return true;
        }
        return false;
    }

    @Override
    public synchronized void addUrls(List<String> newUrls) {
        for (String url : newUrls) {
            addUrl(url);
        }
    }

    @Override
    public synchronized void retryUrl(String url) {
        int attempts = retries.merge(url, 1, Integer::sum);
        dirty = true;
        if (attempts <= maxRetries) {
            System.out.println("Retry " + attempts + "/" + maxRetries + " for URL: " + url);
            urls.addLast(url);
            notifyAll();
        } else {
            System.out.println("Giving up on URL after " + maxRetries + " retries: " + url);
        }
    }

    @Override
    public void downloaderReady(DownloaderInterface downloader) {
        // O downloader só fica livre depois de enviar os links (ou de pedir retryUrl): o URL que tinha
        // em curso terminou e deixa de ser vigiado
        synchronized (this) {
            if (inProgress.remove(downloader) != null) {
                dirty = true;
            }
        }
        availableDownloaders.add(downloader);
    }

    @Override
    public void ping() {
        // Não faz nada: se a chamada chegar aqui, a fila está ativa
    }

    // Ciclo do dispatcher: espera por um downloader livre e por um URL e entrega-o
    private void dispatchLoop() {
        while (true) {
            DownloaderInterface downloader;
            String url;
            int size;
            try {
                downloader = availableDownloaders.take();
                // Tirar o URL da fila e marcá-lo como em curso é uma só operação (sob o lock): uma
                // gravação nunca vê o URL fora das duas listas
                synchronized (this) {
                    while (urls.isEmpty()) {
                        wait();
                    }
                    url = urls.pollFirst();
                    inProgress.put(downloader, url);
                    dirty = true;
                    size = urls.size();
                }
            } catch (InterruptedException e) {
                return;
            }
            try {
                System.out.println("Sending URL to downloader: " + url + " (queue size: " + size + ")");
                // Callback: o download corre numa thread do downloader, por isso a chamada retorna logo
                downloader.download(url);

            } catch (RemoteException e) {
                // O downloader avariou: é descartado (não volta a estar livre) e o URL volta ao
                // INÍCIO da fila (não perde a vez, o que importa sobretudo para URLs de utilizadores)
                System.out.println("Downloader unreachable, URL returned to queue: " + url);
                returnToQueue(downloader, url);
            } catch (RuntimeException e) {
                // Erro inesperado: o dispatcher não pode terminar, senão a fila deixava de distribuir URLs
                System.out.println("Unexpected error while dispatching " + url + ": " + e);
                returnToQueue(downloader, url);
            }
        }
    }

    // Volta a pôr no início da fila o URL em curso de um downloader avariado. Só se ainda for o URL
    // dele: se entretanto o downloader ficou livre (e talvez já tenha outro URL), não faz nada.
    private synchronized boolean returnToQueue(DownloaderInterface downloader, String url) {
        if (!inProgress.remove(downloader, url)) {
            return false;
        }
        urls.addFirst(url);
        dirty = true;
        notifyAll();
        return true;
    }

    // Faz ping periódico aos downloaders com URL em curso. Um downloader que não responde morreu a
    // meio do download: o URL volta ao início da fila. Se for só uma falha de rede passageira, o URL
    // é descarregado duas vezes, o que não estraga o índice (os Barrels juntam conjuntos).
    private void checkLoop(long checkMs) {
        while (true) {
            try {
                Thread.sleep(checkMs);
            } catch (InterruptedException e) {
                return;
            }
            Map<DownloaderInterface, String> busy;
            synchronized (this) {
                busy = new HashMap<>(inProgress);
            }
            // Os pings são feitos fora do lock (podem demorar até rmi.timeout.ms)
            for (Map.Entry<DownloaderInterface, String> e : busy.entrySet()) {
                try {
                    e.getKey().ping();
                } catch (RemoteException ex) {
                    if (returnToQueue(e.getKey(), e.getValue())) {
                        System.out.println("Downloader died while downloading, URL returned to queue: " + e.getValue());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Persistência

    // Grava o estado, se tiver mudado. A cópia é feita sob o lock da fila (rápida, e coerente); a
    // escrita no disco é feita fora dele, para não atrasar os downloaders.
    // Escreve num ficheiro temporário e substitui o anterior (um crash a meio da escrita nunca
    // corrompe o ficheiro).
    public void save() {
        if (dataFile == null) {
            return;
        }
        synchronized (saveLock) {
            QueueState state;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                ArrayList<String> pending = new ArrayList<>(inProgress.values());
                pending.addAll(urls);
                state = new QueueState(pending, new HashSet<>(seen), new HashMap<>(retries));
                dirty = false;
            }
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
                System.out.println("Could not save URLQueue state: " + e.getMessage());
                synchronized (this) {
                    dirty = true; // tenta de novo na próxima gravação periódica
                }
            }
        }
    }

    // Lê o estado gravado; se não existir ou estiver corrompido, começa vazia (só com os URLs iniciais)
    private synchronized void load() {
        if (!Files.exists(dataFile)) {
            System.out.println("No saved URLQueue state found, starting empty: " + dataFile);
            return;
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(dataFile))) {
            QueueState state = (QueueState) in.readObject();
            urls.addAll(state.urls);
            seen.addAll(state.seen);
            retries.putAll(state.retries);
            System.out.println("URLQueue state loaded from " + dataFile + ": " + state.urls.size()
                    + " URLs to visit, " + state.seen.size() + " URLs seen.");
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            System.out.println("Saved URLQueue state is unreadable, starting empty: " + dataFile + " (" + e + ")");
        }
    }

    // Grava o estado periodicamente, se tiver mudado
    private void saveLoop() {
        while (true) {
            try {
                Thread.sleep(saveIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
            save();
        }
    }

    public static void main(String[] args) {
        int port = -1;
        try {
            // Lê a configuração e define o IP desta máquina para o RMI
            Config config = Config.load(args);
            config.applyLocalHostname();
            port = config.getRmiPort();

            // Cria o servidor (carrega o estado gravado, se existir) e exporta-o como um objeto remoto
            URLQueue server = new URLQueue(config.getList("queue.seeds"), config.getInt("queue.max.retries"),
                    Paths.get(config.get("queue.data.file")), config.getInt("queue.save.interval.ms"),
                    config.getInt("queue.downloader.check.ms"));
            URLQueueInterface stub = (URLQueueInterface) UnicastRemoteObject.exportObject(server, 0);

            // Cria um RMI registry no porto e regista o stub (objeto remoto)
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind(config.get("queue.name"), stub);

            // Ao terminar normalmente (ex.: Ctrl+C) grava o que ainda faltar. Só depois de o registry
            // arrancar: uma segunda fila que falha no porto não substitui o ficheiro da primeira.
            Runtime.getRuntime().addShutdownHook(new Thread(server::save));
            System.out.println("URLQueue RMI Server ready.");

            // Erros previsíveis: mensagem clara em vez de stack trace
        } catch (NoSuchFileException e) {
            Config.exitWithError("configuration file not found: " + e.getFile());
        } catch (IllegalArgumentException e) {
            Config.exitWithError("invalid configuration: " + e.getMessage());
        } catch (ExportException e) {
            Config.exitWithError("could not use port " + port + " (is another URLQueue already running?)");
        } catch (Exception e) {
            Config.exitWithError("URLQueue could not start: " + e);
        }
    }
}
