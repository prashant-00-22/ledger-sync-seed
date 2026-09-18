package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

public final class ConsistencyChecker {
    private final LedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(LedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();
        List<NormalizedTxn> sqlTxns = sql.all();
        Map<String, List<NormalizedTxn>> sqlByAccount = new HashMap<>();
        Set<String> allAccounts = new TreeSet<>();
        Set<String> allMessageIds = new HashSet<>();

        for (NormalizedTxn t : sqlTxns) {
            sqlByAccount.computeIfAbsent(t.accountLast4(), k -> new ArrayList<>()).add(t);
            allAccounts.add(t.accountLast4());
            allMessageIds.addAll(t.sourceMessageIds());
        }

        for (String acct : allAccounts) {
            List<NormalizedTxn> acctList = sqlByAccount.getOrDefault(acct, Collections.emptyList());
            Set<YearMonth> months = new HashSet<>();
            for (NormalizedTxn t : acctList) {
                months.add(YearMonth.from(t.occurredAt()));
            }

            for (YearMonth m : months) {
                List<NormalizedTxn> docList = documents.forAccountMonth(acct, m);
                long sqlMonthCount = acctList.stream()
                        .filter(t -> YearMonth.from(t.occurredAt()).equals(m))
                        .count();
                if (docList.size() != sqlMonthCount) {
                    divergences.add(new Divergence(
                            "Account " + acct + " month " + m + " count mismatch",
                            String.valueOf(sqlMonthCount),
                            String.valueOf(docList.size())
                    ));
                }
            }

            Map<Category, BigDecimal> docTotals = documents.categoryTotals(acct);
            Map<Category, BigDecimal> sqlTotals = new EnumMap<>(Category.class);
            for (Category c : Category.values()) sqlTotals.put(c, BigDecimal.ZERO.setScale(2));
            for (NormalizedTxn t : acctList) {
                sqlTotals.put(t.category(), sqlTotals.get(t.category()).add(t.amount()));
            }

            for (Category c : Category.values()) {
                BigDecimal sqlAmt = sqlTotals.get(c);
                BigDecimal docAmt = docTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (sqlAmt.compareTo(docAmt) != 0) {
                    divergences.add(new Divergence(
                            "Account " + acct + " Category " + c.name() + " total mismatch",
                            sqlAmt.toPlainString(),
                            docAmt.toPlainString()
                    ));
                }
            }
        }

        for (String msgId : allMessageIds) {
            if (documents.byMessageId(msgId).isEmpty()) {
                divergences.add(new Divergence(
                        "Message ID " + msgId + " not found in document store",
                        "Present in SQL",
                        "Missing"
                ));
            }
        }
        return divergences;
    }

    public record Divergence(String what, String inSql, String inDocuments) {}
}
