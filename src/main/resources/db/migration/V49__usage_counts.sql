-- How often each page of the web app is opened and each film on it played, by day: a number and
-- nothing about who. No user, no address, no cookie. A page is its route ("/games/:id"), never
-- the address someone typed; where a visit came from is only another site's name (whatsapp.com),
-- a tag put on a link (?from=hillview), or "direct". Only the first page of a visit says where it
-- came from, so the pages opened after it inside the app have none ('').
CREATE TABLE usage_counts (
    day    date   NOT NULL,
    kind   text   NOT NULL CHECK (kind IN ('page', 'film-start', 'film-end')),
    name   text   NOT NULL CHECK (length(name) BETWEEN 1 AND 80),
    source text   NOT NULL DEFAULT '' CHECK (length(source) <= 60),
    n      bigint NOT NULL CHECK (n > 0),
    PRIMARY KEY (day, kind, name, source)
);
