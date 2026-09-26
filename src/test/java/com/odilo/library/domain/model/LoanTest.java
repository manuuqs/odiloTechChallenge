package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.policy.TierPolicy;
import com.odilo.library.infrastructure.memory.InMemoryPolicyProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class LoanTest {

    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void dueDateUsesCurrentTierPolicyAndIsNotChangedByLaterConfigurationUpdates() {
        InMemoryPolicyProvider policies = InMemoryPolicyProvider.withChallengeDefaults();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        Instant startedAt = Instant.now(clock);
        Loan loan = new Loan(new LoanId("loan-1"), new MemberId("member-1"), new CopyId("copy-1"),
                startedAt, policies.tierPolicy(Tier.STANDARD).dueAt(startedAt));

        assertEquals(Instant.parse("2026-10-10T12:00:00Z"), loan.dueAt());
        assertEquals(0, loan.renewalCount());
        assertTrue(loan.returnedAt().isEmpty());

        policies.updateTierPolicy(new TierPolicy(Tier.STANDARD, 21, 4, OptionalInt.of(1)));
        assertEquals(Instant.parse("2026-10-10T12:00:00Z"), loan.dueAt());
    }

    @Test
    void returnRecordsTimeOnceEvenWhenOverdue() {
        Loan loan = loan();
        Instant returnedAt = START.plusSeconds(15 * 24 * 60 * 60);

        loan.markReturned(returnedAt);

        assertEquals(returnedAt, loan.returnedAt().orElseThrow());
        assertThrows(DomainException.class, () -> loan.markReturned(returnedAt));
    }

    @Test
    void renewalAdvancesDueDateAndCountOnlyForValidActiveLoans() {
        Loan loan = loan();
        Instant originalDue = loan.dueAt();

        assertThrows(NullPointerException.class, () -> loan.renewUntil(null));
        assertThrows(IllegalArgumentException.class, () -> loan.renewUntil(originalDue));
        assertThrows(IllegalArgumentException.class, () -> loan.renewUntil(originalDue.minusSeconds(1)));
        assertEquals(originalDue, loan.dueAt());
        assertEquals(0, loan.renewalCount());

        loan.renewUntil(originalDue.plusSeconds(14 * 24 * 60 * 60));
        assertEquals(originalDue.plusSeconds(14 * 24 * 60 * 60), loan.dueAt());
        assertEquals(1, loan.renewalCount());

        loan.markReturned(START.plusSeconds(1));
        assertThrows(DomainException.class, () -> loan.renewUntil(loan.dueAt().plusSeconds(1)));
        assertEquals(1, loan.renewalCount());
    }

    @Test
    void rejectsInvalidDatesWithoutChangingTheLoan() {
        assertThrows(IllegalArgumentException.class,
                () -> new Loan(new LoanId("loan-1"), new MemberId("member-1"), new CopyId("copy-1"), START, START));
        assertThrows(NullPointerException.class,
                () -> new Loan(null, new MemberId("member-1"), new CopyId("copy-1"), START, START.plusSeconds(1)));
        assertThrows(NullPointerException.class,
                () -> new Loan(new LoanId("loan-1"), new MemberId("member-1"), new CopyId("copy-1"), START, null));

        Loan loan = loan();
        assertThrows(IllegalArgumentException.class, () -> loan.markReturned(START.minusSeconds(1)));
        assertThrows(NullPointerException.class, () -> loan.markReturned(null));
        assertTrue(loan.returnedAt().isEmpty());
    }

    private static Loan loan() {
        return new Loan(new LoanId("loan-1"), new MemberId("member-1"), new CopyId("copy-1"),
                START, START.plusSeconds(14 * 24 * 60 * 60));
    }
}
