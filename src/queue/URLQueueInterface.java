package queue;

import downloader.DownloaderInterface;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/*
    Interface remota da Fila de URLs.
    A fila envia os URLs aos downloaders por callback (modelo "push"):
    cada downloader avisa quando está livre e a fila chama download(url) nele.
*/

public interface URLQueueInterface extends Remote {

    // Adiciona um URL à fila (ignorado se já tiver sido visto antes)
    void addUrl(String url) throws RemoteException;

    // Adiciona vários URLs de uma vez (evita uma chamada remota por cada link)
    void addUrls(List<String> urls) throws RemoteException;

    // Volta a pôr na fila um URL cujo download falhou (com limite de tentativas)
    void retryUrl(String url) throws RemoteException;

    // O downloader avisa que está livre para receber um novo URL
    void downloaderReady(DownloaderInterface downloader) throws RemoteException;
}
