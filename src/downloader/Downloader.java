package downloader;

import common.Config;
import common.PageData;
import common.Tokenizer;
import common.UrlNormalizer;
import gateway.GatewayInterface;
import queue.URLQueueInterface;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.NoSuchFileException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/*
    Downloader (web crawler).
    Recebe URLs da fila por callback, descarrega a página com o jsoup, extrai
    título, citação, palavras e links, e devolve os links encontrados à fila
    (indexação iterativa). Podem correr vários Downloaders em paralelo.

    Tolerância a falhas da fila: uma thread verifica periodicamente (ping) se a fila
    está ativa. Se a fila avariar ou reiniciar, o Downloader volta a procurá-la no
    registry e regista-se de novo, sem ser preciso reiniciá-lo.

    Envio para os Barrels: cada página é enviada a todos os Barrels ativos por reliable multicast
    (ver ReliableMulticast). A lista de Barrels ativos é pedida periodicamente à Gateway.
*/

public class Downloader implements DownloaderInterface {

    // Tamanho máximo da citação mostrada nos resultados de pesquisa
    private static final int SNIPPET_LENGTH = 150;

    // Onde está a fila (registry) e com que nome
    private final String queueHost;
    private final int queuePort;
    private final String queueName;

    // Pausa entre páginas (milissegundos), para não sobrecarregar os servidores
    private final long delayMs;

    // Intervalo entre verificações da fila e entre tentativas de nova ligação (milissegundos)
    private final long reconnectMs;

    // Referência remota atual para a fila (volatile: é trocada pela thread de verificação)
    private volatile URLQueueInterface queue;

    // Referência remota (stub) deste downloader, enviada à fila quando fica livre
    private DownloaderInterface self;

    // Estado partilhado entre a thread de download e a de verificação (protegido por "lock"):
    // busy = está a tratar um URL; registeredWith = fila onde está registado como livre
    private final Object lock = new Object();
    private boolean busy = false;
    private URLQueueInterface registeredWith = null;

    // Onde está a Gateway (que tem a lista de Barrels ativos)
    private final String gatewayHost;
    private final int gatewayPort;
    private final String gatewayName;

    // Referência remota para a Gateway (só usada pela thread de verificação) e se já avisou que falhou
    private GatewayInterface gateway;
    private boolean gatewayWarned = false;

    // Envio das páginas indexadas para todos os Barrels
    private final ReliableMulticast multicast;

    public Downloader(Config config) {
        this.queueHost = config.getRmiHost();
        this.queuePort = config.getRmiPort();
        this.queueName = config.get("queue.name");
        this.delayMs = config.getInt("downloader.delay.ms");
        this.reconnectMs = config.getInt("downloader.reconnect.ms");
        this.gatewayHost = config.get("gateway.host");
        this.gatewayPort = config.getInt("gateway.port");
        this.gatewayName = config.get("gateway.name");
        this.multicast = new ReliableMulticast(config.getInt("multicast.retry.ms"));
    }

    @Override
    public void download(String url) {
        synchronized (lock) {
            busy = true;
        }

        // Cria uma thread para a chamada remota retornar logo e não bloquear a fila
        new Thread(() -> {
            try {
                System.out.println("Downloading: " + url);
                PageData page = parsePage(url);

                // Envia a página a todos os Barrels (reliable multicast; espera se não houver nenhum ativo)
                multicast.multicast(page);
                System.out.println("Indexed: " + url + " | title: \"" + page.getTitle() + "\" | "
                        + page.getWords().size() + " words | " + page.getLinks().size() + " links");

                // Devolve os links encontrados à fila para indexação iterativa (se a fila estiver em
                // baixo, espera por ela: a página já foi indexada, só faltam os links)
                sendLinks(url, new ArrayList<>(page.getLinks()));

            } catch (Exception e) {
                if (isPermanent(e)) {
                    // Repetir não adianta (ex.: 404, PDF): não volta à fila
                    System.out.println("Failed to process URL: " + url + " (" + describeFailure(e)
                            + ", permanent error: not retried)");
                } else {
                    System.out.println("Failed to process URL: " + url + " (" + describeFailure(e) + ")");
                    try {
                        // Erro temporário: a fila decide se o URL ainda pode ser tentado de novo
                        queue.retryUrl(url);
                    } catch (Exception ex) {
                        System.out.println("URL queue unreachable: " + reason(ex));
                    }
                }
            } finally {
                // Avisa sempre a fila que está livre (também em caso de erro, para não ficar parado)
                notifyReady();
            }
        }).start();
    }

