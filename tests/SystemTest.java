package tests;

import com.sun.net.httpserver.HttpServer;
import queue.URLQueueInterface;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
    Teste de sistema com processos reais: 1 URLQueue + 2 Downloaders.
    As páginas são servidas por um servidor HTTP local (não depende da internet).
    Correr: java -cp "out;lib\jsoup-1.23.2.jar" tests.SystemTest
    Termina com código 0 se todos os testes passarem e 1 caso contrário.

    Cenários:
    1. Indexação iterativa: a partir de /p0 são visitadas as PAGES páginas ligadas entre si.
    2. Cada URL é descarregado por um único downloader (os dois trabalham em paralelo).
    3. Novas tentativas: /missing (HTTP 404) é tentado 1 + 2 vezes e depois descartado.
    4. Avaria de um downloader: o Downloader 1 é morto enquanto está livre na fila;
       a fila deteta a RemoteException, devolve o URL à fila e o Downloader 2 trata de tudo.
    5. Os logs não têm exceções inesperadas.
*/

public class SystemTest {

    // Número de páginas do site de teste e de páginas extra adicionadas depois da avaria
    private static final int PAGES = 20;
    private static final int EXTRA_PAGES = 10;

    // Tempo máximo de espera por cada fase (margem larga)
    private static final long TIMEOUT_MS = 120_000;

    private static final List<String[]> results = new ArrayList<>();
    private static final List<Process> processes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path logDir = Files.createTempDirectory("googol-systemtest");
        HttpServer http = startHttpServer();
        String base = "http://localhost:" + http.getAddress().getPort();
        int rmiPort = freePort();

        // Configuração própria do teste (porto livre, URL inicial local, pausa curta)
        Path props = logDir.resolve("test.properties");
        Files.writeString(props, String.join("\n",
                "rmi.host=localhost",
                "rmi.port=" + rmiPort,
                "rmi.hostname.local=localhost",
                "queue.name=URLQueue",
                "queue.seeds=" + base + "/p0",
                "queue.max.retries=2",
                "downloader.delay.ms=100", ""), StandardCharsets.UTF_8);

        Path queueLog = logDir.resolve("queue.log");
        Path d1Log = logDir.resolve("downloader1.log");
        Path d2Log = logDir.resolve("downloader2.log");

        try {
            // Arranque: primeiro a fila (cria o registry), depois os dois downloaders
            start("queue.URLQueue", props, queueLog);
            waitFor(() -> read(queueLog).contains("URLQueue RMI Server ready."));
            Process d1 = start("downloader.Downloader", props, d1Log);
            start("downloader.Downloader", props, d2Log);

            // Fase 1: esperar que todas as páginas sejam indexadas e que /missing seja descartado
            boolean phase1 = waitFor(() -> countIndexed(base + "/p", d1Log, d2Log) == PAGES
                    && read(queueLog).contains("Giving up"));
            check("Indexação iterativa: " + PAGES + " páginas visitadas a partir de /p0", phase1);

            Map<String, Integer> d1Urls = downloads(d1Log);
            Map<String, Integer> d2Urls = downloads(d2Log);
            check("Downloaders em paralelo: os dois receberam URLs",
                    countPrefix(d1Urls, base + "/p") > 0 && countPrefix(d2Urls, base + "/p") > 0);

            String missing = base + "/missing";
            int missingAttempts = d1Urls.getOrDefault(missing, 0) + d2Urls.getOrDefault(missing, 0);
            check("Novas tentativas: /missing tentado 3 vezes (1 + 2) e descartado",
                    missingAttempts == 3 && read(queueLog).contains("Retry 2/2")
                            && read(queueLog).contains("Giving up on URL after 2 retries: " + missing));

            // Fase 2: matar o Downloader 1 (está livre, à espera na fila) e adicionar novos URLs.
            // A pausa garante que os dois downloaders já terminaram o delay e voltaram a estar livres.
            Thread.sleep(2000);
            d1.destroyForcibly().waitFor();
            List<String> extra = new ArrayList<>();
            for (int i = 0; i < EXTRA_PAGES; i++) {
                extra.add(base + "/q" + i);
            }
            URLQueueInterface queue = (URLQueueInterface) LocateRegistry.getRegistry("localhost", rmiPort)
                    .lookup("URLQueue");
            queue.addUrls(extra);

            boolean phase2 = waitFor(() -> countIndexed(base + "/q", d2Log) == EXTRA_PAGES);
            check("Avaria: fila detetou o downloader morto (RemoteException)",
                    read(queueLog).contains("Downloader unreachable, URL returned to queue"));
            check("Avaria: o Downloader 2 indexou todos os " + EXTRA_PAGES + " URLs novos", phase2);

            // Cada URL (exceto /missing, que é repetido de propósito) descarregado uma só vez no total
            Map<String, Integer> all = downloads(d1Log);
            downloads(d2Log).forEach((url, n) -> all.merge(url, n, Integer::sum));
            all.remove(missing);
            boolean unique = all.values().stream().allMatch(n -> n == 1);
            check("Cada URL descarregado por um único downloader (" + all.size() + " URLs)",
                    unique && all.size() == PAGES + EXTRA_PAGES);

            // Exceções inesperadas: stack traces ou "Exception" fora das linhas de falha esperadas
            check("Logs sem exceções inesperadas nem ERROR",
                    cleanLog(queueLog) && cleanLog(d1Log) && cleanLog(d2Log));

        } finally {
            for (Process p : processes) {
                p.destroyForcibly();
            }
            http.stop(0);
        }

