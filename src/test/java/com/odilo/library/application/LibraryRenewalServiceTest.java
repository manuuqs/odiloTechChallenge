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
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LibraryRenewalServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final TitleId TITLE = new TitleId("title-1");
    private static final CopyId COPY_ID = new CopyId("copy-1");
    private static final MemberId MEMBER_ID = new MemberId("member-1");
    private final TitleRepository titles = new InMemoryTitleRepository();
    private final CopyRepository copies = new InMemoryCopyRepository();
    private final MemberRepository members = new InMemoryMemberRepository();
    private final LoanRepository loans = new InMemoryLoanRepository();
    private final HoldRepository holds = new InMemoryHoldRepository();
    private final InMemoryPolicyProvider policies = InMemoryPolicyProvider.withChallengeDefaults();

    @Test
    void renewingBeforeDeadlineExtendsThePreviousDueDateAndConsumesOneRenewal() {
        Loan loan = borrow(Tier.STANDARD);
        Instant previousDue = loan.dueAt();
        Instant justBeforeDue = previousDue.minusNanos(1);

        Loan renewed = serviceAt(justBeforeDue).renew(loan.id());

        assertEquals(loan, renewed);
        assertEquals(previousDue.plus(14, ChronoUnit.DAYS), renewed.dueAt());
        assertEquals(1, renewed.renewalCount());
        assertEquals(renewed, loans.findActiveByCopyId(COPY_ID).orElseThrow());
        assertEquals(CopyStatus.ON_LOAN, copies.findById(COPY_ID).orElseThrow().status());
    }

    @Test
    void standardCanRenewTwiceButNotThreeTimes() {
        assertRenewalLimit(Tier.STANDARD, 2, 14);
    }

    @Test
    void studentCanRenewThreeTimesButNotFour() {
        assertRenewalLimit(Tier.STUDENT, 3, 28);
    }

    @Test
    void staffMayRenewRepeatedlyWithoutAConfiguredLimit() {
        Loan loan = borrow(Tier.STAFF);
        Instant previousDue = loan.dueAt();
        for (int i = 1; i <= 5; i++) {
            serviceAt(NOW.plusSeconds(i)).renew(loan.id());
            assertEquals(i, loan.renewalCount());
            assertEquals(previousDue.plus(i * 56L, ChronoUnit.DAYS), loan.dueAt());
        }
    }

    @Test
    void cannotRenewExactlyAtOrAfterTheDueTime() {
        Loan loan = borrow(Tier.STANDARD);
        Instant dueAt = loan.dueAt();

        assertThrows(DomainException.class, () -> serviceAt(dueAt).renew(loan.id()));
        assertThrows(DomainException.class, () -> serviceAt(dueAt.plusNanos(1)).renew(loan.id()));

        assertEquals(dueAt, loan.dueAt());
        assertEquals(0, loan.renewalCount());
    }

    @Test
    void debtBlocksRenewalOnlyAboveTheConfiguredThreshold() {
        Loan loan = borrow(Tier.STANDARD);
        Member member = members.findById(MEMBER_ID).orElseThrow();
        Instant previousDue = loan.dueAt();
        member.updateOutstandingBalance(new Money(new BigDecimal("10.01")));

        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(1)).renew(loan.id()));
        assertEquals(previousDue, loan.dueAt());
        assertEquals(0, loan.renewalCount());

        member.updateOutstandingBalance(new Money(new BigDecimal("10.00")));
        serviceAt(NOW.plusSeconds(1)).renew(loan.id());
        assertEquals(previousDue.plus(14, ChronoUnit.DAYS), loan.dueAt());
        assertEquals(1, loan.renewalCount());
    }

    @Test
    void waitingMemberBlocksRenewalWithoutConsumingAnAttempt() {
        Loan loan = borrow(Tier.STANDARD);
        MemberId other = new MemberId("member-2");
        members.save(new Member(other, "Lee", Tier.STUDENT));
        serviceAt(NOW).placeHold(other, TITLE);
        Instant previousDue = loan.dueAt();

        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(1)).renew(loan.id()));

        assertEquals(previousDue, loan.dueAt());
        assertEquals(0, loan.renewalCount());
    }

    @Test
    void ownWaitingHoldDoesNotCountAsAnotherPerson() {
        Loan loan = borrow(Tier.STANDARD);
        holds.save(new Hold(new HoldId("older-hold"), MEMBER_ID, TITLE, NOW));

        serviceAt(NOW.plusSeconds(1)).renew(loan.id());

        assertEquals(1, loan.renewalCount());
    }

    @Test
    void policyChangeTakesEffectOnlyWhenRenewing() {
        Loan loan = borrow(Tier.STANDARD);
        Instant previousDue = loan.dueAt();
        policies.updateTierPolicy(new TierPolicy(Tier.STANDARD, 21, 3, OptionalInt.of(1)));
        assertEquals(previousDue, loan.dueAt());

        serviceAt(NOW.plusSeconds(1)).renew(loan.id());

        assertEquals(previousDue.plus(21, ChronoUnit.DAYS), loan.dueAt());
        assertEquals(1, loan.renewalCount());
        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(2)).renew(loan.id()));
        assertEquals(1, loan.renewalCount());
    }

    @Test
    void rejectsUnknownAndReturnedLoansWithoutChangingTheirHistory() {
        assertThrows(NullPointerException.class, () -> serviceAt(NOW).renew(null));
        assertThrows(DomainException.class, () -> serviceAt(NOW).renew(new LoanId("missing")));

        Loan loan = borrow(Tier.STANDARD);
        Instant dueAt = loan.dueAt();
        serviceAt(NOW.plusSeconds(1)).returnLoan(loan.id());

        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(2)).renew(loan.id()));
        assertEquals(dueAt, loan.dueAt());
        assertEquals(0, loan.renewalCount());
        assertTrue(loan.returnedAt().isPresent());
    }

    @Test
    void rejectsInconsistentLoanCopyAndMissingMember() {
        Member member = new Member(MEMBER_ID, "Alex", Tier.STANDARD);
        members.save(member);
        Loan orphan = new Loan(new LoanId("orphan"), MEMBER_ID, COPY_ID,
                NOW, NOW.plus(14, ChronoUnit.DAYS));
        loans.save(orphan);
        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(1)).renew(orphan.id()));
        assertEquals(0, orphan.renewalCount());

        copies.save(new Copy(COPY_ID, TITLE));
        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(1)).renew(orphan.id()));
        assertEquals(0, orphan.renewalCount());

        Copy second = new Copy(new CopyId("copy-2"), TITLE);
        second.markOnLoan();
        copies.save(second);
        Loan missingMember = new Loan(new LoanId("missing-member-2"), new MemberId("unknown"), second.id(),
                NOW, NOW.plus(14, ChronoUnit.DAYS));
        loans.save(missingMember);
        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(1)).renew(missingMember.id()));
        assertEquals(0, missingMember.renewalCount());
    }

    @Test
    void concurrentRenewalsAcrossServicesCannotExceedThePolicyLimit() throws Exception {
        Loan loan = borrow(Tier.STANDARD);
        Instant originalDue = loan.dueAt();
        policies.updateTierPolicy(new TierPolicy(Tier.STANDARD, 14, 3, OptionalInt.of(1)));
        LibraryService firstService = serviceAt(NOW.plusSeconds(1));
        LibraryService secondService = serviceAt(NOW.plusSeconds(1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(attemptRenewal(firstService, loan.id(), ready, go));
            Future<Boolean> second = executor.submit(attemptRenewal(secondService, loan.id(), ready, go));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();

            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, loan.renewalCount());
            assertEquals(originalDue.plus(14, ChronoUnit.DAYS), loan.dueAt());
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<Boolean> attemptRenewal(LibraryService service, LoanId loanId,
                                              CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            if (!go.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("other renewal request did not start");
            }
            try {
                service.renew(loanId);
                return true;
            } catch (DomainException limitReached) {
                assertEquals("loan has reached the renewal limit", limitReached.getMessage());
                return false;
            }
        };
    }

    private void assertRenewalLimit(Tier tier, int maximum, int durationDays) {
        Loan loan = borrow(tier);
        Instant originalDue = loan.dueAt();
        for (int i = 1; i <= maximum; i++) {
            serviceAt(NOW.plusSeconds(i)).renew(loan.id());
            assertEquals(i, loan.renewalCount());
            assertEquals(originalDue.plus((long) i * durationDays, ChronoUnit.DAYS), loan.dueAt());
        }
        Instant dueAt = loan.dueAt();
        assertThrows(DomainException.class, () -> serviceAt(NOW.plusSeconds(maximum + 1)).renew(loan.id()));
        assertEquals(maximum, loan.renewalCount());
        assertEquals(dueAt, loan.dueAt());
    }

    private Loan borrow(Tier tier) {
        titles.save(new Title(TITLE, "Clean Code"));
        copies.save(new Copy(COPY_ID, TITLE));
        members.save(new Member(MEMBER_ID, "Alex", tier));
        return (Loan) serviceAt(NOW).borrow(MEMBER_ID, TITLE);
    }

    private LibraryService serviceAt(Instant at) {
        return new LibraryService(titles, copies, members, loans, holds, policies,
                Clock.fixed(at, ZoneOffset.UTC));
    }
}
