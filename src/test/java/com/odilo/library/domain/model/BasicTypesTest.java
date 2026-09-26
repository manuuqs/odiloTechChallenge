package com.odilo.library.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BasicTypesTest {

    @ParameterizedTest(name = "{0} rejects invalid identifiers")
    @MethodSource("identifiers")
    void identifiersRejectNullBlankAndSurroundingWhitespace(String name, Function<String, ?> create) {
        assertThrows(NullPointerException.class, () -> create.apply(null));
        assertThrows(IllegalArgumentException.class, () -> create.apply("  "));
        assertThrows(IllegalArgumentException.class, () -> create.apply(" id-1"));
        assertThrows(IllegalArgumentException.class, () -> create.apply("id-1 "));
    }

    static Stream<Arguments> identifiers() {
        return Stream.of(
                Arguments.of("title", (Function<String, ?>) TitleId::new),
                Arguments.of("copy", (Function<String, ?>) CopyId::new),
                Arguments.of("member", (Function<String, ?>) MemberId::new));
    }

    @Test
    void moneyNormalizesAmountsToCents() {
        assertEquals(new BigDecimal("123.40"), new Money(new BigDecimal("123.4")).amount());
        assertEquals(new BigDecimal("7.00"), new Money(new BigDecimal("7.000")).amount());
    }

    @Test
    void moneyRejectsNegativeAmountsAndFractionalCents() {
        assertThrows(NullPointerException.class, () -> new Money(null));
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("0.001")));
    }
}
