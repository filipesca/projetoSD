package gateway;

import barrel.BarrelInterface;
import common.SearchResult;
import common.Stats;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;

/*
    Interface remota da Gateway: única porta de entrada dos clientes.
    - Clientes: indexUrl, search, getBacklinks, getStats e subscrição das estatísticas (callback).
    - Barrels: registerBarrel (heartbeat). Downloaders: getBarrels (lista para o multicast).
    A Gateway não guarda dados do índice: responde aos clientes através dos Barrels.
*/

public interface GatewayInterface extends Remote {

    // Resultado de um pedido de indexação
    enum IndexResult {
        QUEUED,        // colocado no início da fila de URLs
        ALREADY_KNOWN, // já foi (ou está a ser) visitado
        INVALID,       // não é um URL http/https válido
        PENDING        // fila indisponível: a Gateway guarda o URL e reenvia-o quando a fila voltar
    }

    // ---- Barrels e Downloaders ----

    // Regista (ou renova o registo de) um Barrel, com o tamanho atual do índice. Os Barrels chamam
    // este método periodicamente (heartbeat): se deixarem de o chamar, são retirados da lista.
    void registerBarrel(String name, BarrelInterface barrel, int pages, int words) throws RemoteException;

    // Barrels ativos (nome -> referência remota), usados pelos Downloaders no multicast
    HashMap<String, BarrelInterface> getBarrels() throws RemoteException;

    // ---- Clientes ----

    // Pede a indexação de um URL introduzido por um utilizador (vai para o início da fila)
    IndexResult indexUrl(String url) throws RemoteException;

    // Pesquisa páginas com TODOS os termos (página "page" de 10 resultados, ordenados por relevância).
    // Escolhe um Barrel por desempenho; se falhar, repete o pedido noutro Barrel.
    List<SearchResult> search(String query, int page) throws RemoteException, NoBarrelAvailableException;

    // Páginas com ligação para o URL dado
    List<String> getBacklinks(String url) throws RemoteException, NoBarrelAvailableException;

    // Estatísticas atuais
    Stats getStats() throws RemoteException;

    // Subscrição das estatísticas: a Gateway chama listener.statsUpdated() sempre que mudam
    void subscribeStats(StatsListener listener) throws RemoteException;

    void unsubscribeStats(StatsListener listener) throws RemoteException;
}
