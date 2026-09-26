package com.odilo.library.domain.policy;

import com.odilo.library.domain.model.Tier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.OptionalInt;

/** An empty maximumRenewals means renewals are unlimited. */
public record TierPolicy(Tier tier, int loanDurationDays, int maximumActiveLoans, OptionalInt maximumRenewals) {

    public TierPolicy {
        Objects.requireNonNull(tier, "tier cannot be null");
        Objects.requireNonNull(maximumRenewals, "maximum renewals cannot be null");
        if (loanDurationDays <= 0) {
            throw new IllegalArgumentException("loan duration must be positive");
        }
        if (maximumActiveLoans <= 0) {
            throw new IllegalArgumentException("maximum active loans must be positive");
        }
        // usaremos OptionalInt para representar renovaciones ilimitadas
        if (maximumRenewals.isPresent() && maximumRenewals.getAsInt() < 0) {
            throw new IllegalArgumentException("maximum renewals cannot be negative");
        }
    }

    public Instant dueAt(Instant startedAt) {
        return Objects.requireNonNull(startedAt, "start time cannot be null").plus(loanDurationDays, ChronoUnit.DAYS);
    }
}
