package com.odilo.library.domain.model;

import java.util.Objects;

public record LoanId(String value) {

    public LoanId {
        Objects.requireNonNull(value, "loan ID cannot be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("loan ID cannot be blank or have surrounding whitespace");
        }
    }
}
