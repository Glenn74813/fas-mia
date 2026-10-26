-- Binds a PENDING authenticator secret to the sign-in challenge (MFA token jti) that created it, so
-- repeated enrolment requests within one challenge return the same secret while a new sign-in always
-- receives a fresh secret.
ALTER TABLE mfa_methods ADD COLUMN enrollment_challenge_id VARCHAR(64);
