package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.infrastructure.memory.InMemoryPolicyProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HoldTest {

    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final CopyId COPY_ID = new CopyId("copy-1");

    @Test
    void newHoldWaitsUntilAssignedAndCanBeCollectedBeforeExpiry() {
        Hold hold = hold();
        InMemoryPolicyProvider policies = InMemoryPolicyProvider.withChallengeDefaults();
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        Instant assignedAt = Instant.now(clock);

        assertEquals(HoldStatus.WAITING, hold.status());
        assertTrue(hold.assignedCopyId().isEmpty());
        assertTrue(hold.expiresAt().isEmpty());

        hold.assignCopy(COPY_ID, assignedAt, assignedAt.plus(policies.holdPickupWindow()));

        assertEquals(HoldStatus.ASSIGNED, hold.status());
        assertEquals(COPY_ID, hold.assignedCopyId().orElseThrow());
        assertEquals(Instant.parse("2026-09-28T12:00:00Z"), hold.expiresAt().orElseThrow());
        hold.collectAt(hold.expiresAt().orElseThrow().minusNanos(1));
        assertEquals(HoldStatus.COLLECTED, hold.status());
        assertThrows(DomainException.class, () -> hold.expireAt(hold.expiresAt().orElseThrow()));
    }

    @Test
    void expiresAtExactDeadlineAndCannotBeCollectedOrReassigned() {
        Hold hold = hold();
        Instant deadline = START.plusSeconds(48 * 60 * 60);
        hold.assignCopy(COPY_ID, START, deadline);

        assertThrows(DomainException.class, () -> hold.expireAt(deadline.minusNanos(1)));
        assertThrows(DomainException.class, () -> hold.collectAt(deadline));
        assertEquals(HoldStatus.ASSIGNED, hold.status());

        hold.expireAt(deadline);
        assertEquals(HoldStatus.EXPIRED, hold.status());
        assertThrows(DomainException.class, () -> hold.collectAt(deadline));
        assertThrows(DomainException.class, () -> hold.assignCopy(COPY_ID, deadline, deadline.plusSeconds(1)));
    }

    @Test
    void rejectsMissingReferencesAndInvalidAssignmentTimes() {
        assertThrows(NullPointerException.class,
                () -> new Hold(null, new MemberId("member-1"), new TitleId("title-1"), START));
        assertThrows(NullPointerException.class,
                () -> new Hold(new HoldId("hold-1"), new MemberId("member-1"), new TitleId("title-1"), null));

        Hold hold = hold();
        assertThrows(NullPointerException.class, () -> hold.assignCopy(null, START, START.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> hold.assignCopy(COPY_ID, START.minusSeconds(1), START.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> hold.assignCopy(COPY_ID, START, START));
        assertEquals(HoldStatus.WAITING, hold.status());
        assertThrows(DomainException.class, () -> hold.collectAt(START));

        hold.assignCopy(COPY_ID, START, START.plusSeconds(1));
        assertThrows(IllegalArgumentException.class, () -> hold.collectAt(START.minusSeconds(1)));
        assertEquals(HoldStatus.ASSIGNED, hold.status());
    }

    private static Hold hold() {
        return new Hold(new HoldId("hold-1"), new MemberId("member-1"), new TitleId("title-1"), START);
    }
}
