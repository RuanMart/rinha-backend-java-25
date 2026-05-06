package rinha.model;

import java.util.Set;

public final class FraudRequest {

    public String id;
    public Transaction transaction;
    public Customer customer;
    public Merchant merchant;
    public Terminal terminal;
    public LastTransaction last_transaction;

    public static final class Transaction {
        public double amount;
        public int installments;
        public String requested_at;
    }

    public static final class Customer {
        public double avg_amount;
        public int tx_count_24h;
        public Set<String> known_merchants;
    }

    public static final class Merchant {
        public String id;
        public String mcc;
        public double avg_amount;
    }

    public static final class Terminal {
        public boolean is_online;
        public boolean card_present;
        public double km_from_home;
    }

    public static final class LastTransaction {
        public String timestamp;
        public double km_from_current;
    }
}
