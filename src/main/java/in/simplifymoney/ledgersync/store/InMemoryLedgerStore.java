package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class InMemoryLedgerStore implements LedgerStore {
    private final List<NormalizedTxn> txns = new ArrayList<>();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        txns.add(txn);
    }

    @Override
    public synchronized List<NormalizedTxn> all() {
        return Collections.unmodifiableList(new ArrayList<>(txns));
    }

    @Override
    public synchronized long count() {
        return txns.size();
    }
}
