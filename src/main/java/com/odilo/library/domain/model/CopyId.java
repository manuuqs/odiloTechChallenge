package com.odilo.library.domain.model;

import java.util.Objects;

public record CopyId(String value) {

    public CopyId {
        Objects.requireNonNull(value, "copy ID cannot be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("copy ID cannot be blank or have surrounding whitespace");
        }
    }
}
