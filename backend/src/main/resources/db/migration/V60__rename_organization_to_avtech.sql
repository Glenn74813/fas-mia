-- The organisation is now Avtech Information Technology Solutions, Inc. Earlier migrations keep their original text
-- because Flyway checksums them; this updates what they seeded.
UPDATE organizations SET code = 'AVTECH', name = 'Avtech Information Technology Solutions, Inc.', updated_by = 'system', updated_at = now()
WHERE code = 'MIAA';

-- Only the untouched placeholder instructions; anything an administrator rewrote does not contain the old name.
UPDATE close_tasks SET instructions = replace(instructions, 'MIAA', 'AVTECH'), updated_by = 'system', updated_at = now()
WHERE instructions LIKE '%MIAA%';
