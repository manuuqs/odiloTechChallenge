package com.odilo.library.infrastructure.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.odilo.library.domain.model.Title;
import com.odilo.library.domain.model.TitleId;
import com.odilo.library.domain.repository.TitleRepository;
import org.junit.jupiter.api.Test;

class InMemoryTitleRepositoryTest {

    @Test
    void savesFindsAndReplacesTitleById() {
        TitleRepository titles = new InMemoryTitleRepository();
        TitleId id = new TitleId("title-1");
        assertTrue(titles.findById(id).isEmpty());

        titles.save(new Title(id, "First name"));
        titles.save(new Title(id, "Updated name"));

        assertEquals("Updated name", titles.findById(id).orElseThrow().name());
    }

    @Test
    void rejectsNullInputs() {
        TitleRepository titles = new InMemoryTitleRepository();
        assertThrows(NullPointerException.class, () -> titles.findById(null));
        assertThrows(NullPointerException.class, () -> titles.save(null));
    }
}
