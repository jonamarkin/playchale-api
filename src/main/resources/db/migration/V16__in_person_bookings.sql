-- In-person bookings: a venue manager taking a booking at the gate or on the phone, with who it's
-- for, what it costs and whether it's been paid. Counted in the venue's takings, unlike blocks
-- (maintenance, private holds), which stay free of money.
ALTER TABLE bookings DROP CONSTRAINT bookings_kind_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_kind_check CHECK (kind IN ('game', 'block', 'in-person'));

ALTER TABLE bookings
    ADD COLUMN customer_name  text,
    -- E.164, only ever shown to the venue's owner.
    ADD COLUMN customer_phone text,
    -- How an in-person booking was paid; null while it's still owed.
    ADD COLUMN paid_via       text CHECK (paid_via IN ('cash', 'momo'));

ALTER TABLE bookings ADD CONSTRAINT bookings_customer CHECK ((kind = 'in-person') = (customer_name IS NOT NULL));
