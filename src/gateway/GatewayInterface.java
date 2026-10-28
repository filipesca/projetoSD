package gateway;

import barrel.BarrelInterface;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.HashMap;

/*
    Interface remota da Gateway.
    Neste passo só trata do registo dos Barrels (os Barrels de outras máquinas não podem fazer
    bind no registry desta, por isso registam-se através de um método remoto).
    A pesquisa, o balanceamento e as estatísticas são acrescentados no passo 5.
*/

public interface GatewayInterface extends Remote {

    // Regista (ou renova o registo de) um Barrel. Os Barrels chamam este método periodicamente
    // (heartbeat): se deixarem de o chamar, a Gateway considera-os avariados e retira-os da lista.
    void registerBarrel(String name, BarrelInterface barrel) throws RemoteException;

    // Barrels ativos (nome -> referência remota), usados pelos Downloaders no multicast
    HashMap<String, BarrelInterface> getBarrels() throws RemoteException;
}
