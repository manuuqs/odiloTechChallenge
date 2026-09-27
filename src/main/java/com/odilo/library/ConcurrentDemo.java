package com.odilo.library;

import com.odilo.library.application.LibraryService;
import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Action;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.CopyStatus;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldStatus;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.Tier;
import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.infrastructure.memory.InMemoryCopyRepository;
import com.odilo.library.infrastructure.memory.InMemoryHoldRepository;
import com.odilo.library.infrastructure.memory.InMemoryLoanRepository;
import com.odilo.library.infrastructure.memory.InMemoryMemberRepository;
import com.odilo.library.infrastructure.memory.InMemoryPolicyProvider;
import com.odilo.library.infrastructure.memory.InMemoryTitleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class ConcurrentDemo {

    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    private ConcurrentDemo() {
    }

    static void run() throws Exception {
        Demo demo = new Demo(new InMemoryTitleRepository(), new InMemoryCopyRepository(),
                new InMemoryMemberRepository(), new InMemoryLoanRepository(),
                new InMemoryHoldRepository(), InMemoryPolicyProvider.withChallengeDefaults());
        Title title = new Title(new TitleId("concurrent-title"), "The Pragmatic Programmer");
        Copy copy = new Copy(new CopyId("concurrent-copy"), title.id());
        List<Member> contenders = List.of(
                new Member(new MemberId("contender-1"), "Alex", Tier.STANDARD),
                new Member(new MemberId("contender-2"), "Bea", Tier.STUDENT),
                new Member(new MemberId("contender-3"), "Carla", Tier.STANDARD),
                new Member(new MemberId("contender-4"), "Dani", Tier.STUDENT));
        demo.titles().save(title);
        demo.copies().save(copy);
        contenders.forEach(demo.members()::save);

        System.out.println("=== Demo concurrente (repositorios compartidos, misma JVM) ===");
        System.out.printf("%n[1] Cuatro miembros piden la última copia a las %s%n", START);
        printSnapshot("Antes", demo, title, contenders, null);
        List<Supplier<Action>> requests = new ArrayList<>();
        for (Member member : contenders) {
            requests.add(() -> demo.at(START).borrow(member.id(), title.id()));
        }
        List<Outcome<Action>> borrowOutcomes = race(requests);
        Loan firstLoan = oneSuccessfulLoan(borrowOutcomes);
        long activeLoans = contenders.stream()
                .mapToLong(member -> demo.loans().countActiveByMemberId(member.id())).sum();
        if (activeLoans != 1 || copy.status() != CopyStatus.ON_LOAN
                || !demo.loans().findActiveByCopyId(copy.id()).orElseThrow().id().equals(firstLoan.id())) {
            throw new IllegalStateException("la última copia se prestó más de una vez");
        }
        int acceptedHolds = (int) borrowOutcomes.stream()
                .filter(outcome -> outcome.rejection() == null && outcome.value() instanceof Hold)
                .count();
        int rejections = (int) borrowOutcomes.stream()
                .filter(outcome -> outcome.rejection() != null)
                .count();
        if (acceptedHolds + rejections + 1 != borrowOutcomes.size()) {
            throw new IllegalStateException("la suma de resultados no coincide con el número de peticiones");
        }
        printOutcomes(contenders.stream()
                .map(member -> member.name() + " (" + member.id().value() + ")").toList(), borrowOutcomes);
        printSnapshot("Despues", demo, title, contenders, null);
        System.out.printf("  Comprobación: 1 préstamo, %d reservas, %d rechazadas; copia %s, préstamos activos %d%n",
                acceptedHolds, rejections, copy.status(), activeLoans);

        List<Member> queueCandidates = contenders.stream()
                .filter(member -> !member.id().equals(firstLoan.memberId()))
                .filter(member -> !demo.holds().existsActiveByMemberAndTitle(member.id(), title.id()))
                .toList();
        if (queueCandidates.size() < 2) {
            throw new IllegalStateException("faltan dos miembros distintos para la cola");
        }
        Member firstWaiting = queueCandidates.get(0);
        Member secondWaiting = queueCandidates.get(1);
        int beforeWaiting = demo.holds().findWaitingByTitleId(title.id()).size();
        Instant holdTime = START.plusSeconds(1);
        System.out.printf("%n[2] Dos miembros distintos quedan en cola FIFO a las %s%n", holdTime);
        printSnapshot("Antes", demo, title, contenders, null);

        Hold firstHold = demo.at(holdTime).placeHold(firstWaiting.id(), title.id());
        Hold secondHold = demo.at(holdTime.plusSeconds(1)).placeHold(secondWaiting.id(), title.id());

        int afterWaiting = demo.holds().findWaitingByTitleId(title.id()).size();
        if (afterWaiting != beforeWaiting + 2) {
            throw new IllegalStateException("no se crearon las dos reservas esperadas: antes=" + beforeWaiting + ", despues=" + afterWaiting);
        }
        List<Outcome<Hold>> holdOutcomes = List.of(
                new Outcome<>(firstHold, null),
                new Outcome<>(secondHold, null));
        Hold hold = demo.holds().findWaitingByTitleId(title.id()).stream()
                .filter(candidate -> candidate.memberId().equals(firstWaiting.id())
                        || candidate.memberId().equals(secondWaiting.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no aparece ninguna reserva de la cola"));
        printOutcomes(List.of(firstWaiting.name() + " petición 1", secondWaiting.name() + " petición 2"), holdOutcomes);
        printSnapshot("Despues", demo, title, contenders, hold);
        System.out.printf("  Comprobación: %d reservas creadas; cola %d%n",
                afterWaiting - beforeWaiting, demo.holds().findWaitingByTitleId(title.id()).size());

        Hold expectedAssigned = demo.holds().findWaitingByTitleId(title.id()).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no hay ninguna reserva esperando para asignar la copia"));
        Hold assigned = demo.at(START.plusSeconds(2)).returnLoan(firstLoan.id()).orElseThrow();
        if (!assigned.memberId().equals(expectedAssigned.memberId()) || copy.status() != CopyStatus.HELD) {
            throw new IllegalStateException("la copia devuelta no se asignó a la reserva correcta");
        }
        Instant pickupTime = START.plusSeconds(3);
        System.out.printf("%n[3] Dos recogidas de la reserva asignada a las %s%n", pickupTime);
        printSnapshot("Antes", demo, title, contenders, assigned);
        List<Outcome<Loan>> pickupOutcomes = race(List.of(
                () -> demo.at(pickupTime).collectHold(assigned.id()),
                () -> demo.at(pickupTime).collectHold(assigned.id())));
        Loan pickedUp = oneSuccess(pickupOutcomes, "hold is not assigned");
        if (assigned.status() != HoldStatus.COLLECTED || copy.status() != CopyStatus.ON_LOAN
                || !demo.loans().findActiveByCopyId(copy.id()).orElseThrow().id().equals(pickedUp.id())) {
            throw new IllegalStateException("la copia reservada se prestó más de una vez");
        }
        printOutcomes(List.of(assigned.memberId().value() + " recogida 1", assigned.memberId().value() + " recogida 2"), pickupOutcomes);
        printSnapshot("Despues", demo, title, contenders, assigned);
        System.out.printf("  Comprobación: 1 recogida, 1 rechazada; reserva %s, copia %s%n",
                assigned.status(), copy.status());

        Member lateReturnMember = demo.members().findById(assigned.memberId()).orElseThrow();
        Instant lateReturn = pickedUp.dueAt().plus(1, ChronoUnit.DAYS);
        System.out.printf("%n[4] Dos devoluciones tardías del mismo préstamo a las %s%n", lateReturn);
        printSnapshot("Antes", demo, title, contenders, assigned);
        List<Outcome<Optional<Hold>>> returnOutcomes = race(List.of(
                () -> demo.at(lateReturn).returnLoan(pickedUp.id()),
                () -> demo.at(lateReturn).returnLoan(pickedUp.id())));
        Optional<Hold> nextHold = returnOutcomes.stream()
                .filter(outcome -> outcome.rejection() == null)
                .map(Outcome::value)
                .flatMap(Optional::stream)
                .findFirst();
        oneSuccess(returnOutcomes, "loan has already been returned");
        if (copy.status() != CopyStatus.HELD || pickedUp.returnedAt().isEmpty()
                || nextHold.isEmpty() || nextHold.orElseThrow().status() != HoldStatus.ASSIGNED
                || !nextHold.orElseThrow().assignedCopyId().orElse(copy.id()).equals(copy.id())
                || !lateReturnMember.outstandingBalance().equals(demo.policies().finePolicy().dailyOverdueFine())) {
            throw new IllegalStateException("la devolución tardía no asignó la copia a la siguiente reserva o la multa no se aplicó correctamente");
        }
        printOutcomes(List.of(lateReturnMember.name() + " devolución 1", lateReturnMember.name() + " devolución 2"), returnOutcomes);
        printSnapshot("Despues", demo, title, contenders, nextHold.orElseThrow());
        System.out.printf("  Comprobación: 1 devolución, 1 rechazada; devuelta a las %s, saldo %s EUR, copia %s, reserva asignada %s%n",
                pickedUp.returnedAt().orElseThrow(), lateReturnMember.outstandingBalance().amount(), copy.status(), nextHold.orElseThrow().status());
    }

    private static Loan oneSuccessfulLoan(List<Outcome<Action>> outcomes) {
        Loan winner = null;
        int loanCount = 0;
        int holdCount = 0;
        int rejectedCount = 0;
        for (Outcome<Action> outcome : outcomes) {
            if (outcome.rejection() == null) {
                Action action = outcome.value();
                if (action instanceof Loan loan) {
                    winner = loan;
                    loanCount++;
                } else if (action instanceof Hold) {
                    holdCount++;
                } else {
                    throw new IllegalStateException("tipo de acción no esperado: " + action);
                }
            } else {
                String rejection = outcome.rejection();
                if (!"the title has a waiting queue".equals(rejection)) {
                    throw new IllegalStateException("rechazo inesperado: " + rejection);
                }
                rejectedCount++;
            }
        }
        if (loanCount != 1 || holdCount + rejectedCount != outcomes.size() - 1) {
            throw new IllegalStateException("se esperaba 1 préstamo y el resto como reservas o rechazos, pero hubo "
                    + loanCount + " préstamos, " + holdCount + " reservas y " + rejectedCount + " rechazos");
        }
        return winner;
    }

    private static <T> void printOutcomes(List<String> labels, List<Outcome<T>> outcomes) {
        if (labels.size() != outcomes.size()) {
            throw new IllegalArgumentException("cada petición necesita una etiqueta");
        }
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome<T> outcome = outcomes.get(i);
            if (outcome.rejection() == null) {
                String result = outcome.value() instanceof Loan
                        ? "ACEPTADA: Loan"
                        : outcome.value() instanceof Hold
                                ? "ACEPTADA: Hold"
                                : "ACEPTADA: " + outcome.value().getClass().getSimpleName();
                System.out.printf("  Petición %s -> %s%n", labels.get(i), result);
            } else {
                System.out.printf("  Petición %s -> RECHAZADA: %s%n", labels.get(i), outcome.rejection());
            }
        }
    }

    private static void printSnapshot(String phase, Demo demo, Title title,
                                      List<Member> members, Hold hold) {
        List<Copy> copies = demo.copies().findByTitleId(title.id()).stream()
                .sorted(Comparator.comparing(copy -> copy.id().value()))
                .toList();
        long available = copies.stream().filter(copy -> copy.status() == CopyStatus.AVAILABLE).count();
        long onLoan = copies.stream().filter(copy -> copy.status() == CopyStatus.ON_LOAN).count();
        long held = copies.stream().filter(copy -> copy.status() == CopyStatus.HELD).count();
        long assignedHolds = demo.holds().findActiveByTitleId(title.id()).stream()
                .filter(active -> active.status() == HoldStatus.ASSIGNED).count();
        System.out.printf("  %s - Título %s (%s): copias %d [DISPONIBLES=%d, PRESTADAS=%d, RESERVADAS=%d], "
                        + "reservas [ESPERANDO=%d, ASIGNADAS=%d]%n",
                phase, title.name(), title.id().value(), copies.size(), available, onLoan, held,
                demo.holds().findWaitingByTitleId(title.id()).size(), assignedHolds);

        for (Copy copy : copies) {
            String activeLoan = demo.loans().findActiveByCopyId(copy.id())
                    .map(loan -> loan.memberId().value() + " (vencimiento " + loan.dueAt() + ")")
                    .orElse("ninguno");
            System.out.printf("    Copia %s: %s; préstamo activo: %s%n",
                    copy.id().value(), copy.status(), activeLoan);
        }
        for (Member member : members.stream()
                .sorted(Comparator.comparing(candidate -> candidate.id().value())).toList()) {
            System.out.printf("    Miembro %s (%s, %s): préstamos activos=%d, saldo=%s EUR%n",
                    member.name(), member.id().value(), member.tier(),
                    demo.loans().countActiveByMemberId(member.id()), member.outstandingBalance().amount());
        }
        if (hold != null) {
            System.out.printf("    Reserva para %s: %s, copia asignada=%s, vence=%s%n",
                    hold.memberId().value(), hold.status(),
                    hold.assignedCopyId().map(CopyId::value).orElse("ninguna"),
                    hold.expiresAt().map(Instant::toString).orElse("ninguna"));
        }
    }

    private static <T> T oneSuccess(List<Outcome<T>> outcomes, String expectedRejection) {
        T winner = null;
        int successful = 0;
        for (Outcome<T> outcome : outcomes) {
            if (outcome.rejection() == null) {
                winner = outcome.value();
                successful++;
            } else if (!expectedRejection.equals(outcome.rejection())) {
                throw new IllegalStateException("rechazo inesperado: " + outcome.rejection());
            }
        }
        if (successful != 1) {
            throw new IllegalStateException("se esperaba una petición correcta y hubo " + successful);
        }
        return winner;
    }

    private static <T> List<Outcome<T>> race(List<? extends Supplier<T>> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        try {
            List<Future<Outcome<T>>> futures = new ArrayList<>();
            for (Supplier<T> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!go.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("las peticiones no empezaron a la vez");
                    }
                    try {
                        return new Outcome<>(request.get(), null);
                    } catch (DomainException rejected) {
                        return new Outcome<>(null, rejected.getMessage());
                    }
                }));
            }
            if (!ready.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("las peticiones no estaban listas");
            }
            go.countDown();

            List<Outcome<T>> outcomes = new ArrayList<>();
            for (Future<Outcome<T>> future : futures) {
                outcomes.add(future.get(5, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            go.countDown();
            executor.shutdownNow();
        }
    }

    private record Outcome<T>(T value, String rejection) {
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
