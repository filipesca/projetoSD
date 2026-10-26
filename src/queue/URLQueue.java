package queue;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.LinkedList;
import java.util.Queue;

public class URLQueue implements URLQueueInterface {
    private final Queue<String> urls;

    public URLQueue() {
        urls = new LinkedList<>();
        urls.add("https://example.com");
        urls.add("https://openai.com");
        urls.add("https://wikipedia.org");
    }

    public synchronized String takeUrl() {
        return urls.poll();
    }

    public synchronized void addUrl(String url) {
        urls.add(url);
    }

    public static void main(String[] args) {
        try {
            URLQueue server = new URLQueue();
            URLQueueInterface stub = (URLQueueInterface) UnicastRemoteObject.exportObject(server, 0);
            Registry registry = LocateRegistry.createRegistry(1099);
            registry.rebind("URLQueueInterface", stub);
            System.out.println("URL Queue RMI Server ready.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
