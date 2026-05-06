package rinha.vector;

import rinha.config.Config;

public final class Vectorizer {

    private static final float MAX_AMOUNT = 10_000f;
    private static final float MAX_INSTALL = 12f;
    private static final float AMT_AVG_RATIO = 10f;
    private static final float MAX_MINUTES = 1_440f;
    private static final float MAX_KM = 1_000f;
    private static final float MAX_TX_24H = 20f;
    private static final float MAX_MERCH_AMT = 10_000f;

    public static void vectorize(byte[] b, int len, float[] v) {
        final int txOpen = sectionOpen(b, 0, len, K_TRANSACTION);
        final int txClose = braceClose(b, txOpen, len);

        final float txAmount = readFloat(b, valAt(b, txOpen, txClose, K_AMOUNT));
        final int installments = readInt(b, valAt(b, txOpen, txClose, K_INSTALLMENTS));
        final int reqAtPos = valAt(b, txOpen, txClose, K_REQUESTED_AT) + 1;

        final int custOpen = sectionOpen(b, txClose, len, K_CUSTOMER);
        final int custClose = braceClose(b, custOpen, len);

        final float custAvg = readFloat(b, valAt(b, custOpen, custClose, K_AVG_AMOUNT));
        final int txCount = readInt(b, valAt(b, custOpen, custClose, K_TX_COUNT));
        final int arrOpen = arrayOpen(b, valAt(b, custOpen, custClose, K_KNOWN_MERCHANTS), custClose);
        final int arrClose = arrayClose(b, arrOpen, custClose);

        final int merchOpen = sectionOpen(b, custClose, len, K_MERCHANT);
        final int merchClose = braceClose(b, merchOpen, len);

        final int midPos = valAt(b, merchOpen, merchClose, K_MERCH_ID) + 1;
        final int midLen = stringLen(b, midPos, merchClose);
        final int mccPos = valAt(b, merchOpen, merchClose, K_MCC) + 1;
        final int mcc = read4Digits(b, mccPos);
        final float mAmt = readFloat(b, valAt(b, merchOpen, merchClose, K_AVG_AMOUNT));

        final int termOpen = sectionOpen(b, merchClose, len, K_TERMINAL);
        final int termClose = braceClose(b, termOpen, len);

        final boolean isOnline = readBool(b, valAt(b, termOpen, termClose, K_IS_ONLINE));
        final boolean cardPresent = readBool(b, valAt(b, termOpen, termClose, K_CARD_PRESENT));
        final float kmFromHome = readFloat(b, valAt(b, termOpen, termClose, K_KM_FROM_HOME));

        final int ltKeyPos = indexOf(b, termClose, len, K_LAST_TX);
        final int ltValPos = skipToValue(b, ltKeyPos + K_LAST_TX.length, len);
        final boolean hasLast = b[ltValPos] != 'n';

        float minutesSinceLast = -1f;
        float kmFromLast = -1f;

        if (hasLast) {
            final int ltOpen = sectionOpen2(b, ltValPos, len);
            final int ltClose = braceClose(b, ltOpen, len);
            final int tsPos = valAt(b, ltOpen, ltClose, K_TIMESTAMP) + 1;
            final float kmCurr = readFloat(b, valAt(b, ltOpen, ltClose, K_KM_FROM_CURRENT));
            final long reqMin = isoToEpochMin(b, reqAtPos);
            final long lastMin = isoToEpochMin(b, tsPos);
            final long diff = reqMin - lastMin;
            minutesSinceLast = diff < 0 ? 0f : (float) diff;
            kmFromLast = kmCurr;
        }

        final int unknown = merchantKnown(b, arrOpen, arrClose, midPos, midLen) ? 0 : 1;

        v[0] = Config.clamp(txAmount / MAX_AMOUNT);
        v[1] = Config.clamp(installments / MAX_INSTALL);
        v[2] = (custAvg == 0f) ? 0f : Config.clamp((txAmount / custAvg) / AMT_AVG_RATIO);
        v[3] = isoHour(b, reqAtPos) / 23f;
        v[4] = isoDOW(b, reqAtPos) / 6f;
        v[5] = hasLast ? Config.clamp(minutesSinceLast / MAX_MINUTES) : -1f;
        v[6] = hasLast ? Config.clamp(kmFromLast / MAX_KM) : -1f;
        v[7] = Config.clamp(kmFromHome / MAX_KM);
        v[8] = Config.clamp(txCount / MAX_TX_24H);
        v[9] = isOnline ? 1f : 0f;
        v[10] = cardPresent ? 1f : 0f;
        v[11] = unknown;
        v[12] = Config.mccRisk(mcc);
        v[13] = Config.clamp(mAmt / MAX_MERCH_AMT);
    }

