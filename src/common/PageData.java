package common;

import java.io.Serializable;
import java.util.HashSet;

/*
    Informação extraída de uma página pelo Downloader.
    É Serializable porque vai ser enviada por RMI (passagem por valor) para os Barrels.
*/

public class PageData implements Serializable {

    // Versão da classe para a serialização (deve ser igual em todas as máquinas)
    private static final long serialVersionUID = 1L;

    private final String url;
    private final String title;
    private final String snippet;    // citação curta do texto da página
    private final HashSet<String> words; // palavras distintas encontradas no texto
    private final HashSet<String> links; // URLs para onde esta página aponta

    public PageData(String url, String title, String snippet, HashSet<String> words, HashSet<String> links) {
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.words = words;
        this.links = links;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public HashSet<String> getWords() {
        return words;
    }

    public HashSet<String> getLinks() {
        return links;
    }
}
