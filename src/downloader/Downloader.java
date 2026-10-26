package downloader;

import queue.URLQueueInterface;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;

public class Downloader implements DownloaderInterface {

    public Downloader() {}

    public void download(String url) {
        System.out.println("Downloading: " + url);
        // Optionally: fetch content with Jsoup or HTTP client
    }

    public static void main(String[] args) {
        try {
            Registry registry = LocateRegistry.getRegistry("localhost", 1099);
            URLQueueInterface queue = (URLQueueInterface) registry.lookup("URLQueueInterface");

            Downloader downloader = new Downloader();

            while (true) {
                String url = queue.takeUrl();
                if (url == null) {
                    System.out.println("No more URLs, exiting...");
                    break;
                }
                downloader.download(url);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
