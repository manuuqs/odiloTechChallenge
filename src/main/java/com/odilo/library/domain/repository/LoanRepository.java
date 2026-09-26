package com.odilo.library.domain.repository;

import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.MemberId;

import java.util.List;
import java.util.Optional;

public interface LoanRepository {

    Optional<Loan> findById(LoanId id);

    Optional<Loan> findActiveByCopyId(CopyId copyId);

    List<Loan> findActiveByMemberId(MemberId memberId);

    long countActiveByMemberId(MemberId memberId);

    void save(Loan loan);
}