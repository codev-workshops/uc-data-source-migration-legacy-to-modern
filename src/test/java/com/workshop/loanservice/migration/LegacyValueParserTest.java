package com.workshop.loanservice.migration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class LegacyValueParserTest {

    @Test
    void blankValuesBecomeNull() {
        assertNull(LegacyValueParser.text("  "));
        assertNull(LegacyValueParser.date(null));
        assertNull(LegacyValueParser.amount(""));
        assertNull(LegacyValueParser.integer(" "));
        assertNull(LegacyValueParser.loanStatus(null));
    }

    @Test
    void parsesLegacyFormats() {
        assertEquals(LocalDate.of(2019, 3, 15), LegacyValueParser.date("03/15/2019"));
        assertEquals(LocalDate.of(2019, 3, 15).atStartOfDay(), LegacyValueParser.timestamp("03/15/2019"));
        assertEquals(new BigDecimal("1500000"), LegacyValueParser.amount("1,500,000"));
        assertEquals(new BigDecimal("271432.56"), LegacyValueParser.amount(" 271,432.56 "));
        assertEquals(360, LegacyValueParser.integer("360"));
        assertEquals(Boolean.TRUE, LegacyValueParser.active("ACT"));
        assertEquals(Boolean.FALSE, LegacyValueParser.active("INA"));
    }

    @Test
    void expandsCodesToUppercaseTokens() {
        assertEquals("FORBEARANCE", LegacyValueParser.loanStatus("frb"));
        assertEquals("INACTIVE", LegacyValueParser.borrowerStatus("INA"));
        assertEquals("CONDOMINIUM", LegacyValueParser.propertyType("CND"));
        assertEquals("PREPAYMENT", LegacyValueParser.paymentType("PRE"));
        assertEquals("NSF", LegacyValueParser.paymentStatus("NSF"));
        assertEquals("XYZ", LegacyValueParser.paymentStatus("xyz"));
    }

    @Test
    void malformedValuesThrow() {
        assertThrows(IllegalArgumentException.class, () -> LegacyValueParser.date("2019-03-15"));
        assertThrows(IllegalArgumentException.class, () -> LegacyValueParser.amount("1,2x"));
        assertThrows(IllegalArgumentException.class, () -> LegacyValueParser.integer("36O"));
    }
}
