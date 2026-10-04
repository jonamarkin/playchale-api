-- A referee is not an administrator.
--
-- Until now the only way to put someone on a fixture was to make them a member of the workspace,
-- and every member counted as an admin: appointing a match official handed them the league's entry
-- fees and every company's roster. A pilot cannot be run that way — eight companies means several
-- referees, none of whom should see any of that.
--
-- 'official' is a seat in the workspace that grants nothing except being assignable to a fixture.
-- The checks that let someone run a competition now name the two roles that may (owner, admin), so
-- an official is admitted to their own match sheet and refused everywhere else.
--
-- The old checks were written inline on the column, so their names were generated. They are found
-- by what they constrain rather than by a name this migration would have to guess at.

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'organisation_memberships'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%role%'
    LOOP
        EXECUTE format('ALTER TABLE organisation_memberships DROP CONSTRAINT %I', constraint_name);
    END LOOP;

    FOR constraint_name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'organisation_invitations'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%role%'
    LOOP
        EXECUTE format('ALTER TABLE organisation_invitations DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE organisation_memberships ADD CONSTRAINT organisation_memberships_role
    CHECK (role IN ('owner', 'admin', 'official'));

ALTER TABLE organisation_invitations ADD CONSTRAINT organisation_invitations_role
    CHECK (role IN ('admin', 'official'));
