package downloader;

import java.rmi.Remote;
import java.rmi.RemoteException;

/*
    Interface do Downloader
*/

public interface DownloaderInterface extends Remote {

    // Inicia o processo de descarregamento e análise de um URL
    void download(String url) throws RemoteException;
}
