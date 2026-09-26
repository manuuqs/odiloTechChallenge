package com.odilo.library.domain.model;

import java.util.Objects;

public record MemberId(String value) {

    public MemberId {
        Objects.requireNonNull(value, "member ID cannot be null");
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("member ID cannot be blank or have surrounding whitespace");
        }
    }
}
