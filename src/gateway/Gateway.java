package gateway;

import barrel.BarrelInterface;
import common.Config;

import java.nio.file.NoSuchFileException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
    Gateway RMI: porta de entrada do sistema.
    Neste passo guarda a lista de Barrels ativos:
    - cada Barrel regista-se e renova o registo periodicamente (heartbeat), como uma "lease";
    - uma thread retira os Barrels que deixaram de renovar o registo dentro do prazo (avariados).
    A Gateway cria o seu próprio RMI registry, para não depender do processo da fila.
*/

public class Gateway implements GatewayInterface {

    // Registo de um Barrel: referência remota e instante do último heartbeat
    private static class BarrelEntry {
        final BarrelInterface stub;
        final long lastHeartbeat;

        BarrelEntry(BarrelInterface stub, long lastHeartbeat) {
            this.stub = stub;
            this.lastHeartbeat = lastHeartbeat;
        }
    }

    // Barrels registados (thread-safe: os registos chegam em paralelo por threads do RMI)
    private final Map<String, BarrelEntry> barrels = new ConcurrentHashMap<>();

    // Tempo sem heartbeat até um Barrel ser retirado da lista
    private final long barrelTimeoutMs;

    public Gateway(long barrelTimeoutMs) {
        this.barrelTimeoutMs = barrelTimeoutMs;
    }

    @Override
    public void registerBarrel(String name, BarrelInterface barrel) {
        BarrelEntry old = barrels.put(name, new BarrelEntry(barrel, System.currentTimeMillis()));
        // Só mostra a mensagem quando o Barrel é novo ou reiniciou (nova referência remota)
        if (old == null || !old.stub.equals(barrel)) {
            System.out.println("Barrel " + name + " registered (" + barrels.size() + " active).");
        }
    }

    @Override
    public HashMap<String, BarrelInterface> getBarrels() {
        HashMap<String, BarrelInterface> active = new HashMap<>();
        for (Map.Entry<String, BarrelEntry> e : barrels.entrySet()) {
            active.put(e.getKey(), e.getValue().stub);
        }
        return active;
    }

    // Retira os Barrels sem heartbeat dentro do prazo (avariados ou desligados)
    public void removeExpiredBarrels() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, BarrelEntry> e : barrels.entrySet()) {
            BarrelEntry entry = e.getValue();
            // remove(chave, valor) só remove se o registo não tiver sido renovado entretanto
            if (now - entry.lastHeartbeat > barrelTimeoutMs && barrels.remove(e.getKey(), entry)) {
                System.out.println("Barrel " + e.getKey() + " removed: no heartbeat for more than "
                        + barrelTimeoutMs + " ms (" + barrels.size() + " active).");
            }
        }
    }

    // Thread que verifica os prazos periodicamente
    private void expiryLoop() {
        while (true) {
            try {
                Thread.sleep(Math.max(100, barrelTimeoutMs / 4));
            } catch (InterruptedException e) {
                return;
            }
            removeExpiredBarrels();
        }
    }

    public static void main(String[] args) {
        int port = -1;
        try {
            // Lê a configuração e define o IP desta máquina para o RMI
            Config config = Config.load(args);
            config.applyLocalHostname();
            port = config.getInt("gateway.port");

            // Cria a Gateway e exporta-a como objeto remoto
            Gateway gateway = new Gateway(config.getInt("gateway.barrel.timeout.ms"));
            GatewayInterface stub = (GatewayInterface) UnicastRemoteObject.exportObject(gateway, 0);

            // Registry próprio da Gateway (independente do registry da fila)
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind(config.get("gateway.name"), stub);

            Thread expiry = new Thread(gateway::expiryLoop, "barrel-expiry");
            expiry.setDaemon(true);
            expiry.start();

            System.out.println("Gateway RMI Server ready.");

            // Erros previsíveis: mensagem clara em vez de stack trace
        } catch (NoSuchFileException e) {
            Config.exitWithError("configuration file not found: " + e.getFile());
        } catch (IllegalArgumentException e) {
            Config.exitWithError("invalid configuration: " + e.getMessage());
        } catch (ExportException e) {
            Config.exitWithError("could not use port " + port + " (is another Gateway already running?)");
        } catch (Exception e) {
            Config.exitWithError("Gateway could not start: " + e);
        }
    }
}
