package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS parser.
 *
 * Handles both classic ("Acct XX... is debited/credited with...")
 * and compact alert ("ICICI Bank Acct XX... Dr/Cr ...") formats.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) .*? "
                    + "on (?<when>\\d{2}-\\w{3}-\\d{4} \\d{2}:\\d{2}); "
                    + "(?<merchant>.+?)(?:\\s+ref no|\\.|$)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher v1 = V1.matcher(body);
        if (v1.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount != null && at != null) {
                Direction d = "debited".equalsIgnoreCase(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
                return Optional.of(new ParsedTxn(
                        v1.group("acct"),
                        at,
                        d,
                        amount,
                        v1.group("merchant").trim(),
                        Amounts.statedBalance(body),
                        m.messageId()));
            }
        }

        Matcher v2 = V2.matcher(body);
        if (v2.find()) {
            BigDecimal amount = Amounts.first(body);
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (amount != null && at != null) {
                Direction d = "Dr".equalsIgnoreCase(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
                return Optional.of(new ParsedTxn(
                        v2.group("acct"),
                        at,
                        d,
                        amount,
                        v2.group("merchant").trim(),
                        Amounts.statedBalance(body),
                        m.messageId()));
            }
        }

        return Optional.empty();
    }
}