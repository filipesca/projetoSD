package queue;

import common.Config;
import common.UrlNormalizer;
import downloader.DownloaderInterface;

import java.nio.file.NoSuchFileException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

/*
    Fila de URLs (programa separado).
    - Guarda os URLs por visitar e o conjunto de URLs já vistos, para que cada URL
      seja entregue a um único downloader.
    - Guarda os downloaders livres; uma thread "dispatcher" junta um URL a um downloader livre.
    Usa estruturas de java.util.concurrent (thread-safe) porque os métodos remotos
    são chamados em paralelo por várias threads do RMI.
*/

public class URLQueue implements URLQueueInterface {

    // URLs à espera de serem visitados (take() bloqueia enquanto a fila estiver vazia).
    // É uma deque para os URLs dos utilizadores poderem entrar pelo início (addFirst).
    private final BlockingDeque<String> urls = new LinkedBlockingDeque<>();

    // URLs que já entraram na fila alguma vez (evita visitar o mesmo URL duas vezes)
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    // Número de vezes que cada URL já voltou à fila por falha no download
    private final Map<String, Integer> retries = new ConcurrentHashMap<>();

    // Downloaders livres (take() bloqueia enquanto não houver nenhum livre)
    private final BlockingQueue<DownloaderInterface> availableDownloaders = new LinkedBlockingQueue<>();

    // Número máximo de novas tentativas para um URL que falhou
    private final int maxRetries;

    public URLQueue(List<String> seeds, int maxRetries) {
        this.maxRetries = maxRetries;

        // URLs iniciais (lidos do ficheiro de configuração)
        for (String url : seeds) {
            addUrl(url);
        }

        // Thread que distribui os URLs pelos downloaders livres
        Thread dispatcher = new Thread(this::dispatchLoop, "dispatcher");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    @Override
    public void addUrl(String url) {
        // URLs equivalentes (ex.: com e sem "/" final) contam como o mesmo; URLs inválidos são ignorados
        String normalized = UrlNormalizer.normalize(url);
        // add() do Set é atómico: só o primeiro a adicionar o URL o coloca na fila
        if (normalized != null && seen.add(normalized)) {
            urls.add(normalized);
        }
    }

    @Override
    public boolean addUrlPriority(String url) {
        String normalized = UrlNormalizer.normalize(url);
        if (normalized == null) {
            return false;
        }
        // URL novo, ou ainda à espera na fila (remove() é atómico): entra/passa para o início.
        // Se já foi entregue a um downloader, não é visitado outra vez.
        if (seen.add(normalized) || urls.remove(normalized)) {
            urls.addFirst(normalized);
            System.out.println("Priority URL added to the front of the queue: " + normalized);
            return true;
        }
        return false;
    }

    @Override
    public void addUrls(List<String> newUrls) {
        for (String url : newUrls) {
            addUrl(url);
        }
    }

    @Override
    public void retryUrl(String url) {
        // merge incrementa o contador de forma atómica
        int attempts = retries.merge(url, 1, Integer::sum);
        if (attempts <= maxRetries) {
            System.out.println("Retry " + attempts + "/" + maxRetries + " for URL: " + url);
            urls.add(url);
        } else {
            System.out.println("Giving up on URL after " + maxRetries + " retries: " + url);
        }
    }

    @Override
    public void downloaderReady(DownloaderInterface downloader) {
        availableDownloaders.add(downloader);
    }

    @Override
    public void ping() {
        // Não faz nada: se a chamada chegar aqui, a fila está ativa
    }

    // Ciclo do dispatcher: espera por um downloader livre e por um URL e entrega-o
    private void dispatchLoop() {
        while (true) {
            String url = null;
            try {
                DownloaderInterface downloader = availableDownloaders.take();
                url = urls.take();
                System.out.println("Sending URL to downloader: " + url + " (queue size: " + urls.size() + ")");

                // Callback: o download corre numa thread do downloader, por isso a chamada retorna logo
                downloader.download(url);

            } catch (RemoteException e) {
                // O downloader avariou: é descartado (não volta a estar livre) e o URL volta ao
                // INÍCIO da fila (não perde a vez, o que importa sobretudo para URLs de utilizadores)
                System.out.println("Downloader unreachable, URL returned to queue: " + url);
                urls.addFirst(url);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                // Erro inesperado: o dispatcher não pode terminar, senão a fila deixava de distribuir URLs
                System.out.println("Unexpected error while dispatching " + url + ": " + e);
                if (url != null) {
                    urls.addFirst(url);
                }
            }
        }
    }

    public static void main(String[] args) {
        int port = -1;
        try {
            // Lê a configuração e define o IP desta máquina para o RMI
            Config config = Config.load(args);
            config.applyLocalHostname();
            port = config.getRmiPort();

            // Cria o servidor e exporta-o como um objeto remoto
            URLQueue server = new URLQueue(config.getList("queue.seeds"), config.getInt("queue.max.retries"));
            URLQueueInterface stub = (URLQueueInterface) UnicastRemoteObject.exportObject(server, 0);

            // Cria um RMI registry no porto e regista o stub (objeto remoto)
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind(config.get("queue.name"), stub);

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
