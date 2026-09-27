package com.odilo.library;

import com.odilo.library.application.LibraryService;
import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Money;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
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

public final class Main {

    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    public static void main(String[] args) throws Exception {
//        Demo demo = new Demo(new InMemoryTitleRepository(), new InMemoryCopyRepository(),
//                new InMemoryMemberRepository(), new InMemoryLoanRepository(),
//                new InMemoryHoldRepository(), InMemoryPolicyProvider.withChallengeDefaults());

//        Title title = new Title(new TitleId("title-1"), "Clean Code");
//        Copy copy = new Copy(new CopyId("copy-1"), title.id());
//        Member alex = new Member(new MemberId("member-1"), "Alex", Tier.STANDARD);
//        Member bea = new Member(new MemberId("member-2"), "Bea", Tier.STUDENT);
//        Member carla = new Member(new MemberId("member-3"), "Carla", Tier.STANDARD);
//        Member dani = new Member(new MemberId("member-4"), "Dani", Tier.STUDENT);
//        Member blocked = new Member(new MemberId("member-5"), "Pat", Tier.STANDARD);
//        blocked.updateOutstandingBalance(new Money(new BigDecimal("10.01")));
//        demo.titles().save(title);
//        demo.copies().save(copy);
//        for (Member member : new Member[] {alex, bea, carla, dani, blocked}) {
//            demo.members().save(member);
//        }
//
//        System.out.println("=== Library demo (fixed UTC clock) ===");
//        Loan firstLoan = (Loan) demo.at(START).borrow(alex.id(), title.id());
//        System.out.printf("%s borrowed %s (copy %s); due %s%n",
//                alex.name(), title.name(), copy.id().value(), firstLoan.dueAt());
//
//        Hold beaHold = (Hold) demo.at(START.plusSeconds(1)).borrow(bea.id(), title.id());
//        System.out.printf("%s joined the queue: %s%n", bea.name(), beaHold.status());
//        Instant firstReturn = START.plus(1, ChronoUnit.DAYS);
//        demo.at(firstReturn).returnLoan(firstLoan.id());
//        System.out.printf("%s returned the copy: %s, %s until %s%n",
//                alex.name(), copy.status(), beaHold.status(), beaHold.expiresAt().orElseThrow());
//
//        Loan beaLoan = (Loan) demo.at(firstReturn.plusSeconds(1)).collectHold(beaHold.id());
//        System.out.printf("%s collected: %s, copy %s, due %s%n",
//                bea.name(), beaHold.status(), copy.status(), beaLoan.dueAt());
//        Instant originalDue = beaLoan.dueAt();
//        demo.at(originalDue.minus(1, ChronoUnit.DAYS)).renew(beaLoan.id());
//        System.out.printf("%s renewed: due %s, renewals %d%n",
//                bea.name(), beaLoan.dueAt(), beaLoan.renewalCount());
//
//        Instant lateReturn = beaLoan.dueAt().plus(1, ChronoUnit.DAYS);
//        demo.at(lateReturn).returnLoan(beaLoan.id());
//        System.out.printf("%s returned one full day late: balance %s EUR, copy %s%n",
//                bea.name(), bea.outstandingBalance().amount(), copy.status());
//        expectRejection("Duplicate return", "loan has already been returned",
//                () -> demo.at(lateReturn).returnLoan(beaLoan.id()));
//
//        Instant nextBorrow = lateReturn.plus(1, ChronoUnit.HOURS);
//        Loan alexAgain = (Loan) demo.at(nextBorrow).borrow(alex.id(), title.id());
//        Hold carlaHold = (Hold) demo.at(nextBorrow.plusSeconds(60)).placeHold(carla.id(), title.id());
//        Hold daniHold = (Hold) demo.at(nextBorrow.plusSeconds(120)).placeHold(dani.id(), title.id());
//        expectRejection("Debt above 10 EUR blocks holds", "outstanding fines block holds",
//                () -> demo.at(nextBorrow.plusSeconds(180)).placeHold(blocked.id(), title.id()));
//        expectRejection("Renewal at due time", "overdue loan cannot be renewed",
//                () -> demo.at(alexAgain.dueAt()).renew(alexAgain.id()));
//
//        demo.at(alexAgain.dueAt().plusSeconds(1)).returnLoan(alexAgain.id());
//        System.out.printf("%s got the returned copy: %s until %s%n",
//                carla.name(), carlaHold.status(), carlaHold.expiresAt().orElseThrow());
//        Instant carlaDeadline = carlaHold.expiresAt().orElseThrow();
//        expectRejection("Pickup at expiration", "hold has expired",
//                () -> demo.at(carlaDeadline).collectHold(carlaHold.id()));
//        System.out.printf("%s: %s; next is %s: %s until %s; copy %s%n",
//                carla.name(), carlaHold.status(), dani.name(), daniHold.status(),
//                daniHold.expiresAt().orElseThrow(), copy.status());
//
//        demo.at(daniHold.expiresAt().orElseThrow()).expireHold(daniHold.id());
//        System.out.printf("No one else is waiting: %s, copy %s%n", daniHold.status(), copy.status());

        ConcurrentDemo.run();
    }

    private static void expectRejection(String label, String expectedMessage, Runnable action) {
        try {
            action.run();
        } catch (DomainException rejected) {
            if (!expectedMessage.equals(rejected.getMessage())) {
                throw rejected;
            }
            System.out.printf("%s: %s%n", label, rejected.getMessage());
            return;
        }
        throw new IllegalStateException(label + " unexpectedly succeeded");
    }

    private record Demo(InMemoryTitleRepository titles, InMemoryCopyRepository copies,
                        InMemoryMemberRepository members, InMemoryLoanRepository loans,
                        InMemoryHoldRepository holds, InMemoryPolicyProvider policies) {

        private LibraryService at(Instant when) {
            return new LibraryService(titles, copies, members, loans, holds, policies,
                    Clock.fixed(when, ZoneOffset.UTC));
        }
    }
}
