-- PlayChale in any country. Where something is played sets its money and its clock: each game and
-- league now carries its country, currency and timezone, as venues already do. Games already have a
-- currency. Everything so far is in Ghana.
ALTER TABLE games ADD COLUMN country char(2) NOT NULL DEFAULT 'GH';
ALTER TABLE games ADD COLUMN timezone text NOT NULL DEFAULT 'Africa/Accra';

ALTER TABLE competitions ADD COLUMN country char(2) NOT NULL DEFAULT 'GH';
ALTER TABLE competitions ADD COLUMN currency char(3) NOT NULL DEFAULT 'GHS';
ALTER TABLE competitions ADD COLUMN timezone text NOT NULL DEFAULT 'Africa/Accra';
