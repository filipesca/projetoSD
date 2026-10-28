package tests;

import common.Config;
import common.PageData;
import downloader.Downloader;
import downloader.DownloaderInterface;
import org.jsoup.Jsoup;
import queue.URLQueue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/*
    Testes unitários (sem JUnit, o projeto não usa Maven/Gradle).
    Correr: java -cp "out;lib\jsoup-1.23.2.jar" tests.UnitTests
    Termina com código 0 se todos os testes passarem e 1 caso contrário.
*/

public class UnitTests {

    // Resultados: nome do teste e PASS/FAIL
    private static final List<String[]> results = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        testConfig();
        testExtract();
        testQueueNoDuplicates();
        testQueueRetryLimit();

        // Tabela final
        boolean allPassed = true;
        System.out.println();
        System.out.println("| Teste | Resultado |");
        System.out.println("|---|---|");
        for (String[] r : results) {
            System.out.println("| " + r[0] + " | " + r[1] + " |");
            allPassed &= r[1].equals("PASS");
        }
        System.out.println(allPassed ? "ALL UNIT TESTS PASSED" : "SOME UNIT TESTS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    // Regista o resultado de uma verificação
    private static void check(String name, boolean ok) {
        results.add(new String[]{name, ok ? "PASS" : "FAIL"});
    }

    // Config: valores, listas (com espaços e entradas vazias), acentos e chave em falta
    private static void testConfig() throws Exception {
        Path file = Files.createTempFile("googol-test", ".properties");
        Files.writeString(file, "# comentário\nnum = 42\nlist= a , ,b\ntexto=informação\n", StandardCharsets.UTF_8);
        Config config = new Config(file.toString());

        check("Config: getInt", config.getInt("num") == 42);
        check("Config: getList ignora espaços e vazios", config.getList("list").equals(List.of("a", "b")));
        check("Config: lê UTF-8", config.get("texto").equals("informação"));

        boolean threw = false;
        try {
            config.get("nao.existe");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("Config: chave em falta dá erro claro", threw);
        Files.delete(file);
    }

    // Extração do Downloader a partir de HTML fixo
    private static void testExtract() {
        String longText = "Palavra ".repeat(40);
        String html = "<html><head><title>Universidade de Coimbra</title></head><body>"
                + "<p>Olá, Informática! UC-2026 " + longText + "</p>"
                + "<a href=\"/sobre#equipa\">a</a>"
                + "<a href=\"https://exemplo.pt/x\">b</a>"
                + "<a href=\"mailto:geral@uc.pt\">c</a>"
                + "<a href=\"javascript:void(0)\">d</a>"
                + "</body></html>";
        PageData page = Downloader.extract("https://www.uc.pt/", Jsoup.parse(html, "https://www.uc.pt/"));

        check("Extract: título", page.getTitle().equals("Universidade de Coimbra"));
        check("Extract: citação limitada a 150 caracteres + ...",
                page.getSnippet().length() == 153 && page.getSnippet().endsWith("..."));
        check("Extract: palavras em minúsculas, com acentos, sem pontuação",
                page.getWords().containsAll(List.of("olá", "informática", "uc", "2026", "palavra"))
                        && !page.getWords().contains("Olá") && !page.getWords().contains(""));
        check("Extract: links absolutos, sem #, só http/https",
                page.getLinks().size() == 2
                        && page.getLinks().contains("https://www.uc.pt/sobre")
                        && page.getLinks().contains("https://exemplo.pt/x"));
    }

    // Downloader falso (local, sem RMI) que regista os URLs recebidos e volta logo a ficar livre
    private static class FakeDownloader implements DownloaderInterface {
        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        URLQueue queue;

        @Override
        public void download(String url) {
            received.add(url);
            queue.downloaderReady(this);
        }
    }

    // Cria uma fila com um downloader falso já registado
    private static FakeDownloader newQueueWithFake(int maxRetries) {
        FakeDownloader fake = new FakeDownloader();
        fake.queue = new URLQueue(List.of(), maxRetries);
        fake.queue.downloaderReady(fake);
        return fake;
    }

    // Cada URL é entregue uma só vez, mesmo que seja adicionado várias vezes
    private static void testQueueNoDuplicates() throws Exception {
        FakeDownloader fake = newQueueWithFake(2);
        fake.queue.addUrl("http://a");
        fake.queue.addUrl("http://a");
        fake.queue.addUrls(List.of("http://b", "http://a", "http://b"));

        List<String> got = new ArrayList<>();
        String url;
        while ((url = fake.received.poll(2, TimeUnit.SECONDS)) != null) {
            got.add(url);
        }
        check("Queue: URLs repetidos entregues uma só vez", got.equals(List.of("http://a", "http://b")));
    }

    // retryUrl volta a pôr o URL na fila no máximo maxRetries vezes
    private static void testQueueRetryLimit() throws Exception {
        FakeDownloader fake = newQueueWithFake(2);
        fake.queue.addUrl("http://falha");
        fake.received.poll(2, TimeUnit.SECONDS); // primeira tentativa

        int delivered = 0;
        for (int i = 0; i < 3; i++) {
            fake.queue.retryUrl("http://falha");
            if (fake.received.poll(2, TimeUnit.SECONDS) != null) {
                delivered++;
            }
        }
        check("Queue: no máximo 2 novas tentativas", delivered == 2);
    }
}
