-- Cinemas, and the three places the existing schema assumes there is only
-- ever one of them.
--
-- Structure only. The real cinemas, the backfill and the NOT NULL on
-- screen.cinema_id are V17, for the same reason V12 was the pricing structure
-- and V13 was the reseed: a migration that creates a shape and a migration
-- that fills it fail for different reasons and should be able to fail
-- separately.

-- -------------------------------------------------------------- the cinema

CREATE TABLE cinema (
    id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name    text NOT NULL,
    city    text NOT NULL,
    state   text NOT NULL,
    address text NOT NULL,

    -- geography, not geometry. geometry(4326) measures distance in degrees,
    -- which is not a unit anyone wants and is not even a constant one: a
    -- degree of longitude is about 111km at the equator and about 98km in
    -- Delhi. geography measures metres on a sphere, so ST_Distance returns a
    -- number a human can read and no projection has to be chosen for the
    -- country. The cost is slower maths, which at this row count is nothing.
    location geography(Point, 4326) NOT NULL,

    -- Two cinemas in one city cannot share a name. Across cities they can,
    -- because every chain has an INOX in more than one place.
    CONSTRAINT cinema_unique_in_city UNIQUE (city, name)
);

-- GiST, because it is the index type that answers "the nearest five" as an
-- index scan. ORDER BY location <-> point LIMIT 5 walks the tree and stops
-- after five. A btree cannot do that, and with no index at all Postgres
-- computes the distance to every cinema and sorts the lot. At a few hundred
-- rows both finish instantly, so this index is here for the shape of the
-- query rather than for today's speed.
CREATE INDEX cinema_location_idx ON cinema USING gist (location);

-- No index on city or state. The picker reads tens of rows and a sequential
-- scan over tens of rows is faster than an index lookup, so an index here
-- would be decoration.

-- ---------------------------------------------- screens belong to a cinema

-- Nullable here, NOT NULL in V17 once there are cinemas to point at. Adding
-- it NOT NULL in one step fails against the four rows that already exist with
-- no default to give them, which is the same three-step shape as
-- screen.hall_class in V12 and seat.tier in V6.
ALTER TABLE screen ADD COLUMN cinema_id bigint REFERENCES cinema(id);

-- Every multiplex has a Screen 1. V1 declared screen.name globally UNIQUE,
-- which was correct while there was one building and becomes wrong the
-- instant a second cinema is inserted. Postgres named that constraint itself,
-- from the table and the column, because V1 wrote UNIQUE inline.
ALTER TABLE screen DROP CONSTRAINT screen_name_key;

ALTER TABLE screen ADD CONSTRAINT screen_unique_in_cinema
    UNIQUE (cinema_id, name);

-- That swap opens a gap worth naming: until V17 sets cinema_id NOT NULL, two
-- screens could both be called Screen 1 with a NULL cinema, because NULLs do
-- not conflict in a unique constraint. Nothing inserts screens except
-- migrations and the seeder, and V17 runs in the same Flyway pass, so the
-- window closes before anything can reach it.

-- --------------------------------------------------------- the room layout

-- Where the walking aisles fall, as the seat numbers they come after. {3,9}
-- is the 3 | 6 | 3 room that SeatMap.tsx currently hardcodes.
--
-- A column rather than a constant, because two cinemas are not the same room.
-- A fourteen-wide screen with aisles after 4 and 10 is a different array and
-- nothing else changes: seat is keyed on (screen_id, row_label, seat_number)
-- and nothing anywhere says twelve.
--
-- An array rather than a child table. It is read whole, written whole, never
-- joined to and never queried by element, which is the one case where an
-- array is the right answer instead of the lazy one.
--
-- The default exists only to fill the four rows that already exist, and is
-- dropped on the next line so a new screen has to state its own layout rather
-- than silently inheriting this one.
ALTER TABLE screen ADD COLUMN aisle_after int[] NOT NULL DEFAULT '{3,9}';
ALTER TABLE screen ALTER COLUMN aisle_after DROP DEFAULT;

-- A room has a handful of aisles, not forty. This catches a typo, not an
-- attack. cardinality is immutable, which is what a CHECK requires.
ALTER TABLE screen ADD CONSTRAINT screen_aisle_after_sane
    CHECK (cardinality(aisle_after) <= 4);

-- ----------------------------------------------------------- a dead column

-- seat_tier.price_delta_minor has not been read by anything since V12 moved
-- pricing to hall_class_tier_price, and it still holds 250 and 500, the old
-- GBP-era uplifts. Leaving it means the schema describes two pricing models
-- with nothing to say which one is live. Dropping a column is only safe when
-- nothing reads it, and nothing does: it appears in migration files alone.
ALTER TABLE seat_tier DROP COLUMN price_delta_minor;