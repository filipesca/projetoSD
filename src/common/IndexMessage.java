package common;

import java.io.Serializable;

/*
    Mensagem do reliable multicast Downloader -> Barrels.
    O par (senderId, seq) identifica a mensagem de forma única: o Barrel usa-o para
    descartar mensagens repetidas (retransmissões) e entregar cada página uma só vez.
*/

public class IndexMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String senderId; // identificador único do Downloader (gerado a cada arranque)
    private final long seq;        // número de sequência: 1, 2, 3, ... por Downloader
    private final PageData page;

    public IndexMessage(String senderId, long seq, PageData page) {
        this.senderId = senderId;
        this.seq = seq;
        this.page = page;
    }

    public String getSenderId() {
        return senderId;
    }

    public long getSeq() {
        return seq;
    }

    public PageData getPage() {
        return page;
    }
}
