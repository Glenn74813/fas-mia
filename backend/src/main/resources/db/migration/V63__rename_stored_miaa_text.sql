-- Display text saved before the organisation was renamed (V60). Audit events and acknowledged integration transmissions
-- keep what they recorded: they are history, and each transmission is checked against its stored hash.
UPDATE bank_accounts SET account_name = replace(account_name, 'MIAA', 'AVTECH') WHERE account_name LIKE '%MIAA%';
UPDATE notifications SET title = replace(title, 'MIAA', 'AVTECH') WHERE title LIKE '%MIAA%';
-- Test accounts on a local domain that cannot receive mail.
UPDATE users SET email = regexp_replace(email, 'miaa\.local$', 'avtech.local') WHERE email LIKE '%miaa.local';
