package com.miaa.cas.treasury.statements;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A bank statement as read from a file, before validation against a bank account. */
public record ParsedStatement(String accountIdentifier, String currency, String reference, LocalDate periodStart,
        LocalDate periodEnd, BigDecimal openingBalance, BigDecimal closingBalance, List<Line> lines) {

    /** Amount is signed from the account holder's view: positive = money into the account. */
    public record Line(LocalDate valueDate, LocalDate bookingDate, BigDecimal amount, String description, String reference) {
    }
}
