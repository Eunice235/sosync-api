-- Language preference.
--
-- Stored per account rather than per deployment, because the people involved in one emergency
-- do not necessarily share a language: a reporter may use English while the sister who receives
-- the alert reads Kiswahili. The alert each person receives is rendered in their own language.
--
-- Trusted contacts get an optional override. A contact who is not a registered user has no
-- profile to read a preference from, and guessing wrong on the one channel that reaches a basic
-- phone is worse than asking once when the contact is added. Null falls back to the reporter's
-- language, which is the best available signal.

ALTER TABLE users
    ADD COLUMN preferred_language VARCHAR(8) NOT NULL DEFAULT 'EN';

-- Null means "use the language of whoever raised the alert".
ALTER TABLE emergency_contacts
    ADD COLUMN language VARCHAR(8);

-- Which language each delivery actually went out in. Recorded rather than inferred, so the
-- delivery log answers what the reader saw and not merely what we would send them today.
ALTER TABLE notifications
    ADD COLUMN language VARCHAR(8) NOT NULL DEFAULT 'EN';
