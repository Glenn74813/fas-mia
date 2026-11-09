-- General Payroll import (TOR §4.6.2, Annex D: interfaces for approved payroll summaries; C-37). The HRIS exports a
-- General Payroll, one row per employee and one column per earning or deduction; each column is booked through the
-- account set here. The client maintains the mapping on screen; nothing about it is coded.
CREATE TABLE payroll_column_mappings (
    -- The column heading in lower case with single spaces, as matched against the file's header row.
    column_key  VARCHAR(100) PRIMARY KEY,
    label       VARCHAR(100) NOT NULL,
    -- DEBIT and CREDIT book the column's total (per employee on an account that keeps an employee subsidiary ledger);
    -- EMPLOYEE_NUMBER and EMPLOYEE_NAME identify the row; IGNORE is a column with nothing to book (a subtotal, a note).
    treatment   VARCHAR(16)  NOT NULL CHECK (treatment IN ('DEBIT', 'CREDIT', 'IGNORE', 'EMPLOYEE_NUMBER', 'EMPLOYEE_NAME')),
    account_id  UUID REFERENCES chart_of_accounts (id),
    CONSTRAINT ck_payroll_mapping_account CHECK (treatment IN ('DEBIT', 'CREDIT') OR account_id IS NULL),
    version     BIGINT       NOT NULL DEFAULT 0,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(64)  NOT NULL
);

-- One row per imported file, so the same General Payroll cannot be booked twice.
CREATE TABLE payroll_imports (
    id                 UUID PRIMARY KEY,
    reference          VARCHAR(60)  NOT NULL,
    file_name          VARCHAR(255) NOT NULL,
    file_sha256        CHAR(64)     NOT NULL UNIQUE,
    employees          INTEGER      NOT NULL CHECK (employees > 0),
    total_amount       NUMERIC(19,2) NOT NULL,
    journal_voucher_id UUID         NOT NULL REFERENCES journal_vouchers (id),
    imported_by_id     UUID         NOT NULL REFERENCES users (id),
    imported_by        VARCHAR(64)  NOT NULL,
    imported_at        TIMESTAMPTZ  NOT NULL
);

-- Proposed mapping for the government General Payroll layout, onto COA's Revised Chart of Accounts for Government
-- Corporations. An account that is not in the chart is left unset and the import says which columns still need one.
INSERT INTO payroll_column_mappings (column_key, label, treatment, account_id, updated_at, updated_by)
SELECT m.column_key, m.label, m.treatment, a.id, now(), 'system'
FROM (VALUES
    ('employee no.', 'Employee no.', 'EMPLOYEE_NUMBER', NULL),
    ('name', 'Name', 'EMPLOYEE_NAME', NULL),
    ('position', 'Position', 'IGNORE', NULL),
    ('sg-step', 'SG-Step', 'IGNORE', NULL),
    ('monthly salary', 'Monthly salary', 'IGNORE', NULL),
    ('lwop days', 'LWOP days', 'IGNORE', NULL),
    ('lwop amount', 'LWOP amount', 'IGNORE', NULL),
    ('basic salary', 'Basic salary', 'DEBIT', '50101010'),
    ('pera', 'PERA', 'DEBIT', '50102010'),
    ('representation allowance', 'Representation allowance', 'DEBIT', '50102020'),
    ('transportation allowance', 'Transportation allowance', 'DEBIT', '50102030'),
    ('gross', 'Gross', 'IGNORE', NULL),
    ('gsis life and retirement premium', 'GSIS life and retirement premium', 'CREDIT', '20201020'),
    ('philhealth premium', 'PhilHealth premium', 'CREDIT', '20201040'),
    ('pag-ibig contribution', 'Pag-IBIG contribution', 'CREDIT', '20201030'),
    ('withholding tax', 'Withholding tax', 'CREDIT', '20201010'),
    ('gsis consolidated loan', 'GSIS consolidated loan', 'CREDIT', '20201020'),
    ('gsis multi-purpose loan', 'GSIS multi-purpose loan', 'CREDIT', '20201020'),
    ('pag-ibig multi-purpose loan', 'Pag-IBIG multi-purpose loan', 'CREDIT', '20201030'),
    ('cooperative loan', 'Cooperative loan', 'CREDIT', '29999990'),
    ('union dues', 'Union dues', 'CREDIT', '29999990'),
    ('mortuary contribution', 'Mortuary contribution', 'CREDIT', '29999990'),
    ('lbp salary loan', 'LBP salary loan', 'CREDIT', '29999990'),
    ('total deductions', 'Total deductions', 'IGNORE', NULL),
    ('net pay', 'Net pay', 'CREDIT', '20101020'),
    ('1st half', '1st half', 'IGNORE', NULL),
    ('2nd half', '2nd half', 'IGNORE', NULL),
    ('remarks', 'Remarks', 'IGNORE', NULL)
) AS m (column_key, label, treatment, code)
LEFT JOIN chart_of_accounts a ON a.code = m.code AND a.postable;
