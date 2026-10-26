package com.miaa.cas.migration;

import com.miaa.cas.generalledger.AccountMappingService;
import com.miaa.cas.migration.MigrationService.Kind;
import com.miaa.cas.migration.PartyItemRules.Spec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** The four per-party migration kinds differ only in their columns, control account and target table. */
@Configuration
class MigrationRulesConfig {

    @Bean
    KindRules arOpenItemRules(AccountMappingService mappings, JdbcTemplate jdbc) {
        return new PartyItemRules(new Spec(Kind.AR_OPEN_ITEMS, true, "document_number", 30, "document_date", true, "outstanding_amount",
                1000, "AR_TRADE_RECEIVABLE", true), mappings, jdbc);
    }

    @Bean
    KindRules apOpenItemRules(AccountMappingService mappings, JdbcTemplate jdbc) {
        return new PartyItemRules(new Spec(Kind.AP_OPEN_ITEMS, false, "invoice_number", 60, "invoice_date", true, "outstanding_amount",
                1000, "AP_TRADE_PAYABLE", false), mappings, jdbc);
    }

    @Bean
    KindRules arUnappliedRules(AccountMappingService mappings, JdbcTemplate jdbc) {
        return new PartyItemRules(new Spec(Kind.AR_UNAPPLIED, true, "receipt_number", 30, "receipt_date", false, "unapplied_amount",
                500, "UNAPPLIED_COLLECTIONS", false), mappings, jdbc);
    }

    @Bean
    KindRules apAdvanceRules(AccountMappingService mappings, JdbcTemplate jdbc) {
        return new PartyItemRules(new Spec(Kind.AP_ADVANCES, false, "advance_reference", 60, "advance_date", false, "outstanding_amount",
                1000, "SUPPLIER_ADVANCES", true), mappings, jdbc);
    }
}
