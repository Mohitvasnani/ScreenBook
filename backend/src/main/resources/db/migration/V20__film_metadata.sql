-- Film metadata from TMDB.
--
-- Three nullable columns and a constraint. The service never calls TMDB: the
-- data is ingested once offline by tools/tmdb_ingest.py and committed as V21,
-- so the only third party the running API depends on stays Brevo.
--
-- poster_path is the CDN path TMDB returns, like /abc123.jpg. Not a URL and
-- not an image. The browser builds the URL and fetches the file from TMDB's
-- CDN, so nothing is re-hosted and no image bytes ever pass through this
-- service or this repository.
--
-- released_on is not added here. V12 added it at line 88 and left it NULL in
-- every row. V21 is what finally fills it, which is what unblocks the fifth
-- pricing rule.

ALTER TABLE film
    ADD COLUMN tmdb_id     integer,
    ADD COLUMN overview    text,
    ADD COLUMN poster_path text;

-- Nullable and unique at the same time, which is not a contradiction here.
-- A film does not have to come from TMDB, so the column has to accept NULL.
-- The same film must not be ingested twice, so it has to be unique. In
-- Postgres a unique constraint permits any number of NULLs, so the constraint
-- only makes a claim about rows that assert a tmdb id, and says nothing about
-- the rows that do not. That is exactly the rule wanted.
ALTER TABLE film
    ADD CONSTRAINT film_unique_tmdb_id UNIQUE (tmdb_id);