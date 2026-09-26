package com.odilo.library.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** A non-negative amount in euros, expressed to the cent. */
public record Money(BigDecimal amount) {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount cannot be null");
        if (amount.signum() < 0 || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must be non-negative and representable in cents");
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
}
