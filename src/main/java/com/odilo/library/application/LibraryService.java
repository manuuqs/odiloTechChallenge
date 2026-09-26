package com.odilo.library.application;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.Hold;
import com.odilo.library.domain.model.HoldId;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
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

    public Loan borrow(MemberId memberId, TitleId titleId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");
        Objects.requireNonNull(titleId, "title ID cannot be null");

        // el obejeto copies lo usamos como lock para que no se pueda prestar la misma copia a dos miembros al mismo tiempo

        /*
        Para varias instancias habría que usar una transacción de base de datos con bloqueo de fila, por ejemplo SELECT ... FOR UPDATE.
         */
        synchronized (copies) {
            Member member = members.findById(memberId)
                    .orElseThrow(() -> new DomainException("member not found"));
            titles.findById(titleId).orElseThrow(() -> new DomainException("title not found"));

            if (member.outstandingBalance().amount()
                    .compareTo(policies.finePolicy().borrowingBlockThreshold().amount()) > 0) { //comprobamos deuda existente del miembro
                throw new DomainException("outstanding fines block new loans");
            }
            TierPolicy tierPolicy = policies.tierPolicy(member.tier());
            if (loans.countActiveByMemberId(memberId) >= tierPolicy.maximumActiveLoans()) { //comprobar max prestamos permitidos por el nivel del miembro
                throw new DomainException("member has reached the active-loan limit");
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

            Instant startedAt = clock.instant();
            Loan loan = new Loan(new LoanId(UUID.randomUUID().toString()), memberId, copy.id(),
                    startedAt, tierPolicy.dueAt(startedAt));
            copy.markOnLoan();
            copies.save(copy);
            loans.save(loan);
            return loan;
        }
    }

    public Hold placeHold(MemberId memberId, TitleId titleId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");
        Objects.requireNonNull(titleId, "title ID cannot be null");

        // prestamo e incorporacion a cola emplean mimso bloqueo para evitar que se pueda prestar una copia mientras otro miembro solicita un hold,
        // y viceversa, evitando que se pueda prestar la última copia mientras otro miembro solicita un hold// inserting a hold while another request assigns the last copy.
        synchronized (copies) {
            members.findById(memberId).orElseThrow(() -> new DomainException("member not found"));
            titles.findById(titleId).orElseThrow(() -> new DomainException("title not found"));

            if (holds.existsActiveByMemberAndTitle(memberId, titleId)) {
                throw new DomainException("member already has an active hold for this title");
            }
            boolean alreadyBorrowed = loans.findActiveByMemberId(memberId).stream()
                    .anyMatch(loan -> copies.findById(loan.copyId())
                            .filter(copy -> copy.titleId().equals(titleId)).isPresent());
            if (alreadyBorrowed) {
                throw new DomainException("member already has this title on loan");
            }
            if (!copies.findAvailableByTitleId(titleId).isEmpty()) {
                throw new DomainException("copy available; borrow instead");
            }

            Hold hold = new Hold(new HoldId(UUID.randomUUID().toString()), memberId, titleId, clock.instant());
            holds.save(hold);
            return hold;
        }
    }
}
