package rinha.model;

public final class FraudResponse {

    public final boolean approved;
    public final double fraud_score;

    public FraudResponse(boolean approved, double fraud_score) {
        this.approved = approved;
        this.fraud_score = fraud_score;
    }
}
