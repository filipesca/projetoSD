package common;

import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.rmi.server.RMISocketFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/*
    Lê a configuração do ficheiro .properties
*/

public class Config {

    // Ficheiro usado quando não é passado nenhum caminho nos argumentos
    public static final String DEFAULT_FILE = "googol.properties";

    private final Properties props = new Properties();

    // Lança NoSuchFileException se o ficheiro não existir
    public Config(String path) throws IOException {
        // Lê o ficheiro em UTF-8 para aceitar acentos nos comentários
        try (Reader in = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            props.load(in);
        }
    }

    // Carrega o ficheiro indicado em args[0] ou, se não existir, o ficheiro por defeito
    public static Config load(String[] args) throws IOException {
        return load(args.length > 0 ? args[0] : DEFAULT_FILE);
    }

    public static Config load(String path) throws IOException {
        System.out.println("Loading configuration from: " + path);
        return new Config(path);
    }

    // Termina o programa com uma mensagem clara e sem stack trace.
    // Usado nos main() para erros previsíveis (ficheiro em falta, porto ocupado, ...).
    public static void exitWithError(String message) {
        System.out.println("Error: " + message);
        System.exit(1);
    }

    // Devolve o valor de uma chave, sem espaços; erro se a chave não existir
    public String get(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing property: " + key);
        }
        return value.trim();
    }

    // Devolve um número inteiro; erro claro se o valor não for um número
    public int getInt(String key) {
        String value = get(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property " + key + " must be an integer, found: " + value);
        }
    }

    // Devolve uma lista a partir de valores separados por vírgula
    public List<String> getList(String key) {
        List<String> list = new ArrayList<>();
        for (String value : get(key).split(",")) {
            if (!value.isBlank()) {
                list.add(value.trim());
            }
        }
        return list;
    }

    public String getRmiHost() {
        return get("rmi.host");
    }

    public int getRmiPort() {
        return getInt("rmi.port");
    }

    // Define o IP que o RMI anuncia nos stubs desta máquina (necessário para callbacks entre máquinas)
    // e os tempos limite das chamadas remotas (ver applyRmiTimeout)
    public void applyLocalHostname() {
        System.setProperty("java.rmi.server.hostname", get("rmi.hostname.local"));
        applyRmiTimeout(getInt("rmi.timeout.ms"));
    }

    // Tempos limite do RMI. Por omissão o RMI espera para sempre pela resposta e a ligação a uma máquina
    // desligada só falha ao fim do tempo do sistema operativo (~21 s no Windows). Assim, uma máquina que
    // desaparece sem fechar as ligações (rede desligada, VM suspensa) dá RemoteException ao fim de
    // timeoutMs e o failover funciona, em vez de bloquear a pesquisa ou o heartbeat.
    // Tem de ser chamado antes da primeira chamada remota (o RMI lê a propriedade uma só vez).
    public static void applyRmiTimeout(int timeoutMs) {
        // Tempo máximo à espera da resposta de uma chamada remota
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", String.valueOf(timeoutMs));
        // Tempo máximo para estabelecer a ligação (fábrica de sockets usada por todo o RMI desta JVM)
        try {
            RMISocketFactory.setSocketFactory(new TimeoutSocketFactory(timeoutMs));
        } catch (IOException e) {
            // Já definida nesta JVM (só pode ser definida uma vez): mantém a anterior
        }
    }

    // Fábrica de sockets do RMI com tempo limite de ligação
    public static class TimeoutSocketFactory extends RMISocketFactory {
        private final int timeoutMs;

        public TimeoutSocketFactory(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket;
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new ServerSocket(port);
        }
    }
}
