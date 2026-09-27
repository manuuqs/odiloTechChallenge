package com.odilo.library.application;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.*;
import com.odilo.library.domain.policy.PolicyProvider;
import com.odilo.library.domain.policy.TierPolicy;
import com.odilo.library.domain.repository.CopyRepository;
import com.odilo.library.domain.repository.HoldRepository;
import com.odilo.library.domain.repository.LoanRepository;
import com.odilo.library.domain.repository.MemberRepository;
import com.odilo.library.domain.repository.TitleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class LibraryService {

    private final TitleRepository titles;
    private final CopyRepository copies;
    private final MemberRepository members;
    private final LoanRepository loans;
    private final HoldRepository holds;
    private final PolicyProvider policies;
    private final Clock clock;
    private final LibraryChecks checks;

    public LibraryService(TitleRepository titles, CopyRepository copies, MemberRepository members,
                          LoanRepository loans, HoldRepository holds, PolicyProvider policies, Clock clock) {
        this.titles = Objects.requireNonNull(titles, "title repository cannot be null");
        this.copies = Objects.requireNonNull(copies, "copy repository cannot be null");
        this.members = Objects.requireNonNull(members, "member repository cannot be null");
        this.loans = Objects.requireNonNull(loans, "loan repository cannot be null");
        this.holds = Objects.requireNonNull(holds, "hold repository cannot be null");
        this.policies = Objects.requireNonNull(policies, "policy provider cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.checks = new LibraryChecks(this.copies, this.loans, this.policies);
    }

    //solicita un préstamo de una copia disponible de un título para un miembro
    public Action borrow(MemberId memberId, TitleId titleId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");
        Objects.requireNonNull(titleId, "title ID cannot be null");

        // el obejeto copies lo usamos como lock para que no se pueda prestar la misma copia a dos miembros al mismo tiempo

        /*
        Para varias instancias habría que usar una transacción de base de datos con bloqueo de fila, por ejemplo SELECT ... FOR UPDATE.
         */
        synchronized (copies) {
            Instant now = clock.instant();
            Member member = members.findById(memberId)
                    .orElseThrow(() -> new DomainException("member not found"));
            titles.findById(titleId).orElseThrow(() -> new DomainException("title not found"));
            expireOverdueHolds(titleId, now); // procesa las reservas asignadas cuyo plazo ya venció.

            if (checks.hasBlockingDebt(member)) { //comprobamos deuda existente del miembro
                throw new DomainException("outstanding fines block new loans");
            }
            TierPolicy tierPolicy = policies.tierPolicy(member.tier());
            if (checks.hasReachedLoanLimit(member, tierPolicy)) { //comprobar max prestamos permitidos por el nivel del miembro
                throw new DomainException("member has reached the active-loan limit");
            }
            if (checks.hasActiveLoanForTitle(memberId, titleId)) { // un miembro no puede tener dos préstamos activos del mismo título
                throw new DomainException("member already has this title on loan");
            }
            if (!holds.findWaitingByTitleId(titleId).isEmpty()) {
                throw new DomainException("the title has a waiting queue");
            }

            Copy availableCopy = copies.findAvailableByTitleId(titleId).stream()
                    .min(Comparator.comparing(candidate -> candidate.id().value()))
                    .orElse(null);

            if (availableCopy != null) {
                if (!holds.findWaitingByTitleId(titleId).isEmpty()) {
                    throw new DomainException("the title has a waiting queue");
                }

                Loan loan = new Loan(new LoanId(UUID.randomUUID().toString()), memberId, availableCopy.id(),
                        now, tierPolicy.dueAt(now));
                availableCopy.markOnLoan();
                copies.save(availableCopy);
                loans.save(loan);
                return loan;

//            if (loans.findActiveByCopyId(availableCopy.id()).isPresent()) {
//                System.out.println("copy already has an active loan");
//                return placeHold(memberId, titleId);
            }
            return placeHold(memberId, titleId);
        }
    }

    // solicita una reserva de un título para un miembro
    public Hold placeHold(MemberId memberId, TitleId titleId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");
        Objects.requireNonNull(titleId, "title ID cannot be null");

        // prestamo e incorporacion a cola emplean mimso bloqueo para evitar que se pueda prestar una copia mientras otro miembro solicita un hold,
        // y viceversa, evitando que se pueda prestar la última copia mientras otro miembro solicita un hold// inserting a hold while another request assigns the last copy.
        synchronized (copies) {
            Instant now = clock.instant();
            Member member = members.findById(memberId)
                    .orElseThrow(() -> new DomainException("member not found"));
            titles.findById(titleId).orElseThrow(() -> new DomainException("title not found"));
            expireOverdueHolds(titleId, now); // procesa las reservas asignadas cuyo plazo ya venció.

            if (checks.hasBlockingDebt(member)) {
                throw new DomainException("outstanding fines block holds");
            }
            if (holds.existsActiveByMemberAndTitle(memberId, titleId)) {
                throw new DomainException("member already has an active hold for this title");
            }
            if (checks.hasActiveLoanForTitle(memberId, titleId)) {
                throw new DomainException("member already has this title on loan");
            }
            if (!copies.findAvailableByTitleId(titleId).isEmpty()) {
                throw new DomainException("copy available; borrow instead");
            }

            Hold hold = new Hold(new HoldId(UUID.randomUUID().toString()), memberId, titleId, now);
            holds.save(hold);
            return hold;
        }
    }

    // devuelve una copia prestada, si hay reservas pendientes de la misma obra se asigna la copia devuelta a la primera reserva pendiente y se marca como HOLD,
    // si no hay reservas pendientes se marca como disponible
    public Optional<Hold> returnLoan(LoanId loanId) {
        Objects.requireNonNull(loanId, "loan ID cannot be null");

        // copia devuelta no debe hacerse visible para quien la tomo prestada antes de que se asigne la siguiente reserva
        synchronized (copies) {
            Loan loan = loans.findById(loanId)
                    .orElseThrow(() -> new DomainException("loan not found"));
            if (loan.returnedAt().isPresent()) {
                throw new DomainException("loan has already been returned");
            }
            Copy copy = checks.requireActiveLoanCopy(loan);

            Instant now = clock.instant();
            if (now.isBefore(loan.startedAt())) {
                throw new DomainException("return time cannot precede loan start");
            }
            Member borrower = members.findById(loan.memberId())
                    .orElseThrow(() -> new DomainException("member not found"));
            expireOverdueHolds(copy.titleId(), now); // procesa las reservas asignadas cuyo plazo ya venció.
            //buscamos la siguiente reserva pendiente de la misma obra,
            // si existe asignamos la copia devuelta a esa reserva y marcamos la copia como HOLD, si no existe marcamos la copia como disponible
            Hold next = holds.findWaitingByTitleId(copy.titleId()).stream().findFirst().orElse(null);
            Instant expiration = null;
            if (next != null) {
                expiration = pickupExpiration(next, now);
            }

            // calculamos la multa por devolución tardía, si corresponde, y actualizamos el saldo pendiente del miembro
            Money fine = policies.finePolicy().fineFor(loan.dueAt(), now);
            Money newBalance = borrower.outstandingBalance().add(fine);

            loan.markReturned(now);
            if (next == null) {
                copy.markAvailableFromLoan();
            } else {
                next.assignCopy(copy.id(), now, expiration);
                copy.markHeld();
                holds.save(next);
            }
            loans.save(loan);
            copies.save(copy);
            if (!fine.equals(Money.ZERO)) {
                borrower.updateOutstandingBalance(newBalance);
                members.save(borrower);
            }
            return Optional.ofNullable(next);
        }
    }

    // renueva un préstamo activo, si el préstamo está vencido, si el miembro tiene deuda superior a 10,00 €,
    // si otro miembro tiene una reserva pendiente de la misma obra o si se ha alcanzado el límite de renovaciones según el nivel del miembro, se lanza una excepción
    public Loan renew(LoanId loanId) {
        Objects.requireNonNull(loanId, "loan ID cannot be null");

        synchronized (copies) {
            Loan loan = loans.findById(loanId)
                    .orElseThrow(() -> new DomainException("loan not found"));
            if (loan.returnedAt().isPresent()) {
                throw new DomainException("returned loan cannot be renewed");
            }
            Copy copy = checks.requireActiveLoanCopy(loan);
            Member member = members.findById(loan.memberId())
                    .orElseThrow(() -> new DomainException("member not found"));

            Instant now = clock.instant();
            if (now.isBefore(loan.startedAt())) {
                throw new DomainException("renewal time cannot precede loan start");
            }
            // se aplica para evitar que una reserva asignada pero ya vencida siga bloqueando una copia o la cola.
            expireOverdueHolds(copy.titleId(), now);
            if (!now.isBefore(loan.dueAt())) {
                throw new DomainException("overdue loan cannot be renewed");
            }
            if (checks.hasBlockingDebt(member)) {
                throw new DomainException("outstanding fines block renewals");
            }
            if (holds.findWaitingByTitleId(copy.titleId()).stream()
                    .anyMatch(hold -> !hold.memberId().equals(member.id()))) {
                throw new DomainException("another member is waiting for this title");
            }

            TierPolicy policy = policies.tierPolicy(member.tier());
            if (policy.maximumRenewals().isPresent()
                    && loan.renewalCount() >= policy.maximumRenewals().getAsInt()) {
                throw new DomainException("loan has reached the renewal limit");
            }

            loan.renewUntil(loan.dueAt().plus(policy.loanDurationDays(), ChronoUnit.DAYS));
            loans.save(loan);
            return loan;
        }
    }

    //convierte una reserva asignada en un préstamo
    public Loan collectHold(HoldId holdId) {
        Objects.requireNonNull(holdId, "hold ID cannot be null");

        synchronized (copies) {
            Hold hold = assignedHold(holdId);
            Copy copy = heldCopy(hold);
            Instant now = clock.instant();

            //comprobamos que este en la ventana de recogida
            if (now.isBefore(hold.assignedAt().orElseThrow())) {
                throw new DomainException("pickup time cannot precede assignment");
            }
            if (!now.isBefore(hold.expiresAt().orElseThrow())) {
                releaseHold(hold, copy, now, false);
                throw new DomainException("hold has expired");
            }

           // Si el miembro tiene deuda superior a 10,00 €, pierde la reserva mediante forfeitAt.
            Member member = members.findById(hold.memberId())
                    .orElseThrow(() -> new DomainException("member not found"));
            if (checks.hasBlockingDebt(member)) {
                releaseHold(hold, copy, now, true);
                throw new DomainException("outstanding fines forfeit the assigned hold");
            }
            // Si el miembro ya tiene un préstamo activo del mismo título, pierde la reserva mediante forfeitAt.
            if (checks.hasActiveLoanForTitle(member.id(), hold.titleId())) {
                releaseHold(hold, copy, now, true);
                throw new DomainException("member already has this title on loan");
            }
            // Si el miembro ha alcanzado el límite de préstamos activos según su nivel de membresía, se rechaza temporalmente la recogida de la reserva y se le notifica que ha alcanzado el límite de préstamos activos.
            TierPolicy policy = policies.tierPolicy(member.tier());
            if (checks.hasReachedLoanLimit(member, policy)) {
                throw new DomainException("member has reached the active-loan limit");
            }

            Loan loan = new Loan(new LoanId(UUID.randomUUID().toString()), member.id(), copy.id(),
                    now, policy.dueAt(now));
            hold.collectAt(now);
            copy.markOnLoanFromHold();
            holds.save(hold);
            copies.save(copy);
            loans.save(loan);
            return loan;
        }
    }

    public Optional<Hold> expireHold(HoldId holdId) {
        Objects.requireNonNull(holdId, "hold ID cannot be null");

        synchronized (copies) {
            Hold hold = assignedHold(holdId);
            Copy copy = heldCopy(hold);
            Instant now = clock.instant();
            if (now.isBefore(hold.expiresAt().orElseThrow())) {
                throw new DomainException("hold cannot expire yet");
            }
            return releaseHold(hold, copy, now, false);
        }
    }

    private Hold assignedHold(HoldId holdId) {
        Hold hold = holds.findById(holdId).orElseThrow(() -> new DomainException("hold not found"));
        if (hold.status() != HoldStatus.ASSIGNED) {
            throw new DomainException("hold is not assigned");
        }
        return hold;
    }

    private Copy heldCopy(Hold hold) {
        Copy copy = copies.findById(hold.assignedCopyId().orElseThrow())
                .orElseThrow(() -> new DomainException("assigned copy not found"));
        if (copy.status() != CopyStatus.HELD || !copy.titleId().equals(hold.titleId())
                || loans.findActiveByCopyId(copy.id()).isPresent()) {
            throw new DomainException("assigned hold and copy are inconsistent");
        }
        return copy;
    }

    private Optional<Hold> releaseHold(Hold current, Copy copy, Instant now, boolean forfeited) {
        Hold next = holds.findWaitingByTitleId(current.titleId()).stream().findFirst().orElse(null);
        Instant nextExpiration = null;
        if (next != null) {
            nextExpiration = pickupExpiration(next, now);
        }

        if (forfeited) {
            current.forfeitAt(now);
        } else {
            current.expireAt(now);
        }
        holds.save(current);
        if (next == null) {
            copy.markAvailableFromHold();
        } else {
            next.assignCopy(copy.id(), now, nextExpiration);
            holds.save(next);
        }
        copies.save(copy);
        return Optional.ofNullable(next);
    }

    private Instant pickupExpiration(Hold hold, Instant now) {
        if (now.isBefore(hold.createdAt())) {
            throw new DomainException("hold cannot be assigned before its creation");
        }
        Duration pickupWindow = Objects.requireNonNull(policies.holdPickupWindow(),
                "hold pickup window cannot be null");
        if (pickupWindow.isZero() || pickupWindow.isNegative()) {
            throw new DomainException("hold pickup window must be positive");
        }
        return now.plus(pickupWindow);
    }

    private void expireOverdueHolds(TitleId titleId, Instant now) {
        for (Hold hold : holds.findActiveByTitleId(titleId)) {
            if (hold.status() == HoldStatus.ASSIGNED
                    && !now.isBefore(hold.expiresAt().orElseThrow())) {
                releaseHold(hold, heldCopy(hold), now, false);
            }
        }
    }
}
