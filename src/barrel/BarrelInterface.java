package barrel;

import common.PageData;
import common.SearchResult;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/*
    Interface remota do Index Storage Barrel.
    - storePage é chamado pelos Downloaders (no passo 4, através do reliable multicast).
    - search, getBacklinks e os tamanhos são chamados pela Gateway.
*/

public interface BarrelInterface extends Remote {

    // Número de resultados por página de pesquisa
    int PAGE_SIZE = 10;

    // Guarda no índice a informação de uma página (idempotente: guardar duas vezes não altera nada)
    void storePage(PageData page) throws RemoteException;

    // Páginas que contêm TODOS os termos, ordenadas por links recebidos; devolve a página "page" (0, 1, ...) de PAGE_SIZE resultados
    List<SearchResult> search(String query, int page) throws RemoteException;

    // URLs das páginas que têm uma ligação para o URL dado
    List<String> getBacklinks(String url) throws RemoteException;

    // Número de palavras distintas no índice invertido
    int getWordCount() throws RemoteException;

    // Número de páginas indexadas
    int getPageCount() throws RemoteException;
}
