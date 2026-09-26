package com.odilo.library.application;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyStatus;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
import com.odilo.library.domain.model.HoldStatus;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.TitleId;
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

    public LibraryService(TitleRepository titles, CopyRepository copies, MemberRepository members,
                          LoanRepository loans, HoldRepository holds, PolicyProvider policies, Clock clock) {
        this.titles = Objects.requireNonNull(titles, "title repository cannot be null");
        this.copies = Objects.requireNonNull(copies, "copy repository cannot be null");
        this.members = Objects.requireNonNull(members, "member repository cannot be null");
        this.loans = Objects.requireNonNull(loans, "loan repository cannot be null");
        this.holds = Objects.requireNonNull(holds, "hold repository cannot be null");
        this.policies = Objects.requireNonNull(policies, "policy provider cannot be null");
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    //solicita un préstamo de una copia disponible de un título para un miembro
    public Loan borrow(MemberId memberId, TitleId titleId) {
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

            if (member.outstandingBalance().amount()
                    .compareTo(policies.finePolicy().borrowingBlockThreshold().amount()) > 0) { //comprobamos deuda existente del miembro
                throw new DomainException("outstanding fines block new loans");
            }
            TierPolicy tierPolicy = policies.tierPolicy(member.tier());
            if (loans.countActiveByMemberId(memberId) >= tierPolicy.maximumActiveLoans()) { //comprobar max prestamos permitidos por el nivel del miembro
                throw new DomainException("member has reached the active-loan limit");
            }
            if (hasActiveLoanForTitle(memberId, titleId)) { // un miembro no puede tener dos préstamos activos del mismo título
                throw new DomainException("member already has this title on loan");
            }
            if (!holds.findWaitingByTitleId(titleId).isEmpty()) {
                throw new DomainException("the title has a waiting queue");
            }

            Copy copy = copies.findAvailableByTitleId(titleId).stream()
                    .min(Comparator.comparing(candidate -> candidate.id().value()))
                    .orElseThrow(() -> new DomainException("no available copy; request a hold"));
            if (loans.findActiveByCopyId(copy.id()).isPresent()) {
                throw new DomainException("copy already has an active loan");
            }

            Loan loan = new Loan(new LoanId(UUID.randomUUID().toString()), memberId, copy.id(),
                    now, tierPolicy.dueAt(now));
            copy.markOnLoan();
            copies.save(copy);
            loans.save(loan);
            return loan;
        }
    }

    // solicita una reserva de un título para un miembro, si no hay copias disponibles
    public Hold placeHold(MemberId memberId, TitleId titleId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");
        Objects.requireNonNull(titleId, "title ID cannot be null");

        // prestamo e incorporacion a cola emplean mimso bloqueo para evitar que se pueda prestar una copia mientras otro miembro solicita un hold,
        // y viceversa, evitando que se pueda prestar la última copia mientras otro miembro solicita un hold// inserting a hold while another request assigns the last copy.
        synchronized (copies) {
            Instant now = clock.instant();
            members.findById(memberId).orElseThrow(() -> new DomainException("member not found"));
            titles.findById(titleId).orElseThrow(() -> new DomainException("title not found"));
            expireOverdueHolds(titleId, now); // procesa las reservas asignadas cuyo plazo ya venció.

            if (holds.existsActiveByMemberAndTitle(memberId, titleId)) {
                throw new DomainException("member already has an active hold for this title");
            }
            if (hasActiveLoanForTitle(memberId, titleId)) {
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
            Copy copy = copies.findById(loan.copyId())
                    .orElseThrow(() -> new DomainException("loan copy not found"));
            if (copy.status() != CopyStatus.ON_LOAN || loans.findActiveByCopyId(copy.id())
                    .filter(active -> active.id().equals(loanId)).isEmpty()) {
                throw new DomainException("copy and active loan are inconsistent");
            }

            Instant now = clock.instant();
            if (now.isBefore(loan.startedAt())) {
                throw new DomainException("return time cannot precede loan start");
            }
            expireOverdueHolds(copy.titleId(), now); // procesa las reservas asignadas cuyo plazo ya venció.
            //buscamos la siguiente reserva pendiente de la misma obra,
            // si existe asignamos la copia devuelta a esa reserva y marcamos la copia como HOLD, si no existe marcamos la copia como disponible
            Hold next = holds.findWaitingByTitleId(copy.titleId()).stream().findFirst().orElse(null);
            Instant expiration = null;
            if (next != null) {
                if (now.isBefore(next.createdAt())) {
                    throw new DomainException("hold cannot be assigned before its creation");
                }
                Duration pickupWindow = Objects.requireNonNull(policies.holdPickupWindow(),
                        "hold pickup window cannot be null");
                if (pickupWindow.isZero() || pickupWindow.isNegative()) {
                    throw new DomainException("hold pickup window must be positive");
                }
                expiration = now.plus(pickupWindow); // la fecha de expiración de la reserva es la fecha actual más el tiempo de recogida permitido (cuarenta y ocho horas por defecto)
            }

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
            return Optional.ofNullable(next);
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
            if (member.outstandingBalance().amount()
                    .compareTo(policies.finePolicy().borrowingBlockThreshold().amount()) > 0) {
                releaseHold(hold, copy, now, true);
                throw new DomainException("outstanding fines forfeit the assigned hold");
            }
            // Si el miembro ya tiene un préstamo activo del mismo título, pierde la reserva mediante forfeitAt.
            if (hasActiveLoanForTitle(member.id(), hold.titleId())) {
                releaseHold(hold, copy, now, true);
                throw new DomainException("member already has this title on loan");
            }
            // Si el miembro ha alcanzado el límite de préstamos activos según su nivel de membresía, se rechaza temporalmente la recogida de la reserva y se le notifica que ha alcanzado el límite de préstamos activos.
            TierPolicy policy = policies.tierPolicy(member.tier());
            if (loans.countActiveByMemberId(member.id()) >= policy.maximumActiveLoans()) {
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

    private boolean hasActiveLoanForTitle(MemberId memberId, TitleId titleId) {
        boolean alreadyBorrowed = false;
        for (Loan loan : loans.findActiveByMemberId(memberId)) {
            Copy loanCopy = copies.findById(loan.copyId())
                    .orElseThrow(() -> new DomainException("active loan copy not found: " + loan.copyId().value()));
            if (loanCopy.titleId().equals(titleId)) {
                alreadyBorrowed = true;
            }
        }
        return alreadyBorrowed;
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
            if (now.isBefore(next.createdAt())) {
                throw new DomainException("hold cannot be assigned before its creation");
            }
            Duration pickupWindow = Objects.requireNonNull(policies.holdPickupWindow(),
                    "hold pickup window cannot be null");
            if (pickupWindow.isZero() || pickupWindow.isNegative()) {
                throw new DomainException("hold pickup window must be positive");
            }
            nextExpiration = now.plus(pickupWindow);
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

    private void expireOverdueHolds(TitleId titleId, Instant now) {
        for (Hold hold : holds.findActiveByTitleId(titleId)) {
            if (hold.status() == HoldStatus.ASSIGNED
                    && !now.isBefore(hold.expiresAt().orElseThrow())) {
                releaseHold(hold, heldCopy(hold), now, false);
            }
        }
    }
}
