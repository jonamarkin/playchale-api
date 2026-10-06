-- Who works for PlayChale.
--
-- Every other "admin" in this schema is scoped to something: owner of a workspace, manager of one
-- competition, organiser of a league. None of them is a PlayChale person, and until now there was no
-- way to be one.
--
-- It is its own table rather than a column on users for one reason: a privilege should not live in a
-- row that twenty services already write to. A careless UPDATE on users can mangle a name; the same
-- carelessness on a staff flag hands someone every phone number in the country.
--
-- Nothing in the app can create the first row. There is no "make me an admin" endpoint and no
-- sign-up path to one -- the first entry is an INSERT run by hand on the server, and after that only
-- existing staff can add anyone, which is recorded. An admin account can read any player's phone
-- number, email and payout number, so the way in is deliberately not a feature.
CREATE TABLE platform_staff (
    user_id  uuid        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    -- 'support' can look things up and answer for people; 'owner' can also add and remove staff.
    role     text        NOT NULL CHECK (role IN ('support', 'owner')),
    -- Null only for the first row, which no one was there to add.
    added_by uuid        REFERENCES users (id),
    added_at timestamptz NOT NULL DEFAULT now(),
    note     text
);

COMMENT ON TABLE platform_staff IS
    'PlayChale people. Seeded by hand on the server; no endpoint can create the first row.';

-- A deleted account must not keep its keys: users deletes cascade, and an anonymised account (V11
-- keeps the row) is caught by the app when it checks.
CREATE INDEX platform_staff_role ON platform_staff (role);
