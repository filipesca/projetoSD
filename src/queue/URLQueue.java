package queue;

import common.Config;
import downloader.DownloaderInterface;

import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
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

    // URLs à espera de serem visitados (take() bloqueia enquanto a fila estiver vazia)
    private final BlockingQueue<String> urls = new LinkedBlockingQueue<>();

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
        // add() do Set é atómico: só o primeiro a adicionar o URL o coloca na fila
        if (seen.add(url)) {
            urls.add(url);
        }
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
                // O downloader avariou: é descartado (não volta a estar livre) e o URL volta à fila
                System.out.println("Downloader unreachable, URL returned to queue: " + url);
                urls.add(url);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public static void main(String[] args) {
        try {
            // Lê a configuração e define o IP desta máquina para o RMI
            Config config = Config.load(args);
            config.applyLocalHostname();

            // Cria o servidor e exporta-o como um objeto remoto
            URLQueue server = new URLQueue(config.getList("queue.seeds"), config.getInt("queue.max.retries"));
            URLQueueInterface stub = (URLQueueInterface) UnicastRemoteObject.exportObject(server, 0);

            // Cria um RMI registry no porto e regista o stub (objeto remoto)
            Registry registry = LocateRegistry.createRegistry(config.getRmiPort());
            registry.rebind(config.get("queue.name"), stub);

            System.out.println("URLQueue RMI Server ready.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
