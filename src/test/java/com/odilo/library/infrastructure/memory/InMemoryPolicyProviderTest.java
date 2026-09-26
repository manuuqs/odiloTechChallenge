package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.policy.FinePolicy;
import com.odilo.library.domain.policy.PolicyProvider;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InMemoryPolicyProviderTest {

    @Test
    void providesInitialChallengeFineAmounts() {
        PolicyProvider provider = InMemoryPolicyProvider.withChallengeDefaults();

        assertEquals(new Money(new BigDecimal("0.20")), provider.finePolicy().dailyOverdueFine());
        assertEquals(new Money(new BigDecimal("10.00")), provider.finePolicy().borrowingBlockThreshold());
    }

    @Test
    void updatesFineAmountsWithoutRecreatingTheProvider() {
        InMemoryPolicyProvider configurable = InMemoryPolicyProvider.withChallengeDefaults();
        PolicyProvider consumer = configurable;
        FinePolicy updated = new FinePolicy(
                new Money(new BigDecimal("0.35")),
                new Money(new BigDecimal("15.00")));

        configurable.updateFinePolicy(updated);

        assertEquals(updated, consumer.finePolicy());
        assertThrows(NullPointerException.class, () -> configurable.updateFinePolicy(null));
        assertEquals(updated, consumer.finePolicy());
    }
}
