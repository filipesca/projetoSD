package common;

import java.io.IOException;
import java.io.Reader;
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
    public void applyLocalHostname() {
        System.setProperty("java.rmi.server.hostname", get("rmi.hostname.local"));
    }
}
