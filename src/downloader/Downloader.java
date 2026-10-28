package downloader;

import common.Config;
import common.PageData;
import common.Tokenizer;
import queue.URLQueueInterface;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashSet;

/*
    Downloader (web crawler).
    Recebe URLs da fila por callback, descarrega a página com o jsoup, extrai
    título, citação, palavras e links, e devolve os links encontrados à fila
    (indexação iterativa). Podem correr vários Downloaders em paralelo.
*/

public class Downloader implements DownloaderInterface {

    // Tamanho máximo da citação mostrada nos resultados de pesquisa
    private static final int SNIPPET_LENGTH = 150;

    // Fila de URLs
    private final URLQueueInterface queue;

    // Pausa entre páginas (milissegundos), para não sobrecarregar os servidores
    private final long delayMs;

    // Referência remota (stub) deste downloader, enviada à fila quando fica livre
    private DownloaderInterface self;

    public Downloader(URLQueueInterface queue, long delayMs) {
        this.queue = queue;
        this.delayMs = delayMs;
    }

    @Override
    public void download(String url) {

        // Cria uma thread para a chamada remota retornar logo e não bloquear a fila
        new Thread(() -> {
            try {
                System.out.println("Downloading: " + url);
                PageData page = parsePage(url);

                // Devolve os links encontrados à fila para indexação iterativa
                queue.addUrls(new ArrayList<>(page.getLinks()));

                // TODO (passo 4): enviar a página para os Barrels por reliable multicast
                System.out.println("Indexed: " + url + " | title: \"" + page.getTitle() + "\" | "
                        + page.getWords().size() + " words | " + page.getLinks().size() + " links");

            } catch (Exception e) {
                System.out.println("Failed to process URL: " + url + " (" + e + ")");
                try {
                    // A fila decide se o URL ainda pode ser tentado de novo
                    queue.retryUrl(url);
                } catch (Exception ex) {
                    System.out.println("URL queue unreachable: " + ex.getMessage());
                }
            } finally {
                // Avisa sempre a fila que está livre (também em caso de erro, para não ficar parado)
                notifyReady();
            }
        }).start();
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

        // Palavras: texto em minúsculas, separado por tudo o que não é letra ou dígito
        HashSet<String> words = Tokenizer.words(text);

        // Links: só http/https, sem a parte "#..." (é a mesma página)
        HashSet<String> links = new HashSet<>();
        for (Element link : doc.select("a[href]")) {
            String foundUrl = link.absUrl("href");
            int hash = foundUrl.indexOf('#');
            if (hash >= 0) {
                foundUrl = foundUrl.substring(0, hash);
            }
            if (foundUrl.startsWith("http://") || foundUrl.startsWith("https://")) {
                links.add(foundUrl);
            }
        }

        return new PageData(url, title, snippet, words, links);
    }

    // Espera a pausa configurada e avisa a fila de que pode receber outro URL
    private void notifyReady() {
        try {
            Thread.sleep(delayMs);
            queue.downloaderReady(self);
        } catch (Exception e) {
            System.out.println("Could not notify URL queue: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            // Lê a configuração e define o IP desta máquina para o RMI (callbacks)
            Config config = Config.load(args);
            config.applyLocalHostname();

            // Liga ao registry do RMI e procura o objeto da fila
            Registry registry = LocateRegistry.getRegistry(config.getRmiHost(), config.getRmiPort());
            URLQueueInterface queue = (URLQueueInterface) registry.lookup(config.get("queue.name"));

            // Cria o downloader e exporta-o como um objeto remoto
            Downloader downloader = new Downloader(queue, config.getInt("downloader.delay.ms"));
            downloader.self = (DownloaderInterface) UnicastRemoteObject.exportObject(downloader, 0);

            // Avisa a fila de que está livre: a partir daqui recebe URLs por callback
            queue.downloaderReady(downloader.self);

            System.out.println("Downloader ready and registered for callbacks.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
