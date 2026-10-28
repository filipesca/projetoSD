package common;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    public Config(String path) throws IOException {
        // Lê o ficheiro em UTF-8 para aceitar acentos nos comentários
        try (InputStreamReader in = new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8)) {
            props.load(in);
        }
    }

    // Carrega o ficheiro indicado em args[0] ou, se não existir, o ficheiro por defeito
    public static Config load(String[] args) throws IOException {
        String path = args.length > 0 ? args[0] : DEFAULT_FILE;
        System.out.println("Loading configuration from: " + path);
        return new Config(path);
    }

    // Devolve o valor de uma chave, sem espaços; erro se a chave não existir
    public String get(String key) {
        String value = props.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing property: " + key);
        }
        return value.trim();
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key));
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