        // Tabela final
        boolean allPassed = true;
        System.out.println();
        System.out.println("| Teste | Resultado |");
        System.out.println("|---|---|");
        for (String[] r : results) {
            System.out.println("| " + r[0] + " | " + r[1] + " |");
            allPassed &= r[1].equals("PASS");
        }
        System.out.println("Logs: " + logDir);
        System.out.println(allPassed ? "ALL SYSTEM TESTS PASSED" : "SOME SYSTEM TESTS FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    private static void check(String name, boolean ok) {
        results.add(new String[]{name, ok ? "PASS" : "FAIL"});
    }

    // Servidor HTTP local: /pN liga a /p(N+1) e /p(N+2) (com e sem #), /p0 liga também a /missing (404)
    private static HttpServer startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = null;
            Matcher m = Pattern.compile("/([pq])(\\d+)").matcher(path);
            if (m.matches()) {
                int n = Integer.parseInt(m.group(2));
                StringBuilder sb = new StringBuilder("<html><head><title>Página " + path + "</title></head><body>")
                        .append("<p>Texto de teste da página ").append(path).append("</p>");
                if (m.group(1).equals("p")) {
                    for (int next = n + 1; next <= n + 2 && next < PAGES; next++) {
                        sb.append("<a href=\"/p").append(next).append("\">x</a>");
                        sb.append("<a href=\"/p").append(next).append("#sec\">y</a>");
                    }
                    if (n == 0) {
                        sb.append("<a href=\"/missing\">z</a><a href=\"mailto:a@b.pt\">m</a>");
                    }
                }
                body = sb.append("</body></html>").toString();
            }
            byte[] bytes = body == null ? "not found".getBytes() : body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    // Porto TCP livre para o registry deste teste (não interfere com um registry na 1099)
    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    // Arranca um programa do projeto noutro processo Java, com o output redirecionado para um log
    private static Process start(String mainClass, Path props, Path log) throws IOException {
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        // Output em UTF-8 para os logs poderem ser lidos de forma igual em qualquer sistema
        ProcessBuilder pb = new ProcessBuilder(java, "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-cp", System.getProperty("java.class.path"), mainClass, props.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        processes.add(p);
        return p;
    }

    // Espera (com polling) até a condição ser verdadeira ou acabar o tempo
    private static boolean waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    // Lê um log; bytes inválidos são substituídos em vez de lançar exceção
    private static String read(Path log) {
        try {
            return Files.exists(log) ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    // Número de vezes que cada URL aparece em "Downloading: <url>" num log
    private static Map<String, Integer> downloads(Path log) {
        Map<String, Integer> counts = new HashMap<>();
        for (String line : read(log).split("\\R")) {
            if (line.startsWith("Downloading: ")) {
                counts.merge(line.substring("Downloading: ".length()).trim(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static int countPrefix(Map<String, Integer> urls, String prefix) {
        return (int) urls.keySet().stream().filter(u -> u.startsWith(prefix)).count();
    }

    // Número de URLs distintos com o prefixo dado que aparecem como "Indexed: <url> |" nos logs
    private static int countIndexed(String prefix, Path... logs) {
        java.util.Set<String> indexed = new java.util.HashSet<>();
        for (Path log : logs) {
            for (String line : read(log).split("\\R")) {
                if (line.startsWith("Indexed: " + prefix)) {
                    indexed.add(line.substring("Indexed: ".length(), line.indexOf(" |")));
                }
            }
        }
        return indexed.size();
    }

    // Um log está limpo se não tiver stack traces, ERROR, nem "Exception" fora das falhas de download esperadas
    private static boolean cleanLog(Path log) {
        for (String line : read(log).split("\\R")) {
            boolean expectedFailure = line.startsWith("Failed to process URL: ");
            if (line.startsWith("\tat ") || line.contains("ERROR")
                    || (line.contains("Exception") && !expectedFailure)) {
                System.out.println("Unexpected log line in " + log.getFileName() + ": " + line);
                return false;
            }
        }
        return true;
    }
}
