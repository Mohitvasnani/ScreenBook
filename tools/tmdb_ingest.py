#!/usr/bin/env python3
"""
One-off TMDB ingestion for ScreenBook.

Run once, by hand. The output path is an argument, not a shell redirect:

    python tools/tmdb_ingest.py backend/src/main/resources/db/migration/V21__real_films.sql

The token comes from TMDB_READ_TOKEN, either a real environment variable or a
line in .env at the repo root. Then read the output before committing it. It is
generated, but it is also a migration, and a migration nobody read is a
migration nobody can defend.

The script owns the output file rather than printing to stdout, because a shell
redirect on Windows PowerShell 5.1 writes UTF-16LE, and TMDB titles and
overviews are full of non-ASCII. Encoding is stated here once instead of
depending on which terminal happened to run it.

Why this is a script and not a Spring component: the deployed API must not
depend on TMDB being up. It has exactly one runtime third party, Brevo, and
that is a property worth keeping. So the network call happens here, on a
laptop, once, and what reaches production is SQL.

Posters are not downloaded. The script records poster_path, the browser builds
the CDN URL and fetches the file. No image is re-hosted, which is what TMDB's
terms ask for and also means this repository stays text.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

API = "https://api.themoviedb.org/3"

# Both the regions the cinema rows cover. A film showing in Liverpool and a
# film showing in Jaipur are different lists, and the demo has cinemas in both.
REGIONS = ("GB", "IN")
PAGES = (1, 2)

# The invented films from V3, in the order V3 inserted them, which is the order
# their ids run in. These rows are repointed at real films rather than deleted:
# V4 and V13 both inserted showtimes against them by title and both have
# already been applied.
PLACEHOLDERS = ("The Long Quiet", "Saltwater", "Paper Moons")


def read_dotenv(path):
    """The repo's .env as a dict, or an empty dict if there isn't one.

    Deliberately not python-dotenv. The point of this script being standard
    library only is that it runs on any Python with no venv and no install
    step, and a twelve line parser is a smaller liability than a dependency on
    a one-off tool nobody will maintain.

    It handles what this file actually contains: KEY=value, blank lines and
    comments. It does not handle quoting rules, multi-line values or variable
    interpolation. If .env ever needs those, that is the moment to reach for
    the library, not before.
    """
    values = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, value = line.partition("=")
                values[key.strip()] = value.strip().strip("\"'")
    except FileNotFoundError:
        pass
    return values


def read_token():
    """The read access token, from the environment first, then from .env.

    That order matters: a real environment variable overrides the file, so a
    one-off run with a different token needs no edit to .env and leaves no
    trace in it.

    .env is resolved relative to this file, not to the working directory, so
    the script behaves the same run from the repo root or from inside tools/.
    Resolving it from the cwd is how a script starts working only when you
    happen to be standing in the right place.

    Not cached. This is read once per API call, about forty-five times, on a
    two line file, on a laptop, once ever. A cache here would be machinery in
    service of nothing measurable.
    """
    from_environment = os.environ.get("TMDB_READ_TOKEN")
    if from_environment:
        return from_environment

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    from_file = read_dotenv(os.path.join(root, ".env")).get("TMDB_READ_TOKEN")
    if from_file:
        return from_file

    sys.exit(
        "TMDB_READ_TOKEN is not set.\n"
        "Put it in .env at the repo root, or set it in the shell for one run."
    )


def get(path, **params):
    url = f"{API}{path}"
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"

    request = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {read_token()}",
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        sys.exit(f"{path} returned {error.code}: {error.read()[:200]!r}")


def candidate_ids():
    """Every id now playing in either region, deduplicated, popular first.

    A film on in both countries appears in both lists and must become one row,
    because tmdb_id is unique. Ordering by popularity is what decides which
    three films the placeholder rows become, and popularity drifts, so this
    script is not reproducible across days. That is acceptable for a one-off
    whose output is committed: the SQL is the artefact, not the script's
    determinism.
    """
    popularity = {}
    for region in REGIONS:
        for page in PAGES:
            body = get(
                "/movie/now_playing", region=region, page=page, language="en-US"
            )
            for row in body.get("results", []):
                popularity[row["id"]] = max(
                    popularity.get(row["id"], 0.0), row.get("popularity") or 0.0
                )
            time.sleep(0.1)

    return sorted(popularity, key=lambda i: popularity[i], reverse=True)


def usable(film):
    """now_playing does not return runtime, so every film costs a detail call.

    A film missing any of these is dropped rather than inserted with a hole in
    it. runtime_minutes has a CHECK (> 0) on it from V1, so a zero would be
    rejected by Postgres anyway, and a film with no release date cannot
    exercise the rule this whole exercise exists to unblock.
    """
    return (
        film.get("title")
        and (film.get("runtime") or 0) > 0
        and film.get("release_date")
        and film.get("poster_path")
    )


def details(ids):
    kept = []
    for tmdb_id in ids:
        film = get(f"/movie/{tmdb_id}", language="en-US")
        if usable(film):
            kept.append(film)
        else:
            print(
                f"skipped {tmdb_id} {film.get('title')!r}: incomplete",
                file=sys.stderr,
            )
        time.sleep(0.1)
    return kept


def lit(value):
    """A single-quoted SQL literal, or NULL.

    Doubling the quote is the whole of the escaping, because every value here
    is a string or NULL and goes into a file a human reads before it runs.
    Nothing from TMDB reaches a live query, so this is not a SQL injection
    surface, but a title with an apostrophe in it is ordinary and would break
    the file.
    """
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def one_line(text):
    """Overviews arrive with newlines in them.

    Postgres accepts a literal newline inside a quoted string, so this is for
    the reader rather than the parser: a migration where one value spans eight
    lines is a migration nobody diffs.
    """
    collapsed = " ".join((text or "").split())
    return collapsed or None


def columns(film):
    return (
        lit(film["title"]),
        str(film["runtime"]),
        str(film["id"]),
        lit(one_line(film.get("overview"))),
        lit(film["poster_path"]),
        lit(film["release_date"]),
    )


def write_sql(handle, films):
    out = handle.write

    repointed = films[: len(PLACEHOLDERS)]
    inserted = films[len(PLACEHOLDERS) :]

    out(f"-- Generated by tools/tmdb_ingest.py on {date.today().isoformat()}.\n")
    out("-- Do not edit by hand. Regenerate into a new migration instead.\n")
    out("--\n")
    out(
        f"-- {len(films)} films from TMDB now_playing, regions "
        f"{' and '.join(REGIONS)}, deduplicated by tmdb id, popular first.\n"
    )
    out("--\n")
    out("-- This product uses the TMDB API but is not endorsed or certified\n")
    out("-- by TMDB. Posters are hotlinked from TMDB's CDN by the browser and\n")
    out("-- are never re-hosted.\n")
    out("\n")
    out("-- V3's three invented films are repointed, not deleted. V4 and V13\n")
    out("-- inserted showtimes against them by title and both migrations have\n")
    out("-- already been applied, so the rows cannot be removed without\n")
    out("-- unpicking applied history. Their screenings are all in the past and\n")
    out("-- are listed by nothing.\n")
    out("\n")

    for placeholder, film in zip(PLACEHOLDERS, repointed):
        title, runtime, tmdb_id, overview, poster, released = columns(film)
        out("UPDATE film SET\n")
        out(f"    title           = {title},\n")
        out(f"    runtime_minutes = {runtime},\n")
        out(f"    tmdb_id         = {tmdb_id},\n")
        out(f"    overview        = {overview},\n")
        out(f"    poster_path     = {poster},\n")
        out(f"    released_on     = {released}\n")
        out(f"WHERE title = {lit(placeholder)};\n\n")

    if not inserted:
        return

    out("-- ON CONFLICT guards the unique tmdb id that V20 added. It cannot\n")
    out("-- fire on a clean database, because the repointed three are sliced\n")
    out("-- out of this list by the generator. It is here so that running this\n")
    out("-- file against a database that already has some of these films is a\n")
    out("-- no-op rather than a failed migration.\n")
    out(
        "INSERT INTO film "
        "(title, runtime_minutes, tmdb_id, overview, poster_path, released_on)\n"
    )
    out("VALUES\n")

    rows = []
    for film in inserted:
        title, runtime, tmdb_id, overview, poster, released = columns(film)
        rows.append(
            f"    ({title}, {runtime}, {tmdb_id}, {overview}, {poster}, {released})"
        )
    out(",\n".join(rows))
    out("\nON CONFLICT (tmdb_id) DO NOTHING;\n")


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: python tools/tmdb_ingest.py <output.sql>")

    destination = sys.argv[1]

    films = details(candidate_ids())
    if len(films) < len(PLACEHOLDERS):
        sys.exit(f"only {len(films)} usable films, need at least {len(PLACEHOLDERS)}")

    # newline="\n" because .gitattributes stores everything LF. Python on
    # Windows would otherwise translate every \n to \r\n on write, and the file
    # would land as a whole-file diff the moment git normalised it back.
    with open(destination, "w", encoding="utf-8", newline="\n") as handle:
        write_sql(handle, films)

    print(f"wrote {len(films)} films to {destination}", file=sys.stderr)


if __name__ == "__main__":
    main()