package com.odilo.library.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.CopyStatus;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
import com.odilo.library.domain.model.HoldStatus;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.policy.FinePolicy;
import com.odilo.library.domain.policy.PolicyProvider;
import com.odilo.library.domain.policy.TierPolicy;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LibraryReturnServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final TitleId TITLE = new TitleId("title-1");
    private static final CopyId COPY_ID = new CopyId("copy-1");
    private static final MemberId BORROWER = new MemberId("member-1");
    private final TitleRepository titles = new InMemoryTitleRepository();
    private final CopyRepository copies = new InMemoryCopyRepository();
    private final MemberRepository members = new InMemoryMemberRepository();
    private final LoanRepository loans = new InMemoryLoanRepository();
    private final HoldRepository holds = new InMemoryHoldRepository();
    private final InMemoryPolicyProvider policies = InMemoryPolicyProvider.withChallengeDefaults();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void returningWithoutQueueMakesCopyAvailableAgainEvenWithOutstandingFines() {
        Loan loan = borrowOnlyCopy();
        Member borrower = members.findById(BORROWER).orElseThrow();
        borrower.updateOutstandingBalance(new Money(new BigDecimal("20.00")));

        Optional<Hold> assigned = service().returnLoan(loan.id());

        assertTrue(assigned.isEmpty());
        assertEquals(NOW, loan.returnedAt().orElseThrow());
        assertEquals(CopyStatus.AVAILABLE, copies.findById(COPY_ID).orElseThrow().status());
        assertEquals(0, loans.countActiveByMemberId(BORROWER));
        assertThrows(DomainException.class, () -> service().returnLoan(loan.id()));

        MemberId nextBorrower = new MemberId("member-2");
        members.save(new Member(nextBorrower, "Pat", Tier.STUDENT));
        assertEquals(COPY_ID, service().borrow(nextBorrower, TITLE).copyId());
    }

    @Test
    void returningAssignsFirstWaitingMemberAndRetainsTheExactCopyFor48Hours() {
        Loan loan = borrowOnlyCopy();
        MemberId firstMember = new MemberId("member-2");
        MemberId secondMember = new MemberId("member-3");
        members.save(new Member(firstMember, "Pat", Tier.STANDARD));
        members.save(new Member(secondMember, "Lee", Tier.STUDENT));
        Hold first = service().placeHold(firstMember, TITLE);
        Hold second = service().placeHold(secondMember, TITLE);

        Hold assigned = service().returnLoan(loan.id()).orElseThrow();

        assertEquals(first.id(), assigned.id());
        assertEquals(HoldStatus.ASSIGNED, first.status());
        assertEquals(COPY_ID, first.assignedCopyId().orElseThrow());
        assertEquals(NOW, first.assignedAt().orElseThrow());
        assertEquals(NOW.plus(48, ChronoUnit.HOURS), first.expiresAt().orElseThrow());
        assertEquals(HoldStatus.WAITING, second.status());
        assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());
        assertTrue(copies.findAvailableByTitleId(TITLE).isEmpty());
        assertEquals(NOW, loan.returnedAt().orElseThrow());
        assertEquals(first, holds.findById(first.id()).orElseThrow());
        assertThrows(DomainException.class, () -> service().borrow(secondMember, TITLE));
    }

    @Test
    void holdExpirationIsFixedAtAssignmentUsingCurrentConfiguration() {
        Loan loan = borrowOnlyCopy();
        MemberId waiting = new MemberId("member-2");
        members.save(new Member(waiting, "Pat", Tier.STANDARD));
        service().placeHold(waiting, TITLE);
        policies.updateHoldPickupWindow(Duration.ofHours(12));

        Hold assigned = service().returnLoan(loan.id()).orElseThrow();
        policies.updateHoldPickupWindow(Duration.ofHours(24));

        assertEquals(NOW.plus(12, ChronoUnit.HOURS), assigned.expiresAt().orElseThrow());
    }

    @Test
    void missingOrInconsistentLoansDoNotMutateCopyOrQueue() {
        assertThrows(NullPointerException.class, () -> service().returnLoan(null));
        assertThrows(DomainException.class, () -> service().returnLoan(new LoanId("missing")));

        Loan loan = borrowOnlyCopy();
        Copy copy = copies.findById(COPY_ID).orElseThrow();
        MemberId waiting = new MemberId("member-2");
        members.save(new Member(waiting, "Pat", Tier.STANDARD));
        Hold hold = service().placeHold(waiting, TITLE);

        Loan future = new Loan(new LoanId("future"), BORROWER, new CopyId("missing-copy"),
                NOW.plusSeconds(60), NOW.plus(15, ChronoUnit.DAYS));
        loans.save(future);
        assertThrows(DomainException.class, () -> service().returnLoan(future.id()));
        assertTrue(future.returnedAt().isEmpty());
        assertTrue(loan.returnedAt().isEmpty());
        assertEquals(CopyStatus.ON_LOAN, copy.status());
        assertEquals(HoldStatus.WAITING, hold.status());
    }

    @Test
    void invalidReturnTimeLeavesLoanCopyAndQueueUntouched() {
        titles.save(new Title(TITLE, "Clean Code"));
        Copy copy = new Copy(COPY_ID, TITLE);
        copy.markOnLoan();
        copies.save(copy);
        Loan future = new Loan(new LoanId("future"), BORROWER, COPY_ID,
                NOW.plusSeconds(60), NOW.plus(14, ChronoUnit.DAYS));
        loans.save(future);
        Hold waiting = new Hold(new HoldId("hold-1"), new MemberId("member-2"), TITLE, NOW);
        holds.save(waiting);

        assertThrows(DomainException.class, () -> service().returnLoan(future.id()));
        assertTrue(future.returnedAt().isEmpty());
        assertEquals(CopyStatus.ON_LOAN, copy.status());
        assertEquals(HoldStatus.WAITING, waiting.status());
    }

    @Test
    void invalidExternalPickupPolicyCannotPartiallyReturnALoan() {
        Loan loan = borrowOnlyCopy();
        Hold waiting = new Hold(new HoldId("hold-1"), new MemberId("member-2"), TITLE, NOW);
        holds.save(waiting);
        PolicyProvider invalid = new PolicyProvider() {
            @Override
            public FinePolicy finePolicy() {
                return policies.finePolicy();
            }

            @Override
            public TierPolicy tierPolicy(Tier tier) {
                return policies.tierPolicy(tier);
            }

            @Override
            public Duration holdPickupWindow() {
                return Duration.ZERO;
            }
        };
        LibraryService service = new LibraryService(titles, copies, members, loans, holds, invalid, clock);

        assertThrows(DomainException.class, () -> service.returnLoan(loan.id()));
        assertTrue(loan.returnedAt().isEmpty());
        assertEquals(CopyStatus.ON_LOAN, copies.findById(COPY_ID).orElseThrow().status());
        assertEquals(HoldStatus.WAITING, waiting.status());
    }

    @Test
    void concurrentReturnsAssignOnlyTheFirstHoldOnce() throws Exception {
        Loan loan = borrowOnlyCopy();
        MemberId firstMember = new MemberId("member-2");
        MemberId secondMember = new MemberId("member-3");
        members.save(new Member(firstMember, "Pat", Tier.STANDARD));
        members.save(new Member(secondMember, "Lee", Tier.STUDENT));
        Hold firstHold = service().placeHold(firstMember, TITLE);
        Hold secondHold = service().placeHold(secondMember, TITLE);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attemptReturn(service(), loan.id(), ready, go));
            Future<Boolean> second = executor.submit(attemptReturn(service(), loan.id(), ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            int successful = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successful);
            assertEquals(HoldStatus.ASSIGNED, firstHold.status());
            assertEquals(HoldStatus.WAITING, secondHold.status());
            assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());
            assertEquals(NOW, loan.returnedAt().orElseThrow());
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> attemptReturn(LibraryService service, LoanId loanId,
                                            CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("other return request did not start");
            }
            try {
                service.returnLoan(loanId);
                return true;
            } catch (DomainException duplicate) {
                assertEquals("loan has already been returned", duplicate.getMessage());
                return false;
            }
        };
    }

    private Loan borrowOnlyCopy() {
        titles.save(new Title(TITLE, "Clean Code"));
        copies.save(new Copy(COPY_ID, TITLE));
        members.save(new Member(BORROWER, "Alex", Tier.STANDARD));
        return service().borrow(BORROWER, TITLE);
    }

    private LibraryService service() {
        return new LibraryService(titles, copies, members, loans, holds, policies, clock);
    }
}
