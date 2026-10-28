package queue;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface URLQueueInterface extends Remote {
    String takeUrl() throws RemoteException;
    void addUrl(String url) throws RemoteException;
    void registerDownloader(downloader.DownloaderInterface downloader) throws RemoteException;
}
