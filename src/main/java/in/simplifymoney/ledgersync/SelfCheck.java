package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runs the whole pipeline in memory against fixtures/corpus-a.jsonl and prints
 * what it produced next to what fixtures/corpus-a-totals.json says it should
 * have produced.
 */
public final class SelfCheck {

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 0 ? args[0] : "fixtures/corpus-a.jsonl");
        Path totals = Path.of(args.length > 1 ? args[1] : "fixtures/corpus-a-totals.json");

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(corpus);

        System.out.println("INGEST");
        System.out.printf("  messages read       %d%n", stats.messagesRead());
        System.out.printf("  transactions written %d%n", stats.transactionsWritten());
        System.out.printf("  messages skipped    %d%n", stats.messagesSkipped());

        List<NormalizedTxn> ledger = store.all();
        Map<Category, BigDecimal> cats = in.simplifymoney.ledgersync.report.Reports
                .byCategory(ledger);
        System.out.println("\nBY CATEGORY");
        cats.forEach((c, v) -> System.out.printf("  %-9s %12s%n", c, v.toPlainString()));

        Map<String, Object> want = Json.parseObject(Files.readString(totals));
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) want.get("accounts");

        System.out.println("\nAGAINST fixtures/corpus-a-totals.json");
        System.out.printf("  transactions   expected %s, produced %d%n",
                want.get("transactions_expected"), ledger.size());

        for (Map.Entry<String, Object> e : accounts.entrySet()) {
            String acct = e.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) e.getValue();
            BigDecimal opening = new BigDecimal((String) a.get("opening_balance"));
            BigDecimal closing = new BigDecimal((String) a.get("closing_balance"));

            BigDecimal running = opening;
            long n = 0;
            BigDecimal spend = BigDecimal.ZERO;
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal microTotal = BigDecimal.ZERO;
            int microCount = 0;
            BigDecimal transferOut = BigDecimal.ZERO;
            BigDecimal transferIn = BigDecimal.ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                n++;

                running = switch (t.direction()) {
                    case DEBIT -> running.subtract(t.amount());
                    case CREDIT -> running.add(t.amount());
                };

                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microTotal = microTotal.add(t.amount());
                        microCount++;
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferOut = transferOut.add(t.amount());
                        } else {
                            transferIn = transferIn.add(t.amount());
                        }
                    }
                }
            }

            System.out.printf("%n  **%s  txns %d (expected %s)%n",
                    acct, n, a.get("transactions_expected"));
            System.out.printf("           balance from ledger %s, bank says %s, difference %s%n",
                    running.toPlainString(), closing.toPlainString(),
                    running.subtract(closing).toPlainString());

            // Detailed category breakdown vs expected targets
            System.out.printf("           spend:          %12s (expected %s)%n", spend, a.get("spend"));
            System.out.printf("           income:         %12s (expected %s)%n", income, a.get("income"));
            System.out.printf("           micro_total:    %12s (expected %s)%n", microTotal, a.get("micro_total"));
            System.out.printf("           micro_count:    %12d (expected %s)%n", microCount, a.get("micro_count"));
            System.out.printf("           transferred_out:%12s (expected %s)%n", transferOut, a.get("transferred_out"));
            System.out.printf("           transferred_in: %12s (expected %s)%n", transferIn, a.get("transferred_in"));
        }
        System.out.println("\n-------------------------------------------------------------");
    }
}