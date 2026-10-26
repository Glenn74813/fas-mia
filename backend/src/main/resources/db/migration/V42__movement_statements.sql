-- Statement engine (MS §11.8, gap 14): movement-based lines, so a cash flow statement (SCF, direct method) and a
-- statement of changes in equity (SCE) can be configured. Formats stay templates until MIAA's framework is approved (C-07).
--
-- OPENING   balance of the line's statement line codes before the fiscal year of the period;
-- CLOSING   balance at the end of the period;
-- MOVEMENT  fiscal-year-to-date change in the accounts with the line's statement line codes, restricted to the part
--           of each journal entry whose other lines carry one of counterpart_codes (empty: the whole change). Because
--           every entry balances, the change splits exactly by the counterpart lines' codes; nothing is apportioned.
ALTER TABLE report_templates DROP CONSTRAINT report_templates_statement_type_check;
ALTER TABLE report_templates ADD CONSTRAINT report_templates_statement_type_check
    CHECK (statement_type IN ('SFP', 'SFPERF', 'SCF', 'SCE'));

ALTER TABLE report_template_lines DROP CONSTRAINT report_template_lines_line_type_check;
ALTER TABLE report_template_lines ADD CONSTRAINT report_template_lines_line_type_check
    CHECK (line_type IN ('HEADER', 'LINE', 'SURPLUS', 'TOTAL', 'OPENING', 'CLOSING', 'MOVEMENT'));

-- Statement line codes of the counterpart lines a MOVEMENT line classifies; '(none)' stands for lines whose account has no code.
ALTER TABLE report_template_lines ADD COLUMN counterpart_codes VARCHAR(30)[] NOT NULL DEFAULT '{}';
