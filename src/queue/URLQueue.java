package queue;

import downloader.DownloaderInterface;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Timer;
import java.util.TimerTask;

public class URLQueue implements URLQueueInterface {
    // Fila para guardar URLs
    private final Queue<String> urls = new LinkedList<>();

    // Downloader que vai processar os URLs
    private DownloaderInterface downloader;

    public URLQueue() {
        // URLs iniciais
        urls.add("https://uc.pt");
        urls.add("https://openai.com");
        urls.add("https://wikipedia.org");

        // Temporizador: 10 segundos
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                notifyDownloader();
            }
        }, 0, 10_000);
    }

    // Tira um URL da fila, "synchronized" para não haver conflitos de concorrência
    public synchronized String takeUrl() {
        return urls.poll();
    }

    // Adiciona um URL da fila, "synchronized" para não haver conflitos de concorrência
    public synchronized void addUrl(String url) {
        urls.add(url);
    }

    // Regista o downloader recebido
    public synchronized void registerDownloader(DownloaderInterface downloader) {
        this.downloader = downloader;
        System.out.println("Downloader registered for callbacks.");
    }

    // Manda os URLs para o downloader
    private void notifyDownloader() {
        try {
            if (downloader != null) {
                String url = takeUrl();
                if (url != null) {
                    System.out.println("Notifying downloader of URL: " + url);

                    // Callback, o método downloader é invocado
                    downloader.download(url);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        try {
            // Cria o servidor e exporta-o como um objeto remoto
            URLQueue server = new URLQueue();
            URLQueueInterface stub = (URLQueueInterface) UnicastRemoteObject.exportObject(server, 0);

            // Cria um RMI registry no porto e regista o stub (objeto remoto)
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("URLQueueInterface", stub);

            System.out.println("URLQueue RMI Server ready.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
