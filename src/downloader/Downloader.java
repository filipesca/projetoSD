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

    private URLQueueInterface queue;

    public Downloader(URLQueueInterface queue) {
        this.queue = queue;
    }

    @Override
    public void download(String url) {
        new Thread(() -> {
            try {
                System.out.println("Downloading: " + url);
                Document doc = Jsoup.connect(url).get();
                String text = doc.body().text();

                // Extract links and add back to queue with 1-second delay
                Elements links = doc.select("a[href]");
                for (Element link : links) {
                    String foundUrl = link.absUrl("href");
                    if (!foundUrl.isEmpty()) {
                        Thread.sleep(1000);
                        queue.addUrl(foundUrl);
                        System.out.println("Added new URL to queue: " + foundUrl);
                    }
                }

            } catch (Exception e) {
                System.out.println("Failed to process URL: " + url + " -> re-added to queue");
                try {
                    queue.addUrl(url);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }).start();
    }

    public static void main(String[] args) {
        try {
            // Connect to the RMI registry
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            URLQueueInterface queue = (URLQueueInterface) registry.lookup("URLQueueInterface");

            // Create downloader and export it as a remote object
            Downloader downloader = new Downloader(queue);
            DownloaderInterface stub = (DownloaderInterface) UnicastRemoteObject.exportObject(downloader, 0);

            // Register the stub (remote object) with the queue
            queue.registerDownloader(stub);

            System.out.println("Downloader ready and registered for callbacks.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
