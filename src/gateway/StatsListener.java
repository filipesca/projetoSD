package gateway;

import common.Stats;

import java.rmi.Remote;
import java.rmi.RemoteException;

/*
    Callback das estatísticas: implementado pelos clientes e chamado pela Gateway sempre que
    as estatísticas mudam (o cliente não pergunta periodicamente: não há polling).
*/

public interface StatsListener extends Remote {

    void statsUpdated(Stats stats) throws RemoteException;
}
