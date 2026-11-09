-- Pricing: a hand-set price per hall class and tier, then rules that move it.
--
-- Two halves, and the split is the point. The price table is data a human
-- chooses and nothing computes. The rules are a function of live state, and
-- they live in Java so they can be unit tested without a database. This
-- migration owns the first half and the storage for the second.
--
-- Order matters in the back half of this file. seat_availability reads
-- showtime.price_minor, and Postgres refuses to drop a column a view depends
-- on, so the view goes first and is rebuilt afterwards.

-- ------------------------------------------------------------- hall classes

-- A lookup table rather than a CHECK, for the same reason seat_tier is one: it
-- carries the display name, so adding a class is a row rather than a row plus
-- a code change.
CREATE TABLE hall_class (
    code         text PRIMARY KEY,
    display_name text NOT NULL,
    sort_order   int  NOT NULL UNIQUE
);

INSERT INTO hall_class (code, display_name, sort_order) VALUES
    ('STANDARD', 'Standard', 1),
    ('LUXURY',   'Luxury',   2);

-- Added nullable, backfilled, then constrained. Adding it NOT NULL in one step
-- fails against a table that already has rows and no default. Same three-step
-- shape as seat.tier in V6.
ALTER TABLE screen ADD COLUMN hall_class text REFERENCES hall_class(code);

UPDATE screen
   SET hall_class = CASE WHEN name = 'Screen 4' THEN 'LUXURY' ELSE 'STANDARD' END;

ALTER TABLE screen ALTER COLUMN hall_class SET NOT NULL;

-- --------------------------------------------------------- the price table

-- What a seat costs before any rule touches it, in minor units. For INR that
-- is paise, so every figure here ends in two zeros; nobody prices cinema
-- seats in paise, but the column stays currency-agnostic and switching
-- currency is a data change rather than a code change.
--
-- Deliberately not base-plus-uplift. One global uplift means a fixed 150-rupee
-- gap is a huge ladder in a 200-rupee hall and nearly flat in a 400-rupee one.
-- Percentage uplifts fix the ratio but produce prices like 437.50. Listing the
-- prices is what real cinemas publish, and every number here is one a human
-- picked and would be willing to print.
--
-- Every figure is a multiple of 50. That is not cosmetic: the rules are whole
-- multiples of 10%, so every adjustment lands on a whole rupee and the
-- rounding rule in the pricing function never actually fires.
CREATE TABLE hall_class_tier_price (
    hall_class  text NOT NULL REFERENCES hall_class(code),
    tier        text NOT NULL REFERENCES seat_tier(code),
    -- Strictly positive. A seat that costs nothing is a comp, not a price, and
    -- comps are a different feature with different rules.
    price_minor int  NOT NULL CHECK (price_minor > 0),

    PRIMARY KEY (hall_class, tier)
);

-- Standard 200/350/500, Luxury 300/550/800. Both ladders are about 2.5 to 2.7
-- times bottom to top, which is what Raj Mandir's Ruby-to-Diamond spread is.
INSERT INTO hall_class_tier_price (hall_class, tier, price_minor) VALUES
    ('STANDARD', 'NORMAL',    20000),
    ('STANDARD', 'PREMIUM',   35000),
    ('STANDARD', 'EXECUTIVE', 50000),
    ('LUXURY',   'NORMAL',    30000),
    ('LUXURY',   'PREMIUM',   55000),
    ('LUXURY',   'EXECUTIVE', 80000);

-- ------------------------------------------------------------------ currency

-- Working currency is now INR. Existing bookings keep GBP: they record what
-- was actually charged, and rewriting a receipt's currency would be
-- falsifying history, which is the same rule that put prices on the booking
-- in the first place.
ALTER TABLE showtime ALTER COLUMN currency SET DEFAULT 'INR';
UPDATE showtime SET currency = 'INR';

-- ------------------------------------------------------------- release date

