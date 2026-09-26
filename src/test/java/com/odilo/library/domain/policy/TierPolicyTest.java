package com.odilo.library.domain.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.odilo.library.domain.model.Tier;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class TierPolicyTest {

    @Test
    void rejectsMissingTierOrRenewalLimit() {
        assertThrows(NullPointerException.class, () -> new TierPolicy(null, 14, 3, OptionalInt.of(2)));
        assertThrows(NullPointerException.class, () -> new TierPolicy(Tier.STANDARD, 14, 3, null));
    }

    @Test
    void rejectsNonPositiveDurationAndLoanLimitOrNegativeRenewals() {
        assertThrows(IllegalArgumentException.class, () -> new TierPolicy(Tier.STANDARD, 0, 3, OptionalInt.of(2)));
        assertThrows(IllegalArgumentException.class, () -> new TierPolicy(Tier.STANDARD, -1, 3, OptionalInt.of(2)));
        assertThrows(IllegalArgumentException.class, () -> new TierPolicy(Tier.STANDARD, 14, 0, OptionalInt.of(2)));
        assertThrows(IllegalArgumentException.class, () -> new TierPolicy(Tier.STANDARD, 14, -1, OptionalInt.of(2)));
        assertThrows(IllegalArgumentException.class, () -> new TierPolicy(Tier.STANDARD, 14, 3, OptionalInt.of(-1)));
    }

    @Test
    void allowsZeroOrUnlimitedRenewals() {
        assertEquals(OptionalInt.of(0), new TierPolicy(Tier.STANDARD, 14, 3, OptionalInt.of(0)).maximumRenewals());
        assertEquals(OptionalInt.empty(), new TierPolicy(Tier.STAFF, 56, 10, OptionalInt.empty()).maximumRenewals());
    }
}
