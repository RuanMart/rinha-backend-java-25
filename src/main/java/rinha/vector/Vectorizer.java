package rinha.vector;

import rinha.config.Config;
import rinha.model.FraudRequest;

public final class Vectorizer {

    private static final int SCALE = Config.FIX_SCALE;

    private final Config config;

    public Vectorizer(Config config) {
        this.config = config;
    }

    public short[] vectorize(FraudRequest req) {
        short[] v = new short[Config.DIMENSIONS];

        v[0] = q(Config.clamp(req.transaction.amount / Config.MAX_AMOUNT));
        v[1] = q(Config.clamp((double) req.transaction.installments / Config.MAX_INSTALLMENTS));

        double avgAmount = req.customer.avg_amount;
        v[2] = q(avgAmount > 0 ? Config.clamp((req.transaction.amount / avgAmount) / Config.AMOUNT_VS_AVG_RATIO) : 1.0);

        String ra = req.transaction.requested_at;
        v[3] = q((d(ra, 11) * 10 + d(ra, 12)) / 23.0);
        int year = d(ra, 0) * 1000 + d(ra, 1) * 100 + d(ra, 2) * 10 + d(ra, 3);
        int month = d(ra, 5) * 10 + d(ra, 6);
        int day = d(ra, 8) * 10 + d(ra, 9);
        v[4] = q(dayOfWeek(year, month, day) / 6.0);

        if (req.last_transaction != null && req.last_transaction.timestamp != null) {
            long minDelta = Math.abs(epochSec(ra) - epochSec(req.last_transaction.timestamp)) / 60;
            v[5] = q(Config.clamp((double) minDelta / Config.MAX_MINUTES));
            v[6] = q(Config.clamp(req.last_transaction.km_from_current / Config.MAX_KM));
        } else {
            v[5] = (short) -SCALE;
            v[6] = (short) -SCALE;
        }

        v[7] = q(Config.clamp(req.terminal.km_from_home / Config.MAX_KM));
        v[8] = q(Config.clamp((double) req.customer.tx_count_24h / Config.MAX_TX_COUNT_24H));
        v[9] = (short) (req.terminal.is_online ? SCALE : 0);
        v[10] = (short) (req.terminal.card_present ? SCALE : 0);

        boolean knownMerchant = req.customer.known_merchants != null
                && req.customer.known_merchants.contains(req.merchant.id);
        v[11] = (short) (knownMerchant ? 0 : SCALE);

        v[12] = q(config.getMccRisk(req.merchant.mcc));
        v[13] = q(Config.clamp(req.merchant.avg_amount / Config.MAX_MERCHANT_AVG_AMOUNT));

        return v;
    }

    private static short q(double val) {
        return (short) Math.round(val * SCALE);
    }

    private static int d(String s, int i) {
        return s.charAt(i) - '0';
    }

    private static int dayOfWeek(int y, int m, int d) {
        if (m < 3) { m += 12; y--; }
        int h = (d + (13 * (m + 1)) / 5 + y + y / 4 - y / 100 + y / 400) % 7;
        return (h + 5) % 7;
    }

    private static long epochSec(String s) {
        int y = d(s, 0) * 1000 + d(s, 1) * 100 + d(s, 2) * 10 + d(s, 3);
        int m = d(s, 5) * 10 + d(s, 6);
        int dd = d(s, 8) * 10 + d(s, 9);
        int h = d(s, 11) * 10 + d(s, 12);
        int min = d(s, 14) * 10 + d(s, 15);
        int sec = d(s, 17) * 10 + d(s, 18);
        int a = (14 - m) / 12;
        int ya = y + 4800 - a;
        int ma = m + 12 * a - 3;
        long days = dd + (153L * ma + 2) / 5 + 365L * ya + ya / 4 - ya / 100 + ya / 400 - 32045L - 2440588L;
        return days * 86400 + h * 3600 + min * 60 + sec;
    }
}
