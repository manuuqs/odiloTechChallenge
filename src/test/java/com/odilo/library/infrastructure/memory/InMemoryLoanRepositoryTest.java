package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.Loan;
import com.odilo.library.domain.model.LoanId;
import com.odilo.library.domain.model.MemberId;
import com.odilo.library.domain.repository.LoanRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryLoanRepositoryTest {

    private static final Instant START = Instant.parse("2026-09-26T12:00:00Z");

    @Test
    void excludesReturnedLoansFromActiveQueriesAndCount() {
        LoanRepository loans = new InMemoryLoanRepository();
        MemberId member = new MemberId("member-1");
        Loan returned = loan("loan-1", member, "copy-1");
        Loan active = loan("loan-2", member, "copy-2");
        loans.save(returned);
        loans.save(active);
        returned.markReturned(START.plus(15, ChronoUnit.DAYS));

        assertTrue(loans.findActiveByCopyId(returned.copyId()).isEmpty());
        assertEquals(active, loans.findActiveByCopyId(active.copyId()).orElseThrow());
        assertEquals(List.of(active), loans.findActiveByMemberId(member));
        assertEquals(1, loans.countActiveByMemberId(member));
        assertEquals(0, loans.countActiveByMemberId(new MemberId("missing")));
        assertEquals(returned, loans.findById(returned.id()).orElseThrow());
        assertTrue(loans.findById(new LoanId("missing")).isEmpty());
    }

    @Test
    void rejectsNullInputs() {
        LoanRepository loans = new InMemoryLoanRepository();
        assertThrows(NullPointerException.class, () -> loans.findById(null));
        assertThrows(NullPointerException.class, () -> loans.findActiveByCopyId(null));
        assertThrows(NullPointerException.class, () -> loans.findActiveByMemberId(null));
        assertThrows(NullPointerException.class, () -> loans.save(null));
    }

    private static Loan loan(String id, MemberId member, String copyId) {
        return new Loan(new LoanId(id), member, new CopyId(copyId), START, START.plus(14, ChronoUnit.DAYS));
    }
}