    // Erro permanente num download: repetir não adianta. HTTP 4xx (exceto 408 Request Timeout e
    // 429 Too Many Requests, que são temporários), conteúdo que não é HTML e URL inválido.
    // Os restantes (timeout, ligação recusada, 5xx, ...) podem ser temporários e são repetidos.
    public static boolean isPermanent(Exception e) {
        if (e instanceof HttpStatusException http) {
            int status = http.getStatusCode();
            return status >= 400 && status < 500 && status != 408 && status != 429;
        }
        return e instanceof UnsupportedMimeTypeException || e instanceof MalformedURLException
                || e instanceof IllegalArgumentException;
    }

    // Descrição curta de uma falha de download, para o log (sem o nome da exceção nem stack trace)
    public static String describeFailure(Exception e) {
        if (e instanceof HttpStatusException http) {
            return "HTTP " + http.getStatusCode();
        }
        if (e instanceof UnsupportedMimeTypeException mime) {
            return "not an HTML page (" + mime.getMimeType() + ")";
        }
        if (e instanceof SocketTimeoutException) {
            return "timeout";
        }
        if (e instanceof UnknownHostException) {
            return "unknown host";
        }
        if (e instanceof ConnectException) {
            return "connection refused";
        }
        if (e instanceof SSLException) {
            return "SSL error";
        }
        if (e instanceof MalformedURLException || e instanceof IllegalArgumentException) {
            return "invalid URL";
        }
        if (e instanceof InterruptedException) {
            return "interrupted";
        }
        return e.getMessage() != null ? e.getMessage() : "download error";
    }

    // Envia à fila os links encontrados numa página. Se a fila não responder, volta a ligar-se a ela
    // (espera que reinicie) e repete o envio dos mesmos links até conseguir: a página já foi enviada
    // aos Barrels, por isso o URL não falhou e os links não se podem perder. Repetir é seguro porque
    // a fila ignora URLs que já viu.
    public void sendLinks(String url, List<String> links) {
        while (!Thread.currentThread().isInterrupted()) {
            URLQueueInterface q = queue;
            try {
                if (q == null) {
                    reconnect();
                    continue;
                }
                q.addUrls(links);
                return;
            } catch (RemoteException e) {
                System.out.println("URL queue unreachable: " + reason(e) + " (keeping the " + links.size()
                        + " links of " + url + " until it is back)");
                reconnect();
            }
        }
    }

    // Descarrega a página e extrai a informação para o índice
    private PageData parsePage(String url) throws Exception {
        // Liga-se ao URL e obtém o documento HTML
        Document doc = Jsoup.connect(url).get();
        return extract(url, doc);
    }

    // Extrai título, citação, palavras e links de um documento HTML já descarregado
    // (separado do download para poder ser testado sem acesso à internet)
    public static PageData extract(String url, Document doc) {
        String title = doc.title();
        String text = doc.body() != null ? doc.body().text() : "";

        // Citação: início do texto da página
        String snippet = text.length() > SNIPPET_LENGTH ? text.substring(0, SNIPPET_LENGTH) + "..." : text;

        // Palavras do título e do texto (o título está no <head>, fora do body)
        HashSet<String> words = Tokenizer.words(title + " " + text);

        // Links normalizados (só http/https, sem "#..."): URLs equivalentes contam como o mesmo
        HashSet<String> links = new HashSet<>();
        for (Element link : doc.select("a[href]")) {
            String foundUrl = UrlNormalizer.normalize(link.absUrl("href"));
            if (foundUrl != null) {
                links.add(foundUrl);
            }
        }

        return new PageData(url, title, snippet, words, links);
    }

    // Espera a pausa configurada e avisa a fila de que pode receber outro URL.
    // Se a fila não responder, volta a ligar-se a ela e tenta de novo até conseguir.
    private void notifyReady() {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            return;
        }
        while (true) {
            synchronized (lock) {
                try {
                    queue.downloaderReady(self);
                    registeredWith = queue;
                    busy = false;
                    return;
                } catch (RemoteException e) {
                    System.out.println("URL queue unreachable: " + reason(e));
                }
            }
            reconnect();
        }
    }

    // Procura a fila no registry até a encontrar (a fila pode estar desligada ou a reiniciar)
    private synchronized void reconnect() {
        while (true) {
            try {
                queue = (URLQueueInterface) LocateRegistry.getRegistry(queueHost, queuePort).lookup(queueName);
                System.out.println("Connected to URLQueue at " + queueHost + ":" + queuePort);
                return;
            } catch (Exception e) {
                System.out.println("URLQueue not available at " + queueHost + ":" + queuePort
                        + ", retrying in " + reconnectMs + " ms");
                try {
                    Thread.sleep(reconnectMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); // quem chamou vê a interrupção e termina
                    return;
                }
            }
        }
    }

    // Thread de verificação: confirma periodicamente que a fila está ativa e que este
    // downloader está registado nela quando está livre (necessário depois de a fila reiniciar)
    private void heartbeatLoop() {
        while (true) {
            try {
                queue.ping();
            } catch (RemoteException e) {
                System.out.println("Lost connection to URLQueue: " + reason(e));
                reconnect();
            }

            // Livre mas não registado na fila atual (arranque ou fila reiniciada): regista-se.
            // equals() dos stubs RMI compara o objeto remoto: depois de uma falha de rede passageira
            // o novo stub é igual ao anterior e o downloader não se regista duas vezes.
            synchronized (lock) {
                if (!busy && !queue.equals(registeredWith)) {
                    try {
                        queue.downloaderReady(self);
                        registeredWith = queue;
                        System.out.println("Downloader registered in URLQueue, waiting for URLs.");
                    } catch (RemoteException e) {
                        System.out.println("Could not register in URLQueue: " + reason(e));
                    }
                }
            }

            try {
                Thread.sleep(reconnectMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // Thread que pede periodicamente à Gateway a lista de Barrels ativos e atualiza o multicast.
    // Se a Gateway estiver em baixo, mantém a última lista conhecida (os Barrels continuam a receber
    // as páginas diretamente: a Gateway não está no caminho dos dados).
    private void barrelListLoop() {
        while (true) {
            try {
                if (gateway == null) {
                    gateway = (GatewayInterface) LocateRegistry.getRegistry(gatewayHost, gatewayPort).lookup(gatewayName);
                }
                multicast.updateBarrels(gateway.getBarrels());
                if (gatewayWarned) {
                    System.out.println("Connected to Gateway at " + gatewayHost + ":" + gatewayPort);
                    gatewayWarned = false;
                }
            } catch (Exception e) {
                gateway = null; // na próxima tentativa volta a procurar a Gateway no registry
                if (!gatewayWarned) {
                    System.out.println("Gateway not available at " + gatewayHost + ":" + gatewayPort
                            + ": using the last known list of Barrels, retrying every " + reconnectMs + " ms");
                    gatewayWarned = true;
                }
            }
            try {
                Thread.sleep(reconnectMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // Descrição de uma linha da causa de uma falha (a mensagem de uma RemoteException ocupa várias
    // linhas e inclui a exceção interna, o que parece uma stack trace para o utilizador)
    private static String reason(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    public static void main(String[] args) {
        try {
            // Lê a configuração e define o IP desta máquina para o RMI (callbacks)
            Config config = Config.load(args);
            config.applyLocalHostname();

            // Cria o downloader e exporta-o como um objeto remoto
            Downloader downloader = new Downloader(config);
            downloader.self = (DownloaderInterface) UnicastRemoteObject.exportObject(downloader, 0);

            // Lista de Barrels (Gateway) numa thread própria, para não depender do estado da fila
            Thread barrelList = new Thread(downloader::barrelListLoop, "barrel-list");
            barrelList.setDaemon(true);
            barrelList.start();

            // Liga-se à fila (espera por ela se ainda não estiver ativa) e inicia a verificação
            // periódica, que também faz o primeiro registo na fila
            downloader.reconnect();
            new Thread(downloader::heartbeatLoop, "queue-heartbeat").start();

            // Erros previsíveis: mensagem clara em vez de stack trace
        } catch (NoSuchFileException e) {
            Config.exitWithError("configuration file not found: " + e.getFile());
        } catch (IllegalArgumentException e) {
            Config.exitWithError("invalid configuration: " + e.getMessage());
        } catch (Exception e) {
            Config.exitWithError("Downloader could not start: " + e);
        }
    }
}
