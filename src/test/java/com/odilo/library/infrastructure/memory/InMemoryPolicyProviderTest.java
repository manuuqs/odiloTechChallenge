package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.policy.FinePolicy;
import com.odilo.library.domain.policy.PolicyProvider;
import com.odilo.library.domain.policy.TierPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.OptionalInt;
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

    @Test
    void providesInitialChallengeTierRules() {
        PolicyProvider provider = InMemoryPolicyProvider.withChallengeDefaults();

        assertEquals(new TierPolicy(Tier.STANDARD, 14, 3, OptionalInt.of(2)), provider.tierPolicy(Tier.STANDARD));
        assertEquals(new TierPolicy(Tier.STUDENT, 28, 5, OptionalInt.of(3)), provider.tierPolicy(Tier.STUDENT));
        assertEquals(new TierPolicy(Tier.STAFF, 56, 10, OptionalInt.empty()), provider.tierPolicy(Tier.STAFF));
    }

    @Test
    void updatesOnlyTheChosenTierWithoutRecreatingTheProvider() {
        InMemoryPolicyProvider configurable = InMemoryPolicyProvider.withChallengeDefaults();
        PolicyProvider consumer = configurable;
        TierPolicy unchangedStudent = consumer.tierPolicy(Tier.STUDENT);
        TierPolicy updated = new TierPolicy(Tier.STANDARD, 21, 4, OptionalInt.of(1));

        configurable.updateTierPolicy(updated);

        assertEquals(updated, consumer.tierPolicy(Tier.STANDARD));
        assertEquals(unchangedStudent, consumer.tierPolicy(Tier.STUDENT));
        assertEquals(new Money(new BigDecimal("0.20")), consumer.finePolicy().dailyOverdueFine());
        assertThrows(NullPointerException.class, () -> configurable.updateTierPolicy(null));
        assertEquals(updated, consumer.tierPolicy(Tier.STANDARD));
    }

    @Test
    void rejectsMissingTierWhenLookingUpItsPolicy() {
        PolicyProvider provider = InMemoryPolicyProvider.withChallengeDefaults();

        assertThrows(NullPointerException.class, () -> provider.tierPolicy(null));
    }

    @Test
    void holdPickupWindowCanChangeWithoutRecreatingTheProvider() {
        InMemoryPolicyProvider configurable = InMemoryPolicyProvider.withChallengeDefaults();
        PolicyProvider consumer = configurable;

        assertEquals(Duration.ofHours(48), consumer.holdPickupWindow());
        configurable.updateHoldPickupWindow(Duration.ofHours(24));
        assertEquals(Duration.ofHours(24), consumer.holdPickupWindow());
        assertThrows(IllegalArgumentException.class, () -> configurable.updateHoldPickupWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> configurable.updateHoldPickupWindow(Duration.ofHours(-1)));
        assertThrows(NullPointerException.class, () -> configurable.updateHoldPickupWindow(null));
        assertEquals(Duration.ofHours(24), consumer.holdPickupWindow());
    }
}
