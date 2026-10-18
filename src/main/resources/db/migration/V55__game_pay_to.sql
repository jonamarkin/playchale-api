-- How to pay for a game, when players pay outside the app: the mobile money number to send it to
-- (often not the host's: a treasurer, the pitch owner), the name on that account so players can check
-- it before they send, and anything else (cash at the gate, bank details). Only for a game that costs
-- something; players in the game see it. A repeating game keeps it for the games it opens.
ALTER TABLE games
    ADD COLUMN pay_to_phone text,
    ADD COLUMN pay_to_name  text CHECK (length(pay_to_name) <= 60),
    ADD COLUMN pay_to_note  text CHECK (length(pay_to_note) <= 300);

ALTER TABLE game_series
    ADD COLUMN pay_to_phone text,
    ADD COLUMN pay_to_name  text CHECK (length(pay_to_name) <= 60),
    ADD COLUMN pay_to_note  text CHECK (length(pay_to_note) <= 300);
