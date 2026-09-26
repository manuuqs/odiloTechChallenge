package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MemberTest {

    @Test
    void newMemberStartsWithZeroOutstandingBalance() {
        MemberId id = new MemberId("member-1");

        Member member = new Member(id, "Alex", Tier.STUDENT);

        assertEquals(id, member.id());
        assertEquals("Alex", member.name());
        assertEquals(Tier.STUDENT, member.tier());
        assertEquals(Money.ZERO, member.outstandingBalance());
    }

    @Test
    void rejectsMissingIdentifierTierOrInvalidName() {
        MemberId id = new MemberId("member-1");

        assertThrows(NullPointerException.class, () -> new Member(null, "Alex", Tier.STUDENT));
        assertThrows(NullPointerException.class, () -> new Member(id, null, Tier.STUDENT));
        assertThrows(NullPointerException.class, () -> new Member(id, "Alex", null));
        assertThrows(IllegalArgumentException.class, () -> new Member(id, " ", Tier.STUDENT));
        assertThrows(IllegalArgumentException.class, () -> new Member(id, " Alex", Tier.STUDENT));
        assertThrows(IllegalArgumentException.class, () -> new Member(id, "Alex ", Tier.STUDENT));
    }

    @Test
    void balanceCanBeUpdatedWithoutAllowingNull() {
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STUDENT);
        Money debt = new Money(new BigDecimal("10.01"));

        member.updateOutstandingBalance(debt);

        assertEquals(debt, member.outstandingBalance());
        assertThrows(NullPointerException.class, () -> member.updateOutstandingBalance(null));
        assertEquals(debt, member.outstandingBalance());
    }

    @Test
    void newFineAccumulatesWithOutstandingBalance() {
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STUDENT);
        member.updateOutstandingBalance(new Money(new BigDecimal("9.95")));

        member.addOutstandingBalance(new Money(new BigDecimal("0.20")));

        assertEquals(new Money(new BigDecimal("10.15")), member.outstandingBalance());
        assertThrows(NullPointerException.class, () -> member.addOutstandingBalance(null));
        assertEquals(new Money(new BigDecimal("10.15")), member.outstandingBalance());
    }
}
