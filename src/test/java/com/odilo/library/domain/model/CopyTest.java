package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
