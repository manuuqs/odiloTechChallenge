package com.odilo.library.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.CopyStatus;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LibraryServiceTest {

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
    void borrowingUsesCurrentTierPolicyAndRecordsTheSelectedCopy() {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy first = new Copy(new CopyId("copy-1"), TITLE);
        copies.save(first);
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STUDENT);
        members.save(member);

        Loan loan = service().borrow(member.id(), TITLE);

        assertEquals(first.id(), loan.copyId());
        assertEquals(member.id(), loan.memberId());
        assertEquals(NOW, loan.startedAt());
        assertEquals(NOW.plus(28, ChronoUnit.DAYS), loan.dueAt());
        assertEquals(CopyStatus.ON_LOAN, first.status());
        assertEquals(loan, loans.findActiveByCopyId(first.id()).orElseThrow());
        assertEquals(1, loans.countActiveByMemberId(member.id()));
        assertThrows(DomainException.class, () -> service().borrow(member.id(), TITLE));
    }

    @Test
    void borrowingUsesOneClockReadingForExpiryAndLoanStart() {
        titles.save(new Title(TITLE, "Clean Code"));
        copies.save(new Copy(new CopyId("copy-1"), TITLE));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        AtomicInteger clockReads = new AtomicInteger();
        LibraryService service = serviceWithClock(advancingClock(clockReads, ZoneOffset.UTC));

        Loan loan = service.borrow(member.id(), TITLE);

        assertEquals(NOW, loan.startedAt());
        assertEquals(NOW.plus(14, ChronoUnit.DAYS), loan.dueAt());
        assertEquals(1, clockReads.get());
    }

    @Test
    void placingHoldUsesOneClockReadingForExpiryAndCreation() {
        titles.save(new Title(TITLE, "Clean Code"));
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        AtomicInteger clockReads = new AtomicInteger();
        LibraryService service = serviceWithClock(advancingClock(clockReads, ZoneOffset.UTC));

        Hold hold = service.placeHold(member.id(), TITLE);

        assertEquals(NOW, hold.createdAt());
        assertEquals(1, clockReads.get());
    }

    @Test
    void borrowingRejectsActiveLoanWhoseCopyDoesNotExist() {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy available = new Copy(new CopyId("copy-1"), TITLE);
        copies.save(available);
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        loans.save(new Loan(new LoanId("orphan-loan"), member.id(), new CopyId("missing-copy"),
                NOW, NOW.plus(14, ChronoUnit.DAYS)));

        DomainException rejected = assertThrows(DomainException.class,
                () -> service().borrow(member.id(), TITLE));

        assertEquals("active loan copy not found: missing-copy", rejected.getMessage());
        assertEquals(CopyStatus.AVAILABLE, available.status());
        assertEquals(1, loans.countActiveByMemberId(member.id()));
    }

    @Test
    void memberCannotBorrowTwoCopiesOfTheSameTitleAtOnce() {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy first = new Copy(new CopyId("copy-1"), TITLE);
        Copy second = new Copy(new CopyId("copy-2"), TITLE);
        copies.save(first);
        copies.save(second);
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        Loan initial = service().borrow(member.id(), TITLE);

        DomainException rejected = assertThrows(DomainException.class,
                () -> service().borrow(member.id(), TITLE));

        assertEquals("member already has this title on loan", rejected.getMessage());
        assertEquals(CopyStatus.AVAILABLE, second.status());
        assertEquals(1, loans.countActiveByMemberId(member.id()));

        service().returnLoan(initial.id());
        assertEquals(member.id(), service().borrow(member.id(), TITLE).memberId());
        assertEquals(1, loans.countActiveByMemberId(member.id()));
    }

    @Test
    void outstandingFineMustBeStrictlyGreaterThanThresholdToBlock() {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy available = new Copy(new CopyId("copy-1"), TITLE);
        copies.save(available);
        Member blocked = new Member(new MemberId("blocked"), "Pat", Tier.STANDARD);
        blocked.updateOutstandingBalance(new Money(new BigDecimal("10.01")));
        members.save(blocked);
        assertThrows(DomainException.class, () -> service().borrow(blocked.id(), TITLE));
        assertEquals(CopyStatus.AVAILABLE, available.status());

        Member allowed = new Member(new MemberId("allowed"), "Lee", Tier.STANDARD);
        allowed.updateOutstandingBalance(new Money(new BigDecimal("10.00")));
        members.save(allowed);
        assertEquals(available.id(), service().borrow(allowed.id(), TITLE).copyId());
    }

    @Test
    void activeLoanLimitAndExistingWaitingQueueBlockNewBorrowing() {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy available = new Copy(new CopyId("copy-1"), TITLE);
        copies.save(available);
        Member member = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
        members.save(member);
        for (int i = 0; i < 3; i++) {
            Copy onLoan = new Copy(new CopyId("other-" + i), TITLE);
            onLoan.markOnLoan();
            copies.save(onLoan);
            loans.save(new Loan(new LoanId("old-" + i), member.id(), onLoan.id(),
                    NOW, NOW.plus(14, ChronoUnit.DAYS)));
        }
        assertThrows(DomainException.class, () -> service().borrow(member.id(), TITLE));
        assertEquals(CopyStatus.AVAILABLE, available.status());

        for (Loan loan : loans.findActiveByMemberId(member.id())) {
            loan.markReturned(NOW);
        }
        Hold waiting = new Hold(new HoldId("hold-1"), new MemberId("other-member"), TITLE, NOW);
        holds.save(waiting);
        assertThrows(DomainException.class, () -> service().borrow(member.id(), TITLE));
        assertEquals(CopyStatus.AVAILABLE, available.status());
    }

    @Test
    void twoServicesSharingRepositoriesCannotLendTheLastCopyTwice() throws Exception {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy onlyCopy = new Copy(new CopyId("only-copy"), TITLE);
        copies.save(onlyCopy);
        members.save(new Member(new MemberId("member-1"), "Alex", Tier.STANDARD));
        members.save(new Member(new MemberId("member-2"), "Pat", Tier.STANDARD));
        LibraryService firstService = service();
        LibraryService secondService = service();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attemptBorrow(firstService, new MemberId("member-1"), ready, go));
            Future<Boolean> second = executor.submit(attemptBorrow(secondService, new MemberId("member-2"), ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            int successes = (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(CopyStatus.ON_LOAN, onlyCopy.status());
            Loan active = loans.findActiveByCopyId(onlyCopy.id()).orElseThrow();
            assertEquals(1, loans.countActiveByMemberId(active.memberId()));
            MemberId other = active.memberId().equals(new MemberId("member-1"))
                    ? new MemberId("member-2") : new MemberId("member-1");
            assertEquals(0, loans.countActiveByMemberId(other));
            assertFalse(active.returnedAt().isPresent());
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> attemptBorrow(LibraryService service, MemberId memberId,
                                            CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("other borrowing request did not start");
            }
            try {
                service.borrow(memberId, TITLE);
                return true;
            } catch (DomainException noAvailableCopy) {
                assertEquals("no available copy; request a hold", noAvailableCopy.getMessage());
                return false;
            }
        };
    }

    private LibraryService service() {
        return serviceWithClock(clock);
    }

    private LibraryService serviceWithClock(Clock chosenClock) {
        return new LibraryService(titles, copies, members, loans, holds, policies, chosenClock);
    }

    private Clock advancingClock(AtomicInteger reads, ZoneId zone) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return zone;
            }

            @Override
            public Clock withZone(ZoneId newZone) {
                return advancingClock(reads, newZone);
            }

            @Override
            public Instant instant() {
                return NOW.plusSeconds(reads.getAndIncrement());
            }
        };
    }
}
