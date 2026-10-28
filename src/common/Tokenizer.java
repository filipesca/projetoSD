package common;

import java.util.HashSet;
import java.util.Locale;

/*
    Divide texto em palavras.
    É usado pelo Downloader (texto das páginas) e pelo Barrel (termos de pesquisa),
    para que ambos normalizem as palavras exatamente da mesma forma.
*/

public class Tokenizer {

    // Devolve as palavras distintas do texto: minúsculas, separadas por tudo o que não é letra ou dígito
    public static HashSet<String> words(String text) {
        HashSet<String> words = new HashSet<>();
        // Locale.ROOT: o resultado não depende da língua do sistema (ex.: "I" em turco)
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }
}
