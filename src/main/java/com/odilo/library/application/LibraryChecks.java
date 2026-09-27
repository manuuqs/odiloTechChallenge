package com.odilo.library.application;

import com.odilo.library.domain.exception.DomainException;
import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyStatus;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.Member;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.policy.PolicyProvider;
import com.odilo.library.domain.policy.TierPolicy;
import com.odilo.library.domain.repository.CopyRepository;
import com.odilo.library.domain.repository.LoanRepository;
import java.util.Objects;

final class LibraryChecks {

    private final CopyRepository copies;
    private final LoanRepository loans;
    private final PolicyProvider policies;

    LibraryChecks(CopyRepository copies, LoanRepository loans, PolicyProvider policies) {
        this.copies = Objects.requireNonNull(copies, "copy repository cannot be null");
        this.loans = Objects.requireNonNull(loans, "loan repository cannot be null");
        this.policies = Objects.requireNonNull(policies, "policy provider cannot be null");
    }

    boolean hasBlockingDebt(Member member) {
        return member.outstandingBalance().amount()
                .compareTo(policies.finePolicy().borrowingBlockThreshold().amount()) > 0;
    }

    boolean hasReachedLoanLimit(Member member, TierPolicy policy) {
        return loans.countActiveByMemberId(member.id()) >= policy.maximumActiveLoans();
    }

    boolean hasActiveLoanForTitle(MemberId memberId, TitleId titleId) {
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

    Copy requireActiveLoanCopy(Loan loan) {
        Copy copy = copies.findById(loan.copyId())
                .orElseThrow(() -> new DomainException("loan copy not found"));
        if (copy.status() != CopyStatus.ON_LOAN || loans.findActiveByCopyId(copy.id())
                .filter(active -> active.id().equals(loan.id())).isEmpty()) {
            throw new DomainException("copy and active loan are inconsistent");
        }
        return copy;
    }
}
