package downloader;

import barrel.BarrelInterface;
import common.IndexMessage;
import common.PageData;

import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/*
    Reliable multicast sobre RMI: envia cada página indexada a TODOS os Barrels.

    Algoritmo (baseado em ACKs e retransmissão, t08):
    - Cada mensagem leva (senderId, seq): senderId identifica este Downloader e seq é crescente.
    - Para cada Barrel há uma fila FIFO de mensagens por entregar e uma thread de envio
      (os Barrels são servidos em paralelo; um Barrel lento ou avariado não atrasa os outros).
    - O retorno normal da chamada remota deliver() funciona como ACK: só então a mensagem
      sai da fila. Com RemoteException (omissão/avaria) a mensagem volta ao início da fila e é
      retransmitida mais tarde, mantendo a ordem FIFO.
    - O Barrel descarta mensagens com seq já recebido (integridade: cada mensagem é entregue
      uma só vez, mesmo que um ACK se perca e a mensagem seja retransmitida).
    - Acordo: enquanto o Barrel estiver na lista da Gateway (ou continuar a responder), todas as
      mensagens lhe acabam por ser entregues. Se a Gateway o retirar (sem heartbeat há demasiado
      tempo) e as entregas estiverem a falhar, as mensagens pendentes para ele são descartadas: quando
      voltar, o Barrel recebe da Gateway a indicação de registo novo e copia o que lhe falta de outro
      Barrel ativo (sincronização por snapshot, ver Barrel.synchronize).
*/

public class ReliableMulticast {

    // Identificador único deste Downloader (novo a cada arranque)
    private final String senderId = UUID.randomUUID().toString();

    // Intervalo entre retransmissões para um Barrel que não respondeu
    private final long retryMs;

    // Próximo número de sequência e canais por Barrel (protegidos pelo monitor deste objeto)
    private long nextSeq = 1;
    private final Map<String, Channel> channels = new HashMap<>();

    public ReliableMulticast(long retryMs) {
        this.retryMs = retryMs;
    }

    // Envia a página a todos os Barrels conhecidos. Se ainda não houver nenhum, espera
    // (wait em ciclo, t04) em vez de perder a página.
    public synchronized void multicast(PageData page) throws InterruptedException {
        boolean warned = false;
        while (channels.isEmpty()) {
            if (!warned) {
                System.out.println("No active Barrels: waiting before sending " + page.getUrl());
                warned = true;
            }
            wait();
        }
        IndexMessage message = new IndexMessage(senderId, nextSeq++, page);
        for (Channel channel : channels.values()) {
            channel.pending.add(message);
        }
    }

    // Atualiza a lista de Barrels (obtida da Gateway): cria canais para Barrels novos, atualiza a
    // referência remota dos que reiniciaram e remove os que deixaram de estar ativos.
    // Um Barrel só é removido se não estiver na lista E as entregas a ele estiverem a falhar: logo
    // depois de a Gateway reiniciar, a lista pode ainda não ter Barrels que estão a funcionar (ainda
    // não renovaram o registo) e esses têm de continuar a receber as mensagens.
    public synchronized void updateBarrels(Map<String, BarrelInterface> barrels) {
        Iterator<Map.Entry<String, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Channel> entry = it.next();
            if (!barrels.containsKey(entry.getKey()) && entry.getValue().failing) {
                Channel removed = entry.getValue();
                removed.stop();
                it.remove();
                System.out.println("Barrel " + entry.getKey() + " is no longer active: discarding "
                        + removed.pending.size() + " pending messages (it recovers its state when it restarts).");
            }
        }
        for (Map.Entry<String, BarrelInterface> entry : barrels.entrySet()) {
            Channel channel = channels.get(entry.getKey());
            if (channel == null) {
                channel = new Channel(entry.getKey(), entry.getValue());
                channels.put(entry.getKey(), channel);
                channel.start();
                System.out.println("Multicast: sending to Barrel " + entry.getKey());
            } else {
                // O Barrel pode ter reiniciado com uma nova referência remota
                channel.stub = entry.getValue();
            }
        }
        if (!channels.isEmpty()) {
            notifyAll(); // acorda as threads que esperavam por um Barrel em multicast()
        }
    }

    // Número de mensagens por entregar a um Barrel (-1 se o Barrel não for conhecido)
    public synchronized int pendingFor(String barrelName) {
        Channel channel = channels.get(barrelName);
        return channel == null ? -1 : channel.pending.size() + (channel.sending ? 1 : 0);
    }

    // Canal para um Barrel: fila FIFO de mensagens por entregar e thread que as envia por ordem
    private class Channel implements Runnable {
        final String name;
        volatile BarrelInterface stub;
        final BlockingDeque<IndexMessage> pending = new LinkedBlockingDeque<>();
        volatile boolean sending = false; // há uma mensagem retirada da fila e ainda sem ACK
        volatile boolean failing = false; // a última tentativa de entrega falhou
        private Thread thread;
        private volatile boolean running = true;

        Channel(String name, BarrelInterface stub) {
            this.name = name;
            this.stub = stub;
        }

        void start() {
            thread = new Thread(this, "multicast-" + name);
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            running = false;
            thread.interrupt();
        }

        @Override
        public void run() {
            while (running) {
                IndexMessage message;
                try {
                    message = pending.takeFirst();
                } catch (InterruptedException e) {
                    return;
                }
                sending = true;
                try {
                    stub.deliver(message); // retorno normal = ACK
                    sending = false;
                    if (failing) {
                        System.out.println("Barrel " + name + " reachable again: retransmitting pending messages.");
                        failing = false;
                    }
                } catch (RemoteException e) {
                    // Omissão ou avaria: a mensagem volta ao início da fila (ordem FIFO) e é retransmitida
                    pending.addFirst(message);
                    sending = false;
                    if (!failing) {
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        System.out.println("Barrel " + name + " unreachable (" + cause.getClass().getSimpleName()
                                + "): keeping messages for retransmission every " + retryMs + " ms.");
                        failing = true;
                    }
                    try {
                        Thread.sleep(retryMs);
                    } catch (InterruptedException ie) {
                        return;
                    }
                } catch (RuntimeException e) {
                    // Erro do lado do Barrel ao processar esta mensagem: não a retransmite para não bloquear o canal
                    sending = false;
                    System.out.println("Barrel " + name + " rejected message " + message.getSeq() + ": " + e);
                }
            }
        }
    }
}