-- For the weeks-since-release rule, which ships later. Nullable on purpose,
-- and nullable is the honest choice: a backfilled fake date would be stale the
-- moment this migration ran and could never be told apart from a real one. The
-- rule reads null as "release date unknown, no adjustment".
ALTER TABLE film ADD COLUMN released_on date;

-- ---------------------------------------------------- the frozen hold price

-- The price quoted when the hold was taken, so confirm charges that number
-- rather than recomputing it. Once occupancy is an input, recomputing gives a
-- different answer: five seats booked by somebody else can cross an occupancy
-- band while the customer is typing their email.
--
-- base_price_minor sits on the parent because a hold covers exactly one tier.
-- That is not a rule anyone wrote down, it falls out of two unrelated ones:
-- seats on a hold must share a row, and tier is assigned by row band. The
-- per-seat price below is stored per seat anyway, so if either of those rules
-- ever changes this schema still holds the truth.
ALTER TABLE seat_hold ADD COLUMN base_price_minor int;
ALTER TABLE hold_seat ADD COLUMN price_minor      int;

-- Existing holds predate dynamic pricing, so they are backfilled with the
-- unadjusted tier price, which is literally what they were quoted. They cannot
-- simply be deleted: booking.hold_id references them.
UPDATE seat_hold h
   SET base_price_minor = p.price_minor
  FROM hold_seat hs
  JOIN seat   s  ON s.id = hs.seat_id
  JOIN screen sc ON sc.id = s.screen_id
  JOIN hall_class_tier_price p
    ON p.hall_class = sc.hall_class AND p.tier = s.tier
 WHERE hs.hold_id = h.id;

UPDATE hold_seat hs
   SET price_minor = p.price_minor
  FROM seat   s
  JOIN screen sc ON sc.id = s.screen_id
  JOIN hall_class_tier_price p
    ON p.hall_class = sc.hall_class AND p.tier = s.tier
 WHERE s.id = hs.seat_id;

ALTER TABLE seat_hold ALTER COLUMN base_price_minor SET NOT NULL;
ALTER TABLE hold_seat ALTER COLUMN price_minor      SET NOT NULL;

-- ------------------------------------------------------ the rule breakdown

-- Why the price is what it is, as the customer was shown it.
--
-- A child table rather than a jsonb column: three or four rows per hold, a
-- shape the database enforces, and a table you can aggregate over to ask how
-- often the peak rule fires. Same parent-child shape as hold_seat.
--
-- label is a snapshot, not a lookup. The text depends on the band, not just
-- the rule, and more importantly it is the explanation that was on screen at
-- the time. Rewording a band later must not retroactively change why an old
-- booking cost what it did. Same argument as copying the price.
--
-- Both amounts are signed. Discounts are negative, and so is the cap line
-- below.
--
-- No index. Every read is by hold_id, which the primary key already leads on.
CREATE TABLE hold_price_rule (
    hold_id          uuid NOT NULL REFERENCES seat_hold(id) ON DELETE CASCADE,
    code             text NOT NULL,
    label            text NOT NULL,
    basis_points     int  NOT NULL,
    adjustment_minor int  NOT NULL,
    sort_order       int  NOT NULL,

    PRIMARY KEY (hold_id, code)
);

-- --------------------------------------------- rebuild seat_availability

-- DROP rather than CREATE OR REPLACE. Replace can only append columns, and
-- this changes what price_minor means and renames it, so the old definition
-- has to go. Dropping it first is also what lets showtime.price_minor go:
-- Postgres will not drop a column a view reads.
DROP VIEW seat_availability;

-- The price now lives in hall_class_tier_price, so a price on the screening
-- as well would be a second source of truth.
ALTER TABLE showtime DROP COLUMN price_minor;

