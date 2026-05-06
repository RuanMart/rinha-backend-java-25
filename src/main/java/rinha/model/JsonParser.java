package rinha.model;

import java.util.HashSet;
import java.util.Set;

public final class JsonParser {

    private final String json;
    private int pos;

    private JsonParser(String json) {
        this.json = json;
        this.pos = 0;
    }

    public static FraudRequest parse(String json) {
        if (json == null || json.isEmpty()) return null;
        JsonParser p = new JsonParser(json);
        return p.parseRoot();
    }

    private FraudRequest parseRoot() {
        expect('{');
        FraudRequest req = new FraudRequest();
        while (true) {
            skipWs();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            switch (key) {
                case "id" -> req.id = parseString();
                case "transaction" -> req.transaction = parseTransaction();
                case "customer" -> req.customer = parseCustomer();
                case "merchant" -> req.merchant = parseMerchant();
                case "terminal" -> req.terminal = parseTerminal();
                case "last_transaction" -> req.last_transaction = parseLastTransaction();
                default -> skipValue();
            }
        }
        return req;
    }

    private FraudRequest.Transaction parseTransaction() {
        expect('{');
        FraudRequest.Transaction t = new FraudRequest.Transaction();
        while (true) {
            skipWs();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            switch (key) {
                case "amount" -> t.amount = parseDouble();
                case "installments" -> t.installments = parseInt();
                case "requested_at" -> t.requested_at = parseString();
                default -> skipValue();
            }
        }
        return t;
    }

    private FraudRequest.Customer parseCustomer() {
        expect('{');
        FraudRequest.Customer c = new FraudRequest.Customer();
        while (true) {
            skipWs();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            switch (key) {
                case "avg_amount" -> c.avg_amount = parseDouble();
                case "tx_count_24h" -> c.tx_count_24h = parseInt();
                case "known_merchants" -> c.known_merchants = parseStringSet();
                default -> skipValue();
            }
        }
        return c;
    }

    private FraudRequest.Merchant parseMerchant() {
        expect('{');
        FraudRequest.Merchant m = new FraudRequest.Merchant();
        while (true) {
            skipWs();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            switch (key) {
                case "id" -> m.id = parseString();
                case "mcc" -> m.mcc = parseString();
                case "avg_amount" -> m.avg_amount = parseDouble();
                default -> skipValue();
            }
        }
        return m;
    }

    private FraudRequest.Terminal parseTerminal() {
        expect('{');
        FraudRequest.Terminal t = new FraudRequest.Terminal();
        while (true) {
            skipWs();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            switch (key) {
                case "is_online" -> t.is_online = parseBool();
                case "card_present" -> t.card_present = parseBool();
                case "km_from_home" -> t.km_from_home = parseDouble();
                default -> skipValue();
            }
        }
        return t;
    }

    private FraudRequest.LastTransaction parseLastTransaction() {
        skipWs();
        if (peek() == 'n') { pos += 4; return null; }
        expect('{');
        FraudRequest.LastTransaction lt = new FraudRequest.LastTransaction();
        while (true) {
            skipWs();
            if (peek() == '}') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            String key = parseString();
            skipWs();
            expect(':');
            skipWs();
            switch (key) {
                case "timestamp" -> lt.timestamp = parseString();
                case "km_from_current" -> lt.km_from_current = parseDouble();
                default -> skipValue();
            }
        }
        return lt;
    }

    private Set<String> parseStringSet() {
        expect('[');
        Set<String> set = new HashSet<>();
        while (true) {
            skipWs();
            if (peek() == ']') { pos++; break; }
            if (peek() == ',') { pos++; skipWs(); }
            set.add(parseString());
        }
        return set;
    }

    private String parseString() {
        skipWs();
        expect('"');
        int start = pos;
        while (pos < json.length() && json.charAt(pos) != '"') {
            if (json.charAt(pos) == '\\') pos++;
            pos++;
        }
        String s = json.substring(start, pos);
        pos++;
        return s;
    }

    private double parseDouble() {
        skipWs();
        int start = pos;
        if (pos < json.length() && json.charAt(pos) == '-') pos++;
        while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        if (pos < json.length() && json.charAt(pos) == '.') {
            pos++;
            while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        }
        if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
            pos++;
            if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) pos++;
            while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        }
        return Double.parseDouble(json.substring(start, pos));
    }

    private int parseInt() {
        skipWs();
        int start = pos;
        if (pos < json.length() && json.charAt(pos) == '-') pos++;
        while (pos < json.length() && isDigit(json.charAt(pos))) pos++;
        return Integer.parseInt(json.substring(start, pos));
    }

    private boolean parseBool() {
        skipWs();
        if (json.charAt(pos) == 't') { pos += 4; return true; }
        pos += 5;
        return false;
    }

    private void skipValue() {
        skipWs();
        char c = peek();
        if (c == '"') { pos++; while (json.charAt(pos) != '"') { if (json.charAt(pos) == '\\') pos++; pos++; } pos++; }
        else if (c == '{') { pos++; int depth = 1; while (depth > 0) { if (json.charAt(pos) == '{') depth++; else if (json.charAt(pos) == '}') depth--; else if (json.charAt(pos) == '"') { pos++; while (json.charAt(pos) != '"') { if (json.charAt(pos) == '\\') pos++; pos++; } } pos++; } }
        else if (c == '[') { pos++; int depth = 1; while (depth > 0) { if (json.charAt(pos) == '[') depth++; else if (json.charAt(pos) == ']') depth--; else if (json.charAt(pos) == '"') { pos++; while (json.charAt(pos) != '"') { if (json.charAt(pos) == '\\') pos++; pos++; } } pos++; } }
        else if (c == 'n') { pos += 4; }
        else if (c == 't') { pos += 4; }
        else if (c == 'f') { pos += 5; }
        else { while (pos < json.length() && ",}]".indexOf(json.charAt(pos)) < 0) pos++; }
    }

    private void skipWs() {
        while (pos < json.length() && json.charAt(pos) <= ' ') pos++;
    }

    private char peek() {
        return pos < json.length() ? json.charAt(pos) : '\0';
    }

    private void expect(char c) {
        if (peek() == c) { pos++; return; }
        throw new IllegalStateException("Expected '" + c + "' at pos " + pos + " but got '" + peek() + "'");
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
