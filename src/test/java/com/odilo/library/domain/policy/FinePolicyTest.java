package com.odilo.library.domain.policy;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.odilo.library.domain.model.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FinePolicyTest {

    @Test
    void requiresBothFineAmounts() {
        Money amount = new Money(new BigDecimal("1.00"));

        assertThrows(NullPointerException.class, () -> new FinePolicy(null, amount));
        assertThrows(NullPointerException.class, () -> new FinePolicy(amount, null));
    }
}
