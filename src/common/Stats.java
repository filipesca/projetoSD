package common;

import java.io.Serializable;
import java.util.List;

/*
    Estatísticas do sistema, calculadas pela Gateway e enviadas aos clientes por callback.
    São records: o Java gera automaticamente construtor, getters, equals() e toString().
    O equals() é usado pela Gateway para só enviar as estatísticas quando mudam.
*/

public record Stats(List<SearchCount> topSearches, List<BarrelStats> barrels) implements Serializable {

    // Uma das pesquisas mais comuns e o número de vezes que foi feita
    public record SearchCount(String query, int count) implements Serializable {
    }

    // Estado de um Barrel ativo: tamanho do índice e tempo médio de resposta medido pela Gateway
    public record BarrelStats(String name, int pages, int words, long searches, double avgResponseTenths)
            implements Serializable {
    }

    // Texto para mostrar ao utilizador
    public String format() {
        StringBuilder sb = new StringBuilder("--- Top 10 pesquisas ---\n");
        if (topSearches.isEmpty()) {
            sb.append("  (ainda não houve pesquisas)\n");
        }
        for (int i = 0; i < topSearches.size(); i++) {
            sb.append(String.format("  %2d. %s (%d)%n", i + 1, topSearches.get(i).query(), topSearches.get(i).count()));
        }
        sb.append("--- Barrels ativos ---\n");
        if (barrels.isEmpty()) {
            sb.append("  (nenhum)\n");
        }
        for (BarrelStats b : barrels) {
            sb.append(String.format("  %s: %d páginas, %d palavras | %d pesquisas, tempo médio %.2f décimas de segundo%n",
                    b.name(), b.pages(), b.words(), b.searches(), b.avgResponseTenths()));
        }
        return sb.toString();
    }
}