-- base_price_minor, not price_minor. The name says what it is: the figure
-- before the rules, which Java adjusts. If this column were still called
-- price_minor, every caller reading it would believe it was the final price.
CREATE VIEW seat_availability AS
SELECT st.id         AS showtime_id,
       s.id          AS seat_id,
       s.row_label   AS row_label,
       s.seat_number AS seat_number,
       CASE
         WHEN EXISTS (SELECT 1
                      FROM booking_seat bs
                      WHERE bs.showtime_id = st.id
                        AND bs.seat_id     = s.id)
           THEN 'BOOKED'
         WHEN EXISTS (SELECT 1
                      FROM hold_seat hs
                      JOIN seat_hold h ON h.id = hs.hold_id
                      WHERE hs.showtime_id = st.id
                        AND hs.seat_id     = s.id
                        AND h.booked_at    IS NULL
                        AND h.released_at  IS NULL
                        AND h.expires_at   > now())
           THEN 'HELD'
         ELSE 'AVAILABLE'
       END           AS status,
       s.tier          AS tier,
       t.display_name  AS tier_name,
       sc.hall_class   AS hall_class,
       p.price_minor   AS base_price_minor,
       st.currency     AS currency
FROM showtime st
JOIN screen    sc ON sc.id        = st.screen_id
JOIN seat      s  ON s.screen_id  = st.screen_id
JOIN seat_tier t  ON t.code       = s.tier
JOIN hall_class_tier_price p
                  ON p.hall_class = sc.hall_class
                 AND p.tier       = s.tier;

-- ------------------------------------------------------------- occupancy

-- How full a screening is, counted from the same rows the seat map renders.
--
-- This exists because the old arithmetic in ShowtimeQuery was wrong: it
-- subtracted count(*) from booking and seat_hold, which counted bookings and
-- holds rather than seats, and so over-reported availability for any
-- multi-seat booking after V8. Counting from the view cannot drift from the
-- seat map, because it is the seat map.
--
-- FILTER is the SQL-standard way to count a subset of rows inside an
-- aggregate. count(*) FILTER (WHERE ...) beats
-- sum(CASE WHEN ... THEN 1 ELSE 0 END) for readability and does the same work.
--
-- A seat cannot be double counted. A hold stops being live the moment it
-- converts, because booked_at is set, and booking_seat has
-- UNIQUE (showtime_id, seat_id), so BOOKED and HELD are exclusive by
-- construction rather than by this query being careful.
--
-- showtime_id is the grouping key, so WHERE showtime_id = 1 against this view
-- narrows before the aggregate rather than grouping the whole house first.
-- How full a screening is. One definition, used by the showtimes list and by
-- the pricing rules, so the number that sets the price cannot disagree with
-- the number on the list.
--
-- Correlated subqueries against showtime rather than a GROUP BY over
-- seat_availability. Both are correct; only this one is cheap. Postgres cannot
-- push a join condition through a GROUP BY, so the grouped version aggregates
-- every showtime in the table even when the caller wants twelve. This version
-- narrows to one showtime first and then does three index lookups.
--
-- A seat cannot be counted twice. A hold stops being live the moment it
-- converts, because booked_at is set, and booking_seat carries
-- UNIQUE (showtime_id, seat_id). So booked and held are exclusive by
-- construction rather than by this query being careful.
--
-- These count booking_seat and hold_seat, the child tables. Counting the
-- parents is the bug this replaces: one booking can cover six seats.
CREATE VIEW showtime_occupancy AS
SELECT st.id AS showtime_id,

       (SELECT count(*) FROM seat s
         WHERE s.screen_id = st.screen_id)              AS seats_total,

       (SELECT count(*) FROM booking_seat bs
         WHERE bs.showtime_id = st.id)
       + (SELECT count(*) FROM hold_seat hs
           JOIN seat_hold h ON h.id = hs.hold_id
          WHERE hs.showtime_id = st.id
            AND h.booked_at    IS NULL
            AND h.released_at  IS NULL
            AND h.expires_at   > now())                 AS seats_taken

FROM showtime st;