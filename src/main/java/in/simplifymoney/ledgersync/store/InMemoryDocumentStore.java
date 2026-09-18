package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryDocumentStore implements DocumentStore {
    private final Map<String, List<NormalizedTxn>> accountTxns = new ConcurrentHashMap<>();
    private final Map<String, Map<Category, BigDecimal>> runningTotals = new ConcurrentHashMap<>();
    private final Map<String, NormalizedTxn> messageIndex = new ConcurrentHashMap<>();
    private final Set<String> dedupeKeys = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String dedupeKey = txn.accountLast4() + "|" + txn.occurredAt() + "|" + txn.direction() + "|" + txn.amount();
        if (dedupeKeys.contains(dedupeKey)) return;
        dedupeKeys.add(dedupeKey);

        accountTxns.computeIfAbsent(txn.accountLast4(), k -> new ArrayList<>()).add(txn);

        Map<Category, BigDecimal> totals = runningTotals.computeIfAbsent(txn.accountLast4(), k -> {
            Map<Category, BigDecimal> m = new EnumMap<>(Category.class);
            for (Category c : Category.values()) m.put(c, BigDecimal.ZERO.setScale(2));
            return m;
        });
        totals.put(txn.category(), totals.get(txn.category()).add(txn.amount()));

        for (String msgId : txn.sourceMessageIds()) {
            messageIndex.put(msgId, txn);
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> list = accountTxns.getOrDefault(accountLast4, Collections.emptyList());
        List<NormalizedTxn> matched = new ArrayList<>();
        for (NormalizedTxn t : list) {
            if (YearMonth.from(t.occurredAt()).equals(month)) matched.add(t);
        }
        matched.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());
        return matched;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> totals = runningTotals.get(accountLast4);
        if (totals == null) {
            Map<Category, BigDecimal> empty = new EnumMap<>(Category.class);
            for (Category c : Category.values()) empty.put(c, BigDecimal.ZERO.setScale(2));
            return empty;
        }
        return new EnumMap<>(totals);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        return Optional.ofNullable(messageIndex.get(messageId));
    }
}
