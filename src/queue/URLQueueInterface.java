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

    // Adiciona um URL ao fim da fila (ignorado se já tiver sido visto antes)
    void addUrl(String url) throws RemoteException;

    // Adiciona vários URLs de uma vez (evita uma chamada remota por cada link)
    void addUrls(List<String> urls) throws RemoteException;

    // URL introduzido por um utilizador: vai para o INÍCIO da fila, para ser visitado assim que possível.
    // Se já estiver na fila, passa para o início. Devolve false se já foi (ou está a ser) visitado.
    boolean addUrlPriority(String url) throws RemoteException;

    // Volta a pôr na fila um URL cujo download falhou (com limite de tentativas)
    void retryUrl(String url) throws RemoteException;

    // O downloader avisa que está livre para receber um novo URL
    void downloaderReady(DownloaderInterface downloader) throws RemoteException;

    // Usado pelos downloaders para verificar se a fila continua ativa (deteta reinícios da fila)
    void ping() throws RemoteException;
}
