package com.odilo.library.domain.policy;

import com.odilo.library.domain.model.Money;
import java.util.Objects;

public record FinePolicy(Money dailyOverdueFine, Money borrowingBlockThreshold) {

    public FinePolicy {
        Objects.requireNonNull(dailyOverdueFine, "daily overdue fine cannot be null");
        Objects.requireNonNull(borrowingBlockThreshold, "borrowing block threshold cannot be null");
    }
}
