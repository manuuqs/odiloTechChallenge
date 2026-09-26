package com.odilo.library.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.odilo.library.domain.model.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class FinePolicyTest {

    private static final Instant DUE = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    void requiresBothFineAmounts() {
        Money amount = new Money(new BigDecimal("1.00"));

        assertThrows(NullPointerException.class, () -> new FinePolicy(null, amount));
        assertThrows(NullPointerException.class, () -> new FinePolicy(amount, null));
    }

    @Test
    void onlyFullTwentyFourHourPeriodsAfterDueDateAreCharged() {
        FinePolicy policy = new FinePolicy(new Money(new BigDecimal("0.20")), Money.ZERO);

        assertEquals(Money.ZERO, policy.fineFor(DUE, DUE.minusSeconds(1)));
        assertEquals(Money.ZERO, policy.fineFor(DUE, DUE));
        assertEquals(Money.ZERO, policy.fineFor(DUE, DUE.plus(1, ChronoUnit.DAYS).minusNanos(1)));
        assertEquals(new Money(new BigDecimal("0.20")), policy.fineFor(DUE, DUE.plus(1, ChronoUnit.DAYS)));
        assertEquals(new Money(new BigDecimal("0.20")),
                policy.fineFor(DUE, DUE.plus(2, ChronoUnit.DAYS).minusNanos(1)));
        assertEquals(new Money(new BigDecimal("0.40")), policy.fineFor(DUE, DUE.plus(2, ChronoUnit.DAYS)));
        assertThrows(NullPointerException.class, () -> policy.fineFor(null, DUE));
        assertThrows(NullPointerException.class, () -> policy.fineFor(DUE, null));
    }

    @Test
    void calculationUsesThisPolicyDailyRate() {
        FinePolicy updated = new FinePolicy(new Money(new BigDecimal("0.35")), Money.ZERO);

        assertEquals(new Money(new BigDecimal("1.05")),
                updated.fineFor(DUE, DUE.plus(3, ChronoUnit.DAYS).plusSeconds(1)));
    }
}