    private static final byte[] K_TRANSACTION = k("\"transaction\"");
    private static final byte[] K_AMOUNT = k("\"amount\"");
    private static final byte[] K_INSTALLMENTS = k("\"installments\"");
    private static final byte[] K_REQUESTED_AT = k("\"requested_at\"");
    private static final byte[] K_CUSTOMER = k("\"customer\"");
    private static final byte[] K_AVG_AMOUNT = k("\"avg_amount\"");
    private static final byte[] K_TX_COUNT = k("\"tx_count_24h\"");
    private static final byte[] K_KNOWN_MERCHANTS = k("\"known_merchants\"");
    private static final byte[] K_MERCHANT = k("\"merchant\"");
    private static final byte[] K_MERCH_ID = k("\"id\"");
    private static final byte[] K_MCC = k("\"mcc\"");
    private static final byte[] K_TERMINAL = k("\"terminal\"");
    private static final byte[] K_IS_ONLINE = k("\"is_online\"");
    private static final byte[] K_CARD_PRESENT = k("\"card_present\"");
    private static final byte[] K_KM_FROM_HOME = k("\"km_from_home\"");
    private static final byte[] K_LAST_TX = k("\"last_transaction\"");
    private static final byte[] K_TIMESTAMP = k("\"timestamp\"");
    private static final byte[] K_KM_FROM_CURRENT = k("\"km_from_current\"");

    private static byte[] k(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        return b;
    }

    private static int indexOf(byte[] b, int from, int to, byte[] pat) {
        final int pLen = pat.length;
        final int stop = to - pLen;
        final byte first = pat[0];
        outer:
        for (int i = from; i <= stop; i++) {
            if (b[i] == first) {
                for (int j = 1; j < pLen; j++) {
                    if (b[i + j] != pat[j]) continue outer;
                }
                return i;
            }
        }
        throw new IllegalArgumentException("key not found");
    }

    private static int sectionOpen(byte[] b, int from, int end, byte[] key) {
        return findChar(b, indexOf(b, from, end, key) + key.length, end, (byte) '{');
    }

    private static int sectionOpen2(byte[] b, int from, int end) {
        return findChar(b, from, end, (byte) '{');
    }

    private static int arrayOpen(byte[] b, int from, int end) {
        return findChar(b, from, end, (byte) '[');
    }

    private static int findChar(byte[] b, int from, int end, byte c) {
        for (int i = from; i < end; i++) {
            if (b[i] == c) return i;
        }
        throw new IllegalArgumentException("char not found");
    }

    private static int braceClose(byte[] b, int openPos, int end) {
        int depth = 0;
        for (int i = openPos; i < end; i++) {
            byte c = b[i];
            if (c == '{') depth++;
            else if (c == '}') { if (--depth == 0) return i; }
        }
        throw new IllegalArgumentException("unmatched '{'");
    }

    private static int arrayClose(byte[] b, int openPos, int end) {
        int depth = 0;
        for (int i = openPos; i < end; i++) {
            byte c = b[i];
            if (c == '[') depth++;
            else if (c == ']') { if (--depth == 0) return i; }
        }
        throw new IllegalArgumentException("unmatched '['");
    }

    private static int valAt(byte[] b, int from, int to, byte[] key) {
        return skipToValue(b, indexOf(b, from, to, key) + key.length, to);
    }

    private static int skipToValue(byte[] b, int pos, int end) {
        while (pos < end && b[pos] != ':') pos++;
        pos++;
        while (pos < end && b[pos] <= ' ') pos++;
        return pos;
    }

