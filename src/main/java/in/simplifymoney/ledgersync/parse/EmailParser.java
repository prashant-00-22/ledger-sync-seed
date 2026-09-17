package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails from HDFC and ICICI.
 */
public final class EmailParser implements MessageParser {

    private static final Set<String> ALLOWED_SENDERS = Set.of(
            "alerts@hdfcbank.net",
            "alerts@icicibank.com"
    );

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "^Date:\\s*(.+)$", Pattern.MULTILINE);

    private static final Pattern BODY_PATTERN = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>debited|credited) with .*?\\n"
                    + "Merchant / Remarks:\\s*(?<merchant>.+?)(?:\\n|$)",
            Pattern.DOTALL);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel()) && ALLOWED_SENDERS.contains(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher dm = DATE_PATTERN.matcher(body);
        if (!dm.find()) return Optional.empty();
        OffsetDateTime at = Dates.ist(dm.group(1));
        if (at == null) return Optional.empty();

        Matcher bm = BODY_PATTERN.matcher(body);
        if (!bm.find()) return Optional.empty();

        String acct = bm.group("acct");
        Direction dir = "debited".equalsIgnoreCase(bm.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
        String merchant = bm.group("merchant").trim();

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        return Optional.of(new ParsedTxn(
                acct,
                at,
                dir,
                amount,
                merchant,
                Amounts.statedBalance(body),
                m.messageId()
        ));
    }
}