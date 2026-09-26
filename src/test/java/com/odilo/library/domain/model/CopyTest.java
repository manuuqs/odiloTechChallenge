package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.odilo.library.domain.exception.DomainException;
import org.junit.jupiter.api.Test;

class CopyTest {

    @Test
    void newCopiesOfTheSameTitleHaveDistinctIdsAndAreAvailable() {
        TitleId titleId = new TitleId("title-1");

        Copy first = new Copy(new CopyId("copy-1"), titleId);
        Copy second = new Copy(new CopyId("copy-2"), titleId);

        assertEquals(titleId, first.titleId());
        assertEquals(titleId, second.titleId());
        assertEquals(new CopyId("copy-1"), first.id());
        assertEquals(new CopyId("copy-2"), second.id());
        assertEquals(CopyStatus.AVAILABLE, first.status());
        assertEquals(CopyStatus.AVAILABLE, second.status());
    }

    @Test
    void rejectsMissingCopyOrTitleIdentifier() {
        assertThrows(NullPointerException.class, () -> new Copy(null, new TitleId("title-1")));
        assertThrows(NullPointerException.class, () -> new Copy(new CopyId("copy-1"), null));
    }

    @Test
    void cannotLendTheSameCopyTwice() {
        Copy copy = new Copy(new CopyId("copy-1"), new TitleId("title-1"));

        copy.markOnLoan();

        assertEquals(CopyStatus.ON_LOAN, copy.status());
        assertThrows(DomainException.class, copy::markOnLoan);
    }

    @Test
    void aReturnedCopyCanBeHeldAndIsNotAvailableUntilReleased() {
        Copy copy = new Copy(new CopyId("copy-1"), new TitleId("title-1"));
        assertThrows(DomainException.class, copy::markHeld);
        copy.markOnLoan();
        copy.markHeld();

        assertEquals(CopyStatus.HELD, copy.status());
        assertThrows(DomainException.class, copy::markOnLoan);
        assertThrows(DomainException.class, copy::markAvailableFromLoan);
        assertEquals(CopyStatus.HELD, copy.status());

        copy.markAvailableFromHold();
        assertEquals(CopyStatus.AVAILABLE, copy.status());
        copy.markOnLoan();
        copy.markAvailableFromLoan();
        assertEquals(CopyStatus.AVAILABLE, copy.status());
    }

    @Test
    void collectingAReservedCopyTransitionsFromHeldToOnLoan() {
        Copy copy = new Copy(new CopyId("copy-1"), new TitleId("title-1"));
        copy.markOnLoan();
        copy.markHeld();

        copy.markOnLoanFromHold();

        assertEquals(CopyStatus.ON_LOAN, copy.status());
        assertThrows(DomainException.class, copy::markOnLoanFromHold);
    }
}
