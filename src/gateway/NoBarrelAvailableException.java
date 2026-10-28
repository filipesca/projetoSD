package gateway;

/*
    Lançada pela Gateway quando não há nenhum Storage Barrel ativo para responder a um pedido
    (todos estão desligados ou falharam). É diferente de uma RemoteException: a Gateway está a
    funcionar, por isso o cliente deve mostrar a mensagem em vez de tentar outra Gateway.
*/

public class NoBarrelAvailableException extends Exception {

    private static final long serialVersionUID = 1L;

    public NoBarrelAvailableException(String message) {
        super(message);
    }
}
