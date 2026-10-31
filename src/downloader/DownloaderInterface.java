package downloader;

import java.rmi.Remote;
import java.rmi.RemoteException;

/*
    Interface do Downloader
*/

public interface DownloaderInterface extends Remote {

    // Inicia o processo de descarregamento e análise de um URL
    void download(String url) throws RemoteException;

    // Usado pela fila para verificar se o downloader continua vivo enquanto tem um URL em curso
    // (se a chamada falhar, a fila volta a pôr esse URL na fila)
    void ping() throws RemoteException;
}
