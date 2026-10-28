package common;

import java.io.Serializable;

/*
    Um resultado de pesquisa: devolvido pelo Barrel à Gateway e por esta ao Client.
    É Serializable porque é passado por valor nas chamadas RMI.
*/

public class SearchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String url;
    private final String title;
    private final String snippet;
    private final int inboundLinks; // número de páginas com ligação para esta (critério de ordenação)

    public SearchResult(String url, String title, String snippet, int inboundLinks) {
        this.url = url;
        this.title = title;
        this.snippet = snippet;
        this.inboundLinks = inboundLinks;
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

    public int getInboundLinks() {
        return inboundLinks;
    }

    @Override
    public String toString() {
        return title + "\n  " + url + "\n  " + snippet + "\n  (" + inboundLinks + " links recebidos)";
    }
}
