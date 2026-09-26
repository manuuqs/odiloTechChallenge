package com.odilo.library.infrastructure.memory;

import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.repository.LoanRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryLoanRepository implements LoanRepository {

    private final ConcurrentMap<LoanId, Loan> loans = new ConcurrentHashMap<>();

    @Override
    public Optional<Loan> findById(LoanId id) {
        return Optional.ofNullable(loans.get(
                Objects.requireNonNull(id, "loan ID cannot be null")));
    }

    @Override
    public Optional<Loan> findActiveByCopyId(CopyId copyId) {
        Objects.requireNonNull(copyId, "copy ID cannot be null");

        return loans.values()
                .stream()
                .filter(loan -> loan.copyId().equals(copyId))
                .filter(loan -> loan.returnedAt().isEmpty())
                .findFirst();
    }

    @Override
    public List<Loan> findActiveByMemberId(MemberId memberId) {
        Objects.requireNonNull(memberId, "member ID cannot be null");

        return loans.values()
                .stream()
                .filter(loan -> loan.memberId().equals(memberId))
                .filter(loan -> loan.returnedAt().isEmpty())
                .toList();
    }

    @Override
    public long countActiveByMemberId(MemberId memberId) {
        return findActiveByMemberId(memberId).size();
    }

    @Override
    public void save(Loan loan) {
        Objects.requireNonNull(loan, "loan cannot be null");
        loans.put(loan.id(), loan);
    }
}