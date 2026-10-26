package com.miaa.cas.treasury;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miaa.cas.treasury.statements.Camt053Parser;
import com.miaa.cas.treasury.statements.CsvStatementParser;
import com.miaa.cas.treasury.statements.Mt940Parser;
import com.miaa.cas.treasury.statements.ParsedStatement;
import com.miaa.cas.treasury.statements.StatementFormatException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class StatementParsersTest {

    @Test
    void csvWithSignedAmountsOrDebitCreditColumns() {
        ParsedStatement a = CsvStatementParser.parse(bytes("""
                date,description,reference,amount,balance
                2026-04-02,"Deposit, branch 12",DEP1,1000.50,1100.50
                2026-04-03,Check 1001,1001,-200.00,900.50
                """), new BigDecimal("100.00"), new BigDecimal("900.50"));
        assertThat(a.lines()).hasSize(2);
        assertThat(a.lines().get(0).description()).isEqualTo("Deposit, branch 12");
        assertThat(a.lines().get(1).amount()).isEqualByComparingTo("-200.00");
        assertThat(a.periodStart()).isEqualTo(LocalDate.of(2026, 4, 2));

        ParsedStatement b = CsvStatementParser.parse(bytes("""
                Date,Description,Reference,Debit,Credit
                2026-04-05,Bank charge,,25.00,
                2026-04-06,Interest,,,3.10
                """), BigDecimal.ZERO, new BigDecimal("-21.90"));
        assertThat(b.lines().get(0).amount()).isEqualByComparingTo("-25.00");
        assertThat(b.lines().get(1).amount()).isEqualByComparingTo("3.10");
    }

    @Test
    void csvErrorsNameTheRow() {
        assertThatThrownBy(() -> CsvStatementParser.parse(bytes("date,description,amount,balance\n2026-04-02,x,10.00,11.00\n"),
                BigDecimal.ZERO, BigDecimal.TEN)).isInstanceOf(StatementFormatException.class).hasMessageContaining("Row 2");
        assertThatThrownBy(() -> CsvStatementParser.parse(bytes("date,description,amount\n02/04/2026,x,10.00\n"),
                BigDecimal.ZERO, BigDecimal.TEN)).hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> CsvStatementParser.parse(bytes("date,description,amount\n2026-04-02,x,\"1,000.00\"\n"),
                BigDecimal.ZERO, BigDecimal.TEN)).hasMessageContaining("thousands");
        assertThatThrownBy(() -> CsvStatementParser.parse(bytes("date,description,amount\n2026-04-02,x,1.00\n"), null, null))
                .hasMessageContaining("opening and closing");
    }

    @Test
    void mt940EntriesBalancesAndInformation() {
        ParsedStatement s = Mt940Parser.parse(bytes("""
                :20:STMT0426
                :25:BANKPHMM/001234567788
                :28C:00004/001
                :60F:C260401PHP1000,00
                :61:2604100410C500,00NTRFNONREF//B26100001
                :86:CASH DEPOSIT DEP-2026-000001
                :61:2604250426D250,5NCHK1001//B26250002
                :86:CHECK PAID
                :61:2612311231RD10,00NMSCNONREF
                :62F:C261231PHP1259,50
                """));
        assertThat(s.accountIdentifier()).endsWith("7788");
        assertThat(s.currency()).isEqualTo("PHP");
        assertThat(s.openingBalance()).isEqualByComparingTo("1000.00");
        assertThat(s.closingBalance()).isEqualByComparingTo("1259.50");
        assertThat(s.lines()).hasSize(3);
        assertThat(s.lines().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(s.lines().get(0).description()).isEqualTo("CASH DEPOSIT DEP-2026-000001");
        assertThat(s.lines().get(1).amount()).isEqualByComparingTo("-250.50");
        assertThat(s.lines().get(1).reference()).isEqualTo("1001");
        assertThat(s.lines().get(1).bookingDate()).isEqualTo(LocalDate.of(2026, 4, 26));
        // RD = reversal of a debit: money back into the account.
        assertThat(s.lines().get(2).amount()).isEqualByComparingTo("10.00");
        assertThat(s.periodEnd()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void mt940RejectsMultipleStatementsAndMissingBalances() {
        assertThatThrownBy(() -> Mt940Parser.parse(bytes(":20:A\n:60F:C260401PHP0,00\n:62F:C260401PHP0,00\n:20:B\n")))
                .hasMessageContaining("exactly one statement");
        assertThatThrownBy(() -> Mt940Parser.parse(bytes(":20:A\n:61:2604100410C500,00NTRFNONREF\n")))
                .hasMessageContaining("balances are required");
    }

    @Test
    void camt053BookedEntriesOnly() {
        ParsedStatement s = Camt053Parser.parse(bytes("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
                 <BkToCstmrStmt><Stmt><Id>S-0426</Id>
                  <FrToDt><FrDtTm>2026-04-01T00:00:00</FrDtTm><ToDtTm>2026-04-30T23:59:59</ToDtTm></FrToDt>
                  <Acct><Id><Othr><Id>001234567788</Id></Othr></Id><Ccy>PHP</Ccy></Acct>
                  <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="PHP">0.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-04-01</Dt></Dt></Bal>
                  <Bal><Tp><CdOrPrtry><Cd>CLBD</Cd></CdOrPrtry></Tp><Amt Ccy="PHP">70.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-04-30</Dt></Dt></Bal>
                  <Ntry><Amt Ccy="PHP">100.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts><BookgDt><Dt>2026-04-10</Dt></BookgDt>
                   <ValDt><Dt>2026-04-10</Dt></ValDt><AcctSvcrRef>B1</AcctSvcrRef>
                   <NtryDtls><TxDtls><Refs><EndToEndId>DEP-1</EndToEndId></Refs><RmtInf><Ustrd>Deposit</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                  <Ntry><Amt Ccy="PHP">30.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts><Cd>BOOK</Cd></Sts><BookgDt><Dt>2026-04-20</Dt></BookgDt>
                   <AddtlNtryInf>Service charge</AddtlNtryInf></Ntry>
                  <Ntry><Amt Ccy="PHP">999.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts><Cd>PDNG</Cd></Sts><BookgDt><Dt>2026-04-29</Dt></BookgDt></Ntry>
                 </Stmt></BkToCstmrStmt></Document>
                """));
        assertThat(s.accountIdentifier()).isEqualTo("001234567788");
        assertThat(s.lines()).hasSize(2);
        assertThat(s.lines().get(0).reference()).isEqualTo("DEP-1");
        assertThat(s.lines().get(1).amount()).isEqualByComparingTo("-30.00");
        assertThat(s.lines().get(1).description()).isEqualTo("Service charge");
        assertThat(s.periodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(s.periodEnd()).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void camt053RefusesDoctypeAndExternalEntities() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Document><BkToCstmrStmt><Stmt><Id>&x;</Id></Stmt></BkToCstmrStmt></Document>
                """;
        assertThatThrownBy(() -> Camt053Parser.parse(bytes(xxe))).isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("not a readable camt.053");
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
