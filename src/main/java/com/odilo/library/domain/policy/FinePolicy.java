package com.odilo.library.domain.policy;

import com.odilo.library.domain.model.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public record FinePolicy(Money dailyOverdueFine, Money borrowingBlockThreshold) {

    public FinePolicy {
        Objects.requireNonNull(dailyOverdueFine, "daily overdue fine cannot be null");
        Objects.requireNonNull(borrowingBlockThreshold, "borrowing block threshold cannot be null");
    }

    public Money fineFor(Instant dueAt, Instant returnedAt) {
        Objects.requireNonNull(dueAt, "due time cannot be null");
        Objects.requireNonNull(returnedAt, "return time cannot be null");
        if (!returnedAt.isAfter(dueAt)) {
            return Money.ZERO;
        }
        long fullDaysOverdue = Duration.between(dueAt, returnedAt).toDays();
        return fullDaysOverdue == 0 ? Money.ZERO : dailyOverdueFine.multiply(fullDaysOverdue);
    }
}
