package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Bank SMS and emails carry local date and time.
 * For SMS, these are local IST. Emails carry RFC-1123 date headers with timezone offset (+0530).
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> SMS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH));

    private static final DateTimeFormatter EMAIL_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        if (dateAndTime == null) return null;
        String s = dateAndTime.trim();

        // 1. Try RFC-1123 email date (e.g., Wed, 01 Jul 2026 09:02:00 +0530)
        try {
            return OffsetDateTime.parse(s, EMAIL_DATE).withOffsetSameInstant(IST);
        } catch (DateTimeParseException ignored) {}

        // 2. Try standard ISO if present
        try {
            return OffsetDateTime.parse(s).withOffsetSameInstant(IST);
        } catch (DateTimeParseException ignored) {}

        // 3. Try SMS patterns
        for (DateTimeFormatter f : SMS_FORMATS) {
            try {
                return LocalDateTime.parse(s, f).atOffset(IST);
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }
}