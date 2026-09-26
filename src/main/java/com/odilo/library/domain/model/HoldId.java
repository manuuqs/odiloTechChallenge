package com.odilo.library.domain.model;

import java.util.Objects;

public record HoldId(String value) {

    public HoldId {
        Objects.requireNonNull(value, "hold ID cannot be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("hold ID cannot be blank or have surrounding whitespace");
        }
    }
}
