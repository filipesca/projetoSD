package downloader;

import queue.URLQueueInterface;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

public class Downloader implements DownloaderInterface {

    // Fila de URLs
    private URLQueueInterface queue;

    public Downloader(URLQueueInterface queue) {
        this.queue = queue;
    }

    @Override
    public void download(String url) {

        // Cria uma thread para não bloquear a fila de URLs
        new Thread(() -> {
            try {
                System.out.println("Downloading: " + url);

                // Liga-se ao URL e obtém o documento HTML
                Document doc = Jsoup.connect(url).get();
                String text = doc.body().text(); // texto para o índice

                // Extrair as hiperligações
                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String foundUrl = link.absUrl("href");

                    if (!foundUrl.isEmpty()) {
                        // Para não sobrecarregar os servidores
                        Thread.sleep(1000);

                        // Adiciona o novo URL descoberto de volta à fila para indexação recursiva
                        queue.addUrl(foundUrl);
                        System.out.println("Added new URL to queue: " + foundUrl);
                    }
                }

            } catch (Exception e) {
                System.out.println("Failed to process URL: " + url + " -> re-added to queue");
                try {
                    // Em caso de erro o URL volta para a fila
                    queue.addUrl(url);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }).start();
    }

    public static void main(String[] args) {
        try {
            // Liga ao registry do RMI e procura o objeto da fila
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            URLQueueInterface queue = (URLQueueInterface) registry.lookup("URLQueueInterface");

            // Cria o downloader and exporta-o como um objeto remoto
            Downloader downloader = new Downloader(queue);
            DownloaderInterface stub = (DownloaderInterface) UnicastRemoteObject.exportObject(downloader, 0);

            // Regista o stub (objeto remoto) no servidor
            queue.registerDownloader(stub);

            System.out.println("Downloader ready and registered for callbacks.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