    private static float readFloat(byte[] b, int pos) {
        boolean neg = b[pos] == '-';
        if (neg) pos++;
        int intPart = 0, fracPart = 0, fracDiv = 1;
        boolean frac = false;
        byte c;
        while (true) {
            c = b[pos];
            if (c >= '0' && c <= '9') {
                if (!frac) intPart = intPart * 10 + (c - '0');
                else { fracPart = fracPart * 10 + (c - '0'); fracDiv *= 10; }
                pos++;
            } else if (c == '.' && !frac) {
                frac = true; pos++;
            } else {
                break;
            }
        }
        float r = intPart + (float) fracPart / fracDiv;
        return neg ? -r : r;
    }

    private static int readInt(byte[] b, int pos) {
        int r = 0;
        byte c;
        while ((c = b[pos]) >= '0' && c <= '9') { r = r * 10 + (c - '0'); pos++; }
        return r;
    }

    private static boolean readBool(byte[] b, int pos) {
        return b[pos] == 't';
    }

    private static int read4Digits(byte[] b, int pos) {
        return (b[pos] - '0') * 1000
             + (b[pos + 1] - '0') * 100
             + (b[pos + 2] - '0') * 10
             + (b[pos + 3] - '0');
    }

    private static int stringLen(byte[] b, int pos, int end) {
        int len = 0;
        while (pos + len < end && b[pos + len] != '"') len++;
        return len;
    }

    private static boolean merchantKnown(byte[] b, int arrOpen, int arrClose, int midPos, int midLen) {
        int i = arrOpen + 1;
        while (i < arrClose) {
            if (b[i] == '"') {
                i++;
                if (i + midLen <= arrClose && b[i + midLen] == '"') {
                    boolean match = true;
                    for (int j = 0; j < midLen; j++) {
                        if (b[i + j] != b[midPos + j]) { match = false; break; }
                    }
                    if (match) return true;
                }
                while (i < arrClose && b[i] != '"') i++;
            }
            i++;
        }
        return false;
    }

    private static int read2(byte[] b, int pos) {
        return (b[pos] - '0') * 10 + (b[pos + 1] - '0');
    }

    private static int isoHour(byte[] b, int pos) {
        return read2(b, pos + 11);
    }

    private static int isoDOW(byte[] b, int pos) {
        int y = read2(b, pos) * 100 + read2(b, pos + 2);
        int m = read2(b, pos + 5);
        int d = read2(b, pos + 8);
        if (m < 3) y--;
        int dow = (y + y / 4 - y / 100 + y / 400 + sakamotoT(m) + d) % 7;
        return (dow + 6) % 7;
    }

    private static int sakamotoT(int m) {
        switch (m) {
            case 1: return 0; case 2: return 3; case 3: return 2;
            case 4: return 5; case 5: return 0; case 6: return 3;
            case 7: return 5; case 8: return 1; case 9: return 4;
            case 10: return 6; case 11: return 2; default: return 4;
        }
    }

    private static long isoToEpochMin(byte[] b, int pos) {
        int y = read2(b, pos) * 100 + read2(b, pos + 2);
        int mo = read2(b, pos + 5);
        int d = read2(b, pos + 8);
        int h = read2(b, pos + 11);
        int mi = read2(b, pos + 14);
        return epochMin(y, mo, d, h, mi);
    }

    private static long epochMin(int y, int mo, int d, int h, int mi) {
        boolean leap = (y % 4 == 0) && (y % 100 != 0 || y % 400 == 0);
        int doy = monthOffset(mo) + d - 1;
        if (mo > 2 && leap) doy++;
        int yy = y - 1;
        int leaps = yy / 4 - yy / 100 + yy / 400 - 477;
        int days = (y - 1970) * 365 + leaps + doy;
        return (long) days * 1440L + h * 60L + mi;
    }

    private static int monthOffset(int m) {
        switch (m) {
            case 1: return 0; case 2: return 31; case 3: return 59;
            case 4: return 90; case 5: return 120; case 6: return 151;
            case 7: return 181; case 8: return 212; case 9: return 243;
            case 10: return 273; case 11: return 304; default: return 334;
        }
    }
}
