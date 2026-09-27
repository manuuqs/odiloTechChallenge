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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LibraryPickupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final TitleId TITLE = new TitleId("title-1");
    private static final CopyId COPY_ID = new CopyId("copy-1");
    private static final MemberId BORROWER = new MemberId("member-1");
    private static final MemberId FIRST = new MemberId("member-2");
    private static final MemberId SECOND = new MemberId("member-3");
    private final TitleRepository titles = new InMemoryTitleRepository();
    private final CopyRepository copies = new InMemoryCopyRepository();
    private final MemberRepository members = new InMemoryMemberRepository();
    private final LoanRepository loans = new InMemoryLoanRepository();
    private final HoldRepository holds = new InMemoryHoldRepository();
    private final InMemoryPolicyProvider policies = InMemoryPolicyProvider.withChallengeDefaults();

    @Test
    void collectingImmediatelyBeforeDeadlineCreatesLoanForAssignedCopy() {
        Loan original = borrowOnlyCopy();
        Hold first = queue(FIRST, Tier.STUDENT);
        serviceAt(NOW).returnLoan(original.id());
        Instant collectedAt = first.expiresAt().orElseThrow().minusNanos(1);

        Loan collected = serviceAt(collectedAt).collectHold(first.id());

        assertEquals(FIRST, collected.memberId());
        assertEquals(COPY_ID, collected.copyId());
        assertEquals(collectedAt, collected.startedAt());
        assertEquals(collectedAt.plus(28, ChronoUnit.DAYS), collected.dueAt());
        assertEquals(collected, loans.findActiveByCopyId(COPY_ID).orElseThrow());
        assertEquals(HoldStatus.COLLECTED, first.status());
        assertEquals(CopyStatus.ON_LOAN, copies.findById(COPY_ID).orElseThrow().status());
        assertThrows(DomainException.class, () -> serviceAt(collectedAt).collectHold(first.id()));
    }

    @Test
    void expiringAtDeadlineGivesTheNextMemberAFullWindow() {
        Loan original = borrowOnlyCopy();
        Hold first = queue(FIRST, Tier.STANDARD);
        Hold second = queue(SECOND, Tier.STUDENT);
        serviceAt(NOW).returnLoan(original.id());
        Instant deadline = first.expiresAt().orElseThrow();

        assertThrows(DomainException.class, () -> serviceAt(deadline.minusNanos(1)).expireHold(first.id()));
        assertEquals(HoldStatus.ASSIGNED, first.status());
        Hold next = serviceAt(deadline).expireHold(first.id()).orElseThrow();

        assertEquals(second.id(), next.id());
        assertEquals(HoldStatus.EXPIRED, first.status());
        assertEquals(HoldStatus.ASSIGNED, second.status());
        assertEquals(COPY_ID, second.assignedCopyId().orElseThrow());
        assertEquals(deadline.plus(48, ChronoUnit.HOURS), second.expiresAt().orElseThrow());
        assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());
        assertThrows(DomainException.class, () -> serviceAt(deadline).expireHold(first.id()));
    }

    @Test
    void changingPickupWindowDoesNotAlterExistingHoldButAppliesToNextOne() {
        Loan original = borrowOnlyCopy();
        Hold first = queue(FIRST, Tier.STANDARD);
        Hold second = queue(SECOND, Tier.STUDENT);
        policies.updateHoldPickupWindow(Duration.ofHours(12));
        serviceAt(NOW).returnLoan(original.id());
        Instant firstDeadline = first.expiresAt().orElseThrow();
        policies.updateHoldPickupWindow(Duration.ofHours(24));
        Instant expiredAt = firstDeadline.plusSeconds(1);

        serviceAt(expiredAt).expireHold(first.id());

        assertEquals(NOW.plus(12, ChronoUnit.HOURS), firstDeadline);
        assertEquals(HoldStatus.EXPIRED, first.status());
        assertEquals(HoldStatus.ASSIGNED, second.status());
        assertEquals(expiredAt.plus(24, ChronoUnit.HOURS), second.expiresAt().orElseThrow());
    }

    @Test
    void collectingExactlyAtDeadlineExpiresHoldAndAssignsNextMember() {
        Loan original = borrowOnlyCopy();
        Hold first = queue(FIRST, Tier.STANDARD);
        Hold second = queue(SECOND, Tier.STUDENT);
        serviceAt(NOW).returnLoan(original.id());
        Instant deadline = first.expiresAt().orElseThrow();

        assertThrows(DomainException.class, () -> serviceAt(deadline).collectHold(first.id()));

        assertEquals(HoldStatus.EXPIRED, first.status());
        assertEquals(HoldStatus.ASSIGNED, second.status());
        assertEquals(deadline.plus(48, ChronoUnit.HOURS), second.expiresAt().orElseThrow());
        assertTrue(loans.findActiveByCopyId(COPY_ID).isEmpty());
    }

    @Test
    void outstandingFinesForfeitAssignedHoldAndPassCopyToNextMember() {
        Loan original = borrowOnlyCopy();
        Hold first = queue(FIRST, Tier.STANDARD);
        Hold second = queue(SECOND, Tier.STUDENT);
        serviceAt(NOW).returnLoan(original.id());
        Member debtor = members.findById(FIRST).orElseThrow();
        debtor.updateOutstandingBalance(new Money(new BigDecimal("10.01")));
        Instant attemptedAt = NOW.plus(1, ChronoUnit.HOURS);

        assertThrows(DomainException.class, () -> serviceAt(attemptedAt).collectHold(first.id()));

        assertEquals(HoldStatus.EXPIRED, first.status());
        assertEquals(HoldStatus.ASSIGNED, second.status());
        assertEquals(attemptedAt.plus(48, ChronoUnit.HOURS), second.expiresAt().orElseThrow());
        assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());
        assertTrue(loans.findActiveByCopyId(COPY_ID).isEmpty());
        assertEquals(SECOND, serviceAt(attemptedAt).collectHold(second.id()).memberId());
    }

    @Test
    void indebtedMemberWithoutSuccessorReleasesCopyAndExactlyTenEurosCanCollect() {
        Loan original = borrowOnlyCopy();
        Hold hold = queue(FIRST, Tier.STANDARD);
        serviceAt(NOW).returnLoan(original.id());
        Member member = members.findById(FIRST).orElseThrow();
        member.updateOutstandingBalance(new Money(new BigDecimal("10.01")));
        assertThrows(DomainException.class, () -> serviceAt(NOW).collectHold(hold.id()));
        assertEquals(HoldStatus.EXPIRED, hold.status());
        assertEquals(CopyStatus.AVAILABLE, copies.findById(COPY_ID).orElseThrow().status());

        MemberId nextBorrower = SECOND;
        members.save(new Member(nextBorrower, "Lee", Tier.STANDARD));
        Loan secondLoan = (Loan) serviceAt(NOW).borrow(nextBorrower, TITLE);
        member.updateOutstandingBalance(new Money(new BigDecimal("10.00")));
        Hold nextHold = (Hold) serviceAt(NOW).placeHold(FIRST, TITLE);
        serviceAt(NOW).returnLoan(secondLoan.id());

        assertEquals(FIRST, serviceAt(NOW).collectHold(nextHold.id()).memberId());
    }

    @Test
    void loanLimitBlocksPickupWithoutLosingTheReservation() {
        Loan original = borrowOnlyCopy();
        Hold assigned = queue(FIRST, Tier.STANDARD);
        Loan oneToReturn = null;
        for (int i = 0; i < 3; i++) {
            TitleId otherTitle = new TitleId("other-title-" + i);
            titles.save(new Title(otherTitle, "Refactoring " + i));
            copies.save(new Copy(new CopyId("other-" + i), otherTitle));
            Loan active = (Loan) serviceAt(NOW).borrow(FIRST, otherTitle);
            if (i == 0) {
                oneToReturn = active;
            }
        }
        serviceAt(NOW).returnLoan(original.id());

        assertThrows(DomainException.class, () -> serviceAt(NOW).collectHold(assigned.id()));
        assertEquals(HoldStatus.ASSIGNED, assigned.status());
        assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());

        serviceAt(NOW.plusSeconds(1)).returnLoan(oneToReturn.id());
        assertEquals(FIRST, serviceAt(NOW.plusSeconds(2)).collectHold(assigned.id()).memberId());
    }

    @Test
    void existingLoanOfSameTitleForfeitsHoldAndOffersCopyToNextMember() {
        Loan original = borrowOnlyCopy();
        Hold assigned = queue(FIRST, Tier.STANDARD);
        serviceAt(NOW).returnLoan(original.id());
        copies.save(new Copy(new CopyId("copy-2"), TITLE));
        Loan otherCopy = (Loan) serviceAt(NOW).borrow(FIRST, TITLE);
        Hold next = queue(SECOND, Tier.STUDENT);
        Instant attemptedAt = NOW.plusSeconds(1);

        DomainException rejected = assertThrows(DomainException.class,
                () -> serviceAt(attemptedAt).collectHold(assigned.id()));

        assertEquals("member already has this title on loan", rejected.getMessage());
        assertEquals(HoldStatus.EXPIRED, assigned.status());
        assertFalse(holds.existsActiveByMemberAndTitle(FIRST, TITLE));
        assertTrue(holds.findById(assigned.id()).isPresent());
        assertEquals(HoldStatus.ASSIGNED, next.status());
        assertEquals(COPY_ID, next.assignedCopyId().orElseThrow());
        assertEquals(attemptedAt.plus(48, ChronoUnit.HOURS), next.expiresAt().orElseThrow());
        assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());
        assertTrue(loans.findActiveByCopyId(COPY_ID).isEmpty());
        assertEquals(otherCopy, loans.findActiveByCopyId(otherCopy.copyId()).orElseThrow());
        assertEquals(COPY_ID, serviceAt(attemptedAt).collectHold(next.id()).copyId());
    }

    @Test
    void existingLoanOfSameTitleReleasesCopyWhenNoOneElseIsWaiting() {
        Loan original = borrowOnlyCopy();
        Hold assigned = queue(FIRST, Tier.STANDARD);
        serviceAt(NOW).returnLoan(original.id());
        copies.save(new Copy(new CopyId("copy-2"), TITLE));
        serviceAt(NOW).borrow(FIRST, TITLE);

        assertThrows(DomainException.class,
                () -> serviceAt(NOW.plusSeconds(1)).collectHold(assigned.id()));

        assertEquals(HoldStatus.EXPIRED, assigned.status());
        assertEquals(CopyStatus.AVAILABLE, copies.findById(COPY_ID).orElseThrow().status());
        assertFalse(holds.existsActiveByMemberAndTitle(FIRST, TITLE));
        members.save(new Member(SECOND, "Lee", Tier.STUDENT));
        assertEquals(COPY_ID, ( (Loan) serviceAt(NOW.plusSeconds(2)).borrow(SECOND, TITLE) ).copyId());
    }

    @Test
    void missingCopyOfActiveLoanRejectsPickupWithoutForfeitingHold() {
        Loan original = borrowOnlyCopy();
        Hold assigned = queue(FIRST, Tier.STANDARD);
        serviceAt(NOW).returnLoan(original.id());
        loans.save(new Loan(new LoanId("orphan-loan"), FIRST, new CopyId("missing-copy"),
                NOW, NOW.plus(14, ChronoUnit.DAYS)));

        DomainException rejected = assertThrows(DomainException.class,
                () -> serviceAt(NOW.plusSeconds(1)).collectHold(assigned.id()));

        assertEquals("active loan copy not found: missing-copy", rejected.getMessage());
        assertEquals(HoldStatus.ASSIGNED, assigned.status());
        assertEquals(CopyStatus.HELD, copies.findById(COPY_ID).orElseThrow().status());
        assertTrue(loans.findActiveByCopyId(COPY_ID).isEmpty());
    }

    @Test
    void relatedBorrowingLazilyExpiresUncollectedHoldWhenNoOneIsWaiting() {
        Loan original = borrowOnlyCopy();
        Hold first = queue(FIRST, Tier.STANDARD);
        serviceAt(NOW).returnLoan(original.id());
        members.save(new Member(SECOND, "Lee", Tier.STUDENT));
        Instant deadline = first.expiresAt().orElseThrow();

        Loan newLoan = (Loan) serviceAt(deadline).borrow(SECOND, TITLE);

        assertEquals(COPY_ID, newLoan.copyId());
        assertEquals(HoldStatus.EXPIRED, first.status());
        assertEquals(CopyStatus.ON_LOAN, copies.findById(COPY_ID).orElseThrow().status());
    }

    @Test
    void concurrentPickupsCannotCreateTwoLoansForTheHeldCopy() throws Exception {
        Loan original = borrowOnlyCopy();
        Hold assigned = queue(FIRST, Tier.STANDARD);
        serviceAt(NOW).returnLoan(original.id());
        LibraryService firstService = serviceAt(NOW);
        LibraryService secondService = serviceAt(NOW);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attemptPickup(firstService, assigned.id(), ready, go));
            Future<Boolean> second = executor.submit(attemptPickup(secondService, assigned.id(), ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            int successful = (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successful);
            assertEquals(HoldStatus.COLLECTED, assigned.status());
            assertEquals(CopyStatus.ON_LOAN, copies.findById(COPY_ID).orElseThrow().status());
            assertEquals(1, loans.countActiveByMemberId(FIRST));
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void missingOrWaitingHoldsCannotBeCollectedOrExpired() {
        assertThrows(NullPointerException.class, () -> serviceAt(NOW).collectHold(null));
        assertThrows(NullPointerException.class, () -> serviceAt(NOW).expireHold(null));
        assertThrows(DomainException.class, () -> serviceAt(NOW).collectHold(new HoldId("missing")));
        assertThrows(DomainException.class, () -> serviceAt(NOW).expireHold(new HoldId("missing")));
        titles.save(new Title(TITLE, "Clean Code"));
        Hold waiting = queue(FIRST, Tier.STANDARD);
        assertThrows(DomainException.class, () -> serviceAt(NOW).collectHold(waiting.id()));
        assertThrows(DomainException.class, () -> serviceAt(NOW).expireHold(waiting.id()));
        assertEquals(HoldStatus.WAITING, waiting.status());
    }

    private Callable<Boolean> attemptPickup(LibraryService service, HoldId id,
                                             CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("other pickup request did not start");
            }
            try {
                service.collectHold(id);
                return true;
            } catch (DomainException alreadyCollected) {
                assertEquals("hold is not assigned", alreadyCollected.getMessage());
                return false;
            }
        };
    }

    private Loan borrowOnlyCopy() {
        titles.save(new Title(TITLE, "Clean Code"));
        copies.save(new Copy(COPY_ID, TITLE));
        members.save(new Member(BORROWER, "Alex", Tier.STANDARD));
        return (Loan) serviceAt(NOW).borrow(BORROWER, TITLE);
    }

    private Hold queue(MemberId memberId, Tier tier) {
        members.save(new Member(memberId, "Reader", tier));
        return (Hold) serviceAt(NOW).placeHold(memberId, TITLE);
    }

    private LibraryService serviceAt(Instant instant) {
        return new LibraryService(titles, copies, members, loans, holds, policies,
                Clock.fixed(instant, ZoneOffset.UTC));
    }
}
