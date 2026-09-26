package com.odilo.library.domain.model;

import java.util.Objects;

public record TitleId(String value) {

    public TitleId {
        Objects.requireNonNull(value, "title ID cannot be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("title ID cannot be blank or have surrounding whitespace");
        }
    }
}
