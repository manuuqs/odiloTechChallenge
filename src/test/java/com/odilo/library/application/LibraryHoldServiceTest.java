package com.odilo.library.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldStatus;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.CopyRepository;
import com.odilo.library.domain.repository.HoldRepository;
import com.odilo.library.domain.repository.LoanRepository;
import com.odilo.library.domain.repository.MemberRepository;
import com.odilo.library.domain.repository.TitleRepository;
import com.odilo.library.infrastructure.memory.InMemoryCopyRepository;
import com.odilo.library.infrastructure.memory.InMemoryHoldRepository;
import com.odilo.library.infrastructure.memory.InMemoryLoanRepository;
import com.odilo.library.infrastructure.memory.InMemoryMemberRepository;
import com.odilo.library.infrastructure.memory.InMemoryPolicyProvider;
import com.odilo.library.infrastructure.memory.InMemoryTitleRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LibraryHoldServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final TitleId TITLE = new TitleId("title-1");
    private final TitleRepository titles = new InMemoryTitleRepository();
    private final CopyRepository copies = new InMemoryCopyRepository();
    private final MemberRepository members = new InMemoryMemberRepository();
    private final LoanRepository loans = new InMemoryLoanRepository();
    private final HoldRepository holds = new InMemoryHoldRepository();
    private final InMemoryPolicyProvider policies = InMemoryPolicyProvider.withChallengeDefaults();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void createsWaitingHoldWithOutstandingBalanceExactlyAtThreshold() {
        titles.save(new Title(TITLE, "Clean Code"));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        member.updateOutstandingBalance(new Money(new BigDecimal("10.00")));
        members.save(member);

        Hold hold = service().placeHold(member.id(), TITLE);

        assertEquals(member.id(), hold.memberId());
        assertEquals(TITLE, hold.titleId());
        assertEquals(NOW, hold.createdAt());
        assertEquals(HoldStatus.WAITING, hold.status());
        assertTrue(hold.assignedCopyId().isEmpty());
        assertEquals(0, loans.countActiveByMemberId(member.id()));
        assertEquals(hold, holds.findById(hold.id()).orElseThrow());
    }

    @Test
    void balanceAboveThresholdBlocksJoiningTheQueue() {
        titles.save(new Title(TITLE, "Clean Code"));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        member.updateOutstandingBalance(new Money(new BigDecimal("10.01")));
        members.save(member);

        DomainException rejected = assertThrows(DomainException.class,
                () -> service().placeHold(member.id(), TITLE));

        assertEquals("outstanding fines block holds", rejected.getMessage());
        assertTrue(holds.findActiveByTitleId(TITLE).isEmpty());
    }

    @Test
    void fineFromLateReturnBlocksJoiningAnotherTitlesQueue() {
        titles.save(new Title(TITLE, "Clean Code"));
        TitleId otherTitle = new TitleId("title-2");
        titles.save(new Title(otherTitle, "Refactoring"));
        copies.save(new Copy(new CopyId("copy-1"), TITLE));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        Loan loan = (Loan) service().borrow(member.id(), TITLE);
        member.updateOutstandingBalance(new Money(new BigDecimal("10.00")));

        Instant returnedAt = loan.dueAt().plus(1, ChronoUnit.DAYS);
        serviceAt(returnedAt).returnLoan(loan.id());

        assertEquals(new Money(new BigDecimal("10.20")), member.outstandingBalance());
        assertThrows(DomainException.class, () -> serviceAt(returnedAt).placeHold(member.id(), otherTitle));
        assertTrue(holds.findActiveByTitleId(otherTitle).isEmpty());
    }

    @Test
    void rejectsDuplicateActiveHoldAndAnAvailableCopy() {
        titles.save(new Title(TITLE, "Clean Code"));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);

        service().placeHold(member.id(), TITLE);
        assertThrows(DomainException.class, () -> service().placeHold(member.id(), TITLE));
        assertEquals(1, holds.findWaitingByTitleId(TITLE).size());

        TitleId anotherTitle = new TitleId("title-2");
        titles.save(new Title(anotherTitle, "Refactoring"));
        copies.save(new Copy(new CopyId("copy-2"), anotherTitle));
        assertThrows(DomainException.class, () -> service().placeHold(member.id(), anotherTitle));
        assertTrue(holds.findActiveByTitleId(anotherTitle).isEmpty());
    }

    @Test
    void rejectsHoldingATitleTheMemberAlreadyBorrowed() {
        titles.save(new Title(TITLE, "Clean Code"));
        copies.save(new Copy(new CopyId("copy-1"), TITLE));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        service().borrow(member.id(), TITLE);

        assertThrows(DomainException.class, () -> service().placeHold(member.id(), TITLE));
        assertTrue(holds.findWaitingByTitleId(TITLE).isEmpty());
    }

    @Test
    void placingHoldRejectsActiveLoanWhoseCopyDoesNotExist() {
        titles.save(new Title(TITLE, "Clean Code"));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        loans.save(new Loan(new LoanId("orphan-loan"), member.id(), new CopyId("missing-copy"),
                NOW, NOW.plus(14, ChronoUnit.DAYS)));

        DomainException rejected = assertThrows(DomainException.class,
                () -> service().placeHold(member.id(), TITLE));

        assertEquals("active loan copy not found: missing-copy", rejected.getMessage());
        assertTrue(holds.findActiveByTitleId(TITLE).isEmpty());
    }

    @Test
    void preservesFifoEvenWhenClockReturnsTheSameInstant() {
        titles.save(new Title(TITLE, "Clean Code"));
        Member first = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        Member second = new Member(new MemberId("member-2"), "Pat", Tier.STUDENT);
        members.save(first);
        members.save(second);

        Hold firstHold = service().placeHold(first.id(), TITLE);
        Hold secondHold = service().placeHold(second.id(), TITLE);

        assertEquals(NOW, firstHold.createdAt());
        assertEquals(NOW, secondHold.createdAt());
        assertEquals(List.of(firstHold, secondHold), holds.findWaitingByTitleId(TITLE));
    }

    @Test
    void rejectsUnknownMembersTitlesAndNullInputs() {
        titles.save(new Title(TITLE, "Clean Code"));
        MemberId memberId = new MemberId("member-1");
        assertThrows(NullPointerException.class, () -> service().placeHold(null, TITLE));
        assertThrows(NullPointerException.class, () -> service().placeHold(memberId, null));
        assertThrows(DomainException.class, () -> service().placeHold(memberId, TITLE));

        members.save(new Member(memberId, "Alex", Tier.STANDARD));
        assertThrows(DomainException.class, () -> service().placeHold(memberId, new TitleId("missing")));
        assertTrue(holds.findWaitingByTitleId(TITLE).isEmpty());
    }

    @Test
    void simultaneousRequestsFromOneMemberCreateOnlyOneHold() throws Exception {
        titles.save(new Title(TITLE, "Clean Code"));
        MemberId memberId = new MemberId("member-1");
        members.save(new Member(memberId, "Alex", Tier.STANDARD));
        LibraryService firstService = service();
        LibraryService secondService = service();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attemptHold(firstService, memberId, ready, go));
            Future<Boolean> second = executor.submit(attemptHold(secondService, memberId, ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            int created = (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, created);
            assertEquals(1, holds.findWaitingByTitleId(TITLE).size());
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> attemptHold(LibraryService service, MemberId memberId,
                                          CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("other hold request did not start");
            }
            try {
                service.placeHold(memberId, TITLE);
                return true;
            } catch (DomainException duplicate) {
                assertEquals("member already has an active hold for this title", duplicate.getMessage());
                return false;
            }
        };
    }

    private LibraryService service() {
        return new LibraryService(titles, copies, members, loans, holds, policies, clock);
    }

    private LibraryService serviceAt(Instant at) {
        return new LibraryService(titles, copies, members, loans, holds, policies,
                Clock.fixed(at, ZoneOffset.UTC));
    }
}
