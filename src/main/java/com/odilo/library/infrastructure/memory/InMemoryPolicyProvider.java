package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.policy.FinePolicy;
import com.odilo.library.domain.policy.PolicyProvider;
import com.odilo.library.domain.policy.TierPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryPolicyProvider implements PolicyProvider {

    private volatile FinePolicy currentFinePolicy;
    private volatile Duration holdPickupWindow = Duration.ofHours(48);
    private final Map<Tier, TierPolicy> tierPolicies = new ConcurrentHashMap<>();

    public InMemoryPolicyProvider(FinePolicy initialFinePolicy) {
        this.currentFinePolicy = Objects.requireNonNull(initialFinePolicy, "initial fine policy cannot be null");
        tierPolicies.put(Tier.STANDARD, new TierPolicy(Tier.STANDARD, 14, 3, OptionalInt.of(2)));
        tierPolicies.put(Tier.STUDENT, new TierPolicy(Tier.STUDENT, 28, 5, OptionalInt.of(3)));
        tierPolicies.put(Tier.STAFF, new TierPolicy(Tier.STAFF, 56, 10, OptionalInt.empty()));
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

    @Override
    public TierPolicy tierPolicy(Tier tier) {
        return tierPolicies.get(Objects.requireNonNull(tier, "tier cannot be null"));
    }

    @Override
    public Duration holdPickupWindow() {
        return holdPickupWindow;
    }

    public void updateFinePolicy(FinePolicy finePolicy) {
        currentFinePolicy = Objects.requireNonNull(finePolicy, "fine policy cannot be null");
    }

    public void updateTierPolicy(TierPolicy tierPolicy) {
        Objects.requireNonNull(tierPolicy, "tier policy cannot be null");
        tierPolicies.put(tierPolicy.tier(), tierPolicy);
    }

    public void updateHoldPickupWindow(Duration window) {
        Objects.requireNonNull(window, "hold pickup window cannot be null");
        if (window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("hold pickup window must be positive");
        }
        holdPickupWindow = window;
    }
}
