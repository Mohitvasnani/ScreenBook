-- Two countries, so two currencies and two clocks.
--
-- Nothing in this project converts between currencies, and nothing ever
-- should. A Jaipur screening is priced in paise and charged in paise, a
-- Liverpool one in pence. The two never meet inside an arithmetic
-- expression, so there is no rate to fetch, no rate to store, and no
-- question about which day's rate a six month old booking was taken at.
-- The only place both appear is a customer's bookings list, where each row
-- carries its own currency, which is how every real platform behaves.
--
-- The pricing rules need no second set of numbers either. They are
-- percentages, and a percentage does not care what it is a percentage of.
--
-- cinema is still empty at this point, so these columns can be NOT NULL
-- immediately. That is the one benefit of V16 being structure with no data:
-- no backfill, no three-step dance.

-- ------------------------------------------------------------- currencies

-- A lookup table rather than a CHECK, for the same reason hall_class and
-- seat_tier are lookup tables. It also closes a real hole: seat_availability
-- inner joins the price table, so a cinema in a currency with no price rows
-- would make its seats vanish from the seat map rather than fail loudly.
-- With both sides pointing here, that cannot be typed into existence.
CREATE TABLE currency (
    code         text PRIMARY KEY CHECK (char_length(code) = 3),
    display_name text NOT NULL
);

INSERT INTO currency (code, display_name) VALUES
    ('INR', 'Indian rupee'),
    ('GBP', 'Pound sterling');

-- No exponent column. Every amount in this schema is integer minor units
-- with 100 to the major unit, which holds for both of these. A currency
-- without a decimal part, yen for instance, would need one here and a
-- formatter that reads it. Noting the limit rather than building for it.

-- --------------------------------------------------- currency and clock

ALTER TABLE cinema ADD COLUMN currency text NOT NULL REFERENCES currency(code);

-- An IANA zone name, Asia/Kolkata or Europe/London. Nothing in Postgres can
-- validate that, and a CHECK against a hardcoded list would be a second
-- place to edit when a country is added, so this is enforced by ZoneId.of
-- blowing up at read time instead.
--
-- It lives on the cinema because it is a fact about the building. The
-- evening and weekend pricing rules currently read one global config value,
-- Asia/Kolkata, which would call a 19:00 Liverpool screening an off-peak
-- afternoon one. A screening's local evening is decided by where the
-- screening is.
ALTER TABLE cinema ADD COLUMN timezone text NOT NULL;

-- --------------------------------------------- prices become per currency

-- V12 keyed this on hall class and tier, with one global ladder. That was
-- defensible while every cinema was in one country and is nonsense now: a
-- Liverpool seat at 20000 paise is not a price, it is a unit error.
ALTER TABLE hall_class_tier_price DROP CONSTRAINT hall_class_tier_price_pkey;

ALTER TABLE hall_class_tier_price
    ADD COLUMN currency text REFERENCES currency(code);

-- Everything that exists today is Indian, which V12 already established when
-- it set every showtime to INR.
UPDATE hall_class_tier_price SET currency = 'INR';

ALTER TABLE hall_class_tier_price ALTER COLUMN currency SET NOT NULL;

ALTER TABLE hall_class_tier_price
    ADD PRIMARY KEY (currency, hall_class, tier);

-- No GBP price rows here, deliberately.
--
-- seat_availability joins this table on hall class and tier alone, so a
-- second currency makes that join match twice and every seat appears in the
-- view twice. The first run of this migration did exactly that and took out
-- nineteen tests, because HoldService asks for four seats, gets eight rows
-- and correctly refuses to believe they belong to the showtime.
--
-- The join only becomes unambiguous once it can filter on the cinema's
-- currency, and no cinema is attached to a screen until V18. So the GBP
-- ladder is inserted there, on the other side of the view rebuild, where it
-- is safe.