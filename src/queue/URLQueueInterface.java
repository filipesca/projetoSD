package queue;

import java.rmi.Remote;
import java.rmi.RemoteException;

/*
    Interface remota da Fila de URLs.
*/

public interface URLQueueInterface extends Remote {
    // Retira o próximo URL da fila
    String takeUrl() throws RemoteException;

    // Adiciona um URL descoberto à fila
    void addUrl(String url) throws RemoteException;

    // Regista um downloader para enviar os URLs
    void registerDownloader(downloader.DownloaderInterface downloader) throws RemoteException;
}
