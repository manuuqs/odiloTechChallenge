package com.odilo.library.domain.model;

import java.util.Objects;

public record Title(TitleId id, String name) {

    public Title {
        Objects.requireNonNull(id, "title ID cannot be null");
        Objects.requireNonNull(name, "title name cannot be null");
        if (name.isBlank() || !name.equals(name.strip())) {
            throw new IllegalArgumentException("title name cannot be blank or have surrounding whitespace");
        }
    }
}
