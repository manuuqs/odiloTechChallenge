package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TitleTest {

    @Test
    void createsTitleWithIdentifierAndName() {
        TitleId id = new TitleId("title-1");

        Title title = new Title(id, "Clean Code");

        assertEquals(id, title.id());
        assertEquals("Clean Code", title.name());
    }

    @Test
    void rejectsMissingIdentifierOrInvalidName() {
        TitleId id = new TitleId("title-1");

        assertThrows(NullPointerException.class, () -> new Title(null, "Clean Code"));
        assertThrows(NullPointerException.class, () -> new Title(id, null));
        assertThrows(IllegalArgumentException.class, () -> new Title(id, "  "));
        assertThrows(IllegalArgumentException.class, () -> new Title(id, " Clean Code"));
        assertThrows(IllegalArgumentException.class, () -> new Title(id, "Clean Code "));
    }
}
