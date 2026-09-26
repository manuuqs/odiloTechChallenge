package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.HoldRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryHoldRepositoryTest {

    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");
    private static final TitleId TITLE = new TitleId("title-1");

    @Test
    void returnsActiveHoldsInFifoOrderAndExcludesCompletedOnes() {
        HoldRepository holds = new InMemoryHoldRepository();
        Hold first = hold("hold-1", "member-1", START);
        Hold tiedLater = hold("hold-2", "member-2", START);
        Hold later = hold("hold-3", "member-3", START.plusSeconds(1));
        Hold collected = hold("hold-4", "member-4", START.plusSeconds(2));
        Hold expired = hold("hold-5", "member-5", START.plusSeconds(3));
        first.assignCopy(new CopyId("copy-1"), START, START.plus(48, ChronoUnit.HOURS));
        collected.assignCopy(new CopyId("copy-4"), START.plusSeconds(2), START.plus(48, ChronoUnit.HOURS));
        collected.collectAt(START.plusSeconds(3));
        expired.assignCopy(new CopyId("copy-5"), START.plusSeconds(3), START.plus(48, ChronoUnit.HOURS));
        expired.expireAt(START.plus(48, ChronoUnit.HOURS));
        holds.save(later);
        holds.save(expired);
        holds.save(tiedLater);
        holds.save(collected);
        holds.save(first);

        List<Hold> active = holds.findActiveByTitleId(TITLE);
        assertEquals(List.of(first, tiedLater, later), active);
        assertEquals(List.of(tiedLater, later), holds.findWaitingByTitleId(TITLE));
        assertTrue(holds.existsActiveByMemberAndTitle(first.memberId(), TITLE));
        assertFalse(holds.existsActiveByMemberAndTitle(collected.memberId(), TITLE));
        assertFalse(holds.existsActiveByMemberAndTitle(expired.memberId(), TITLE));
        assertTrue(holds.findActiveByTitleId(new TitleId("other-title")).isEmpty());
        assertEquals(collected, holds.findById(collected.id()).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> active.add(collected));
    }

    @Test
    void rejectsNullInputs() {
        HoldRepository holds = new InMemoryHoldRepository();
        assertThrows(NullPointerException.class, () -> holds.findById(null));
        assertThrows(NullPointerException.class, () -> holds.findActiveByTitleId(null));
        assertThrows(NullPointerException.class, () -> holds.findWaitingByTitleId(null));
        assertThrows(NullPointerException.class, () -> holds.existsActiveByMemberAndTitle(null, TITLE));
        assertThrows(NullPointerException.class, () -> holds.existsActiveByMemberAndTitle(new MemberId("member-1"), null));
        assertThrows(NullPointerException.class, () -> holds.save(null));
    }

    private static Hold hold(String id, String member, Instant createdAt) {
        return new Hold(new HoldId(id), new MemberId(member), TITLE, createdAt);
    }
}
