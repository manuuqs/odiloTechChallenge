package com.odilo.library.domain.model;

import java.util.Objects;

public class Member {

    private final MemberId id;
    private final String name;
    private final Tier tier;
    private Money outstandingBalance;

    public Member(MemberId id, String name, Tier tier) {
        this.id = Objects.requireNonNull(id, "member ID cannot be null");
        this.name = Objects.requireNonNull(name, "member name cannot be null");
        if (name.isBlank() || !name.equals(name.strip())) {
            throw new IllegalArgumentException("member name cannot be blank or have surrounding whitespace");
        }
        this.tier = Objects.requireNonNull(tier, "member tier cannot be null");
        this.outstandingBalance = Money.ZERO;
    }

    public MemberId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Tier tier() {
        return tier;
    }

    public Money outstandingBalance() {
        return outstandingBalance;
    }

    public void updateOutstandingBalance(Money amount) {
        outstandingBalance = Objects.requireNonNull(amount, "outstanding balance cannot be null");
    }
}
