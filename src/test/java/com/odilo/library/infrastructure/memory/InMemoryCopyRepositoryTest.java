package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.model.Copy;
import com.odilo.library.domain.model.CopyId;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.CopyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class InMemoryCopyRepositoryTest {

    @Test
    void filtersAvailableCopiesByTitleWithoutExposingTheInternalCollection() {
        CopyRepository copies = new InMemoryCopyRepository();
        TitleId titleId = new TitleId("title-1");
        Copy available = new Copy(new CopyId("copy-1"), titleId);
        Copy onLoan = new Copy(new CopyId("copy-2"), titleId);
        Copy otherTitle = new Copy(new CopyId("copy-3"), new TitleId("title-2"));
        onLoan.markOnLoan();
        copies.save(available);
        copies.save(onLoan);
        copies.save(otherTitle);

        assertEquals(2, copies.findByTitleId(titleId).size());
        List<Copy> found = copies.findAvailableByTitleId(titleId);
        assertEquals(List.of(available), found);
        assertThrows(UnsupportedOperationException.class, () -> found.add(otherTitle));
        assertTrue(copies.findAvailableByTitleId(new TitleId("missing")).isEmpty());
        assertEquals(available, copies.findById(available.id()).orElseThrow());
        assertTrue(copies.findById(new CopyId("missing")).isEmpty());
    }

    @Test
    void rejectsNullInputs() {
        CopyRepository copies = new InMemoryCopyRepository();
        assertThrows(NullPointerException.class, () -> copies.findById(null));
        assertThrows(NullPointerException.class, () -> copies.findByTitleId(null));
        assertThrows(NullPointerException.class, () -> copies.findAvailableByTitleId(null));
        assertThrows(NullPointerException.class, () -> copies.save(null));
    }
}
