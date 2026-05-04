package rinha.vector;

import rinha.config.Config;
import rinha.model.FraudRequest;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public final class Vectorizer {

    private static final Set<String> EMPTY_SET = Set.of();

    private final Config config;

    public Vectorizer(Config config) {
        this.config = config;
    }

    public double[] vectorize(FraudRequest req) {
        double[] v = new double[Config.DIMENSIONS];

        v[0] = Config.clamp(req.transaction.amount / Config.MAX_AMOUNT);
        v[1] = Config.clamp((double) req.transaction.installments / Config.MAX_INSTALLMENTS);

        double avgAmount = req.customer.avg_amount;
        v[2] = avgAmount > 0 ? Config.clamp((req.transaction.amount / avgAmount) / Config.AMOUNT_VS_AVG_RATIO) : 1.0;

        ZonedDateTime requestedAt = ZonedDateTime.parse(req.transaction.requested_at);
        v[3] = requestedAt.getHour() / 23.0;

        DayOfWeek dow = requestedAt.getDayOfWeek();
        int dowIndex = switch (dow) {
            case MONDAY -> 0;
            case TUESDAY -> 1;
            case WEDNESDAY -> 2;
            case THURSDAY -> 3;
            case FRIDAY -> 4;
            case SATURDAY -> 5;
            case SUNDAY -> 6;
        };
        v[4] = dowIndex / 6.0;

        if (req.last_transaction != null && req.last_transaction.timestamp != null) {
            ZonedDateTime lastTs = ZonedDateTime.parse(req.last_transaction.timestamp);
            long minutes = ChronoUnit.MINUTES.between(lastTs, requestedAt);
            v[5] = Config.clamp((double) minutes / Config.MAX_MINUTES);
            v[6] = Config.clamp(req.last_transaction.km_from_current / Config.MAX_KM);
        } else {
            v[5] = -1.0;
            v[6] = -1.0;
        }

        v[7] = Config.clamp(req.terminal.km_from_home / Config.MAX_KM);
        v[8] = Config.clamp((double) req.customer.tx_count_24h / Config.MAX_TX_COUNT_24H);
        v[9] = req.terminal.is_online ? 1.0 : 0.0;
        v[10] = req.terminal.card_present ? 1.0 : 0.0;

        List<String> knownList = req.customer.known_merchants;
        Set<String> known = knownList != null ? knownList.stream().collect(Collectors.toSet()) : EMPTY_SET;
        v[11] = known.contains(req.merchant.id) ? 0.0 : 1.0;

        v[12] = config.getMccRisk(req.merchant.mcc);
        v[13] = Config.clamp(req.merchant.avg_amount / Config.MAX_MERCHANT_AVG_AMOUNT);

        return v;
    }
}
