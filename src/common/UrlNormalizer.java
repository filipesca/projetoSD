package common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/*
    Normaliza URLs para que endereços equivalentes sejam tratados como a mesma página.
    Exemplo: "HTTPS://www.UC.pt:443" e "https://www.uc.pt/#topo" passam ambos a "https://www.uc.pt/".
    É usado pela fila (URLs iniciais, do utilizador e descobertos) e pelo Downloader (links),
    para que a fila não visite a mesma página duas vezes e as ligações recebidas sejam contadas
    sempre para o mesmo URL.
*/

public class UrlNormalizer {

    // Devolve o URL normalizado, ou null se não for um URL http/https válido
    public static String normalize(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!"http".equals(scheme) && !"https".equals(scheme)) || uri.getHost() == null) {
                return null;
            }

            // Esquema e host em minúsculas; o porto só aparece se não for o porto por omissão
            StringBuilder sb = new StringBuilder(scheme).append("://").append(uri.getHost().toLowerCase(Locale.ROOT));
            int port = uri.getPort();
            boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
            if (!defaultPort) {
                sb.append(':').append(port);
            }

            // Caminho vazio passa a "/"; a query mantém-se; o fragmento (#...) é removido
            String path = uri.getRawPath();
            sb.append(path == null || path.isEmpty() ? "/" : path);
            if (uri.getRawQuery() != null) {
                sb.append('?').append(uri.getRawQuery());
            }
            return sb.toString();

        } catch (URISyntaxException e) {
            // URL com caracteres inválidos (ex.: espaços): mantém-se, apenas sem o fragmento
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                return null;
            }
            int hash = trimmed.indexOf('#');
            return hash >= 0 ? trimmed.substring(0, hash) : trimmed;
        }
    }
}
