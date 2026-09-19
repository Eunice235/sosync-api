-- Drop Kiswahili, ship English only.
--
-- The columns stay. They are cheap, they are already populated, and `notifications.language`
-- remains a truthful record of what language each delivery actually went out in - which is
-- still worth having even when the answer is always the same. Restoring a second language
-- later means adding a catalogue, not another migration.
--
-- This migration exists because the enum and the data have to move together. `Language` no
-- longer has an SW constant, so any row still holding 'SW' would fail to map on read: Grace in
-- the demo data would become an account that cannot be loaded at all. Renaming the code without
-- rewriting the rows is the version of this change that breaks in production and not in a test.

UPDATE users
SET preferred_language = 'EN'
WHERE preferred_language <> 'EN';

-- Null means "use the language of whoever raised the alert", which is now always English, so
-- an explicit override carries no information.
UPDATE emergency_contacts
SET language = NULL
WHERE language IS NOT NULL;

UPDATE notifications
SET language = 'EN'
WHERE language <> 'EN';
