package downloader;

import java.rmi.Remote;
import java.rmi.RemoteException;

public interface DownloaderInterface extends Remote {
    void download(String url) throws RemoteException;
}
