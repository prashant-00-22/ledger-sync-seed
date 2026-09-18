package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Backfill {
    private final LedgerStore source;
    private final DocumentStore target;

    public Backfill(LedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> all = source.all();
        long read = 0;
        long written = 0;
        long skipped = 0;
        Set<String> seen = new HashSet<>();

        for (NormalizedTxn txn : all) {
            read++;
            String identity = txn.accountLast4() + "|" + txn.occurredAt() + "|" + txn.direction() + "|" + txn.amount();
            if (seen.contains(identity)) {
                skipped++;
                continue;
            }
            seen.add(identity);
            target.save(txn);
            written++;
        }
        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
