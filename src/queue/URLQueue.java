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
    private final Queue<String> urls = new LinkedList<>();
    private DownloaderInterface downloader;

    public URLQueue() {
        urls.add("https://uc.pt");
        urls.add("https://openai.com");
        urls.add("https://wikipedia.org");

        // Timer: notify downloader every 10 seconds
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                notifyDownloader();
            }
        }, 0, 10_000);
    }

    public synchronized String takeUrl() {
        return urls.poll();
    }

    public synchronized void addUrl(String url) {
        urls.add(url);
    }

    public synchronized void registerDownloader(DownloaderInterface downloader) {
        this.downloader = downloader;
        System.out.println("Downloader registered for callbacks.");
    }

    private void notifyDownloader() {
        try {
            if (downloader != null) {
                String url = takeUrl();
                if (url != null) {
                    System.out.println("Notifying downloader of URL: " + url);
                    downloader.download(url);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        try {
            URLQueue server = new URLQueue();
            URLQueueInterface stub = (URLQueueInterface) UnicastRemoteObject.exportObject(server, 0);
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("URLQueueInterface", stub);
            System.out.println("URLQueue RMI Server ready.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
