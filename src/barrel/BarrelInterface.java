package barrel;

import common.IndexMessage;
import common.SearchResult;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;

/*
    Interface remota do Index Storage Barrel.
    - deliver é chamado pelos Downloaders através do reliable multicast.
    - search, getBacklinks e os tamanhos são chamados pela Gateway (passo 5).
    - getSnapshot e getLastSeqs são chamados pelos outros Barrels para se sincronizarem (passo 7).
*/

public interface BarrelInterface extends Remote {

    // Número de resultados por página de pesquisa
    int PAGE_SIZE = 10;

    // Recebe uma mensagem do reliable multicast. Mensagens repetidas (mesmo senderId e seq já
    // recebido) são ignoradas; o retorno normal da chamada serve de ACK ao Downloader.
    void deliver(IndexMessage message) throws RemoteException;

    // Páginas que contêm TODOS os termos, ordenadas por links recebidos; devolve a página "page" (0, 1, ...) de PAGE_SIZE resultados
    List<SearchResult> search(String query, int page) throws RemoteException;

    // URLs das páginas que têm uma ligação para o URL dado
    List<String> getBacklinks(String url) throws RemoteException;

    // Número de palavras distintas no índice invertido
    int getWordCount() throws RemoteException;

    // Número de páginas indexadas
    int getPageCount() throws RemoteException;

    // Cópia completa do índice (serializada), pedida por outro Barrel para se sincronizar
    // (Barrel novo, reiniciado depois de um crash, ou que esteve fora da lista da Gateway)
    byte[] getSnapshot() throws RemoteException;

    // Último número de sequência entregue de cada Downloader (senderId -> seq). Os outros Barrels
    // comparam-no com o seu para detetarem mensagens que não receberam.
    HashMap<String, Long> getLastSeqs() throws RemoteException;
}
