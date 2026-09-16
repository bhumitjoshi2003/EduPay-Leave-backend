package com.indraacademy.ias_management.service;

import org.springframework.stereotype.Component;

/** Deterministic, paise-native calculator for ONLINE_CONVENIENCE_FEE_V1. Deliberately knows
 * nothing about the database, HTTP, security, or Razorpay — every pricing input (rate/tax/fee)
 * is supplied explicitly by the caller, resolved beforehand from PaymentPricingService's
 * CURRENT/FUTURE effective-dated configuration (never a hardcoded default, never an
 * environment variable). Kept as a stateless @Component so it can still be @Autowired at
 * existing call sites; add no per-instance state here. */
@Component
public class OnlinePaymentPricingCalculator {
    public static final String PRICING_VERSION = "ONLINE_CONVENIENCE_FEE_V1";
    private static final long BPS_DENOMINATOR = 10_000L;
    private static final long COMBINED_DENOMINATOR = BPS_DENOMINATOR * BPS_DENOMINATOR;

    /** Callable both as an instance method (existing @Autowired call sites,
     * {@code paymentPricingCalculator.calculate(...)}) and, since it's static, directly on the
     * class (OnlinePaymentPricingCalculatorTest's existing calls) — one implementation, no
     * duplicate overload. Every pricing input is explicit; nothing is read from any field. */
    public static Pricing calculate(long principal, long other, int rateBps, int taxRateBps, long transactionFee) {
        if (principal < 0 || other < 0 || transactionFee < 0) {
            throw new IllegalArgumentException("Payment pricing amounts cannot be negative");
        }
        if (rateBps < 0 || rateBps >= BPS_DENOMINATOR || taxRateBps < 0 || taxRateBps >= BPS_DENOMINATOR) {
            throw new IllegalArgumentException("Payment pricing rates must be between 0 and 9999 basis points");
        }

        long qNumerator = Math.multiplyExact((long) rateBps, BPS_DENOMINATOR + taxRateBps);
        long denominator = COMBINED_DENOMINATOR - qNumerator;
        if (denominator <= 0) {
            throw new IllegalArgumentException("Combined gateway pricing rate must be less than 100%");
        }
        long base = Math.addExact(Math.addExact(principal, other), transactionFee);
        long total = ceilMultiplyDivide(base, COMBINED_DENOMINATOR, denominator);
        long gatewayRecovery = Math.subtractExact(total, base);
        long convenience = Math.addExact(gatewayRecovery, transactionFee);
        return new Pricing(principal, other, rateBps, taxRateBps, gatewayRecovery,
                transactionFee, convenience, total);
    }

    private static long ceilMultiplyDivide(long value, long multiplier, long divisor) {
        if (value == 0) return 0;
        java.math.BigInteger numerator = java.math.BigInteger.valueOf(value)
                .multiply(java.math.BigInteger.valueOf(multiplier));
        java.math.BigInteger[] result = numerator.divideAndRemainder(java.math.BigInteger.valueOf(divisor));
        java.math.BigInteger rounded = result[1].signum() == 0 ? result[0] : result[0].add(java.math.BigInteger.ONE);
        return rounded.longValueExact();
    }

    public record Pricing(long schoolLiabilityPrincipalPaise,
                          long otherCapturedNonConveniencePaise,
                          int gatewayRateBps,
                          int gatewayTaxRateBps,
                          long gatewayRecoveryFeePaise,
                          long edunexifyTransactionFeePaise,
                          long onlineConvenienceFeePaise,
                          long totalPayablePaise) {}
}
