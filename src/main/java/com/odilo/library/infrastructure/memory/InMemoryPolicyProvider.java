package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.policy.FinePolicy;
import com.odilo.library.domain.policy.PolicyProvider;
import java.math.BigDecimal;
import java.util.Objects;

public final class InMemoryPolicyProvider implements PolicyProvider {

    private volatile FinePolicy currentFinePolicy;

    public InMemoryPolicyProvider(FinePolicy initialFinePolicy) {
        this.currentFinePolicy = Objects.requireNonNull(initialFinePolicy, "initial fine policy cannot be null");
    }

    public static InMemoryPolicyProvider withChallengeDefaults() {
        return new InMemoryPolicyProvider(new FinePolicy(
                new Money(new BigDecimal("0.20")),
                new Money(new BigDecimal("10.00"))));
    }

    @Override
    public FinePolicy finePolicy() {
        return currentFinePolicy;
    }

    public void updateFinePolicy(FinePolicy finePolicy) {
        currentFinePolicy = Objects.requireNonNull(finePolicy, "fine policy cannot be null");
    }
}
