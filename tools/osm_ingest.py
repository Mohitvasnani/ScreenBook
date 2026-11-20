#!/usr/bin/env python3
"""
OpenStreetMap cinema ingestion for ScreenBook. Phase one: ask, cache, report.

This writes no SQL. It asks Overpass what is actually tagged near each city
already in the database, caches the answer, and prints it against what V18
has. The number it prints is the input to the decision about whether an
expansion migration is worth writing, and estimating that number from
Nominatim's output would be estimating it from the tool that caused the
problem.

Why Overpass rather than Nominatim, which V18 used. Nominatim is a geocoder:
you ask it for "cinemas in Jaipur" and it returns a ranked page with a cap,
because it exists to answer "which one did you mean". Overpass is the bulk
query interface to the same database and returns every tagged feature in an
area with no cap. V18 used Nominatim only because Overpass refused that
network, so the 130 rows are an artefact of that refusal rather than a
statement about what OSM knows.

Run it by hand:

    python tools/osm_ingest.py

It writes tools/osm_cinemas.json and prints a table. Running it again reads
the cache. Pass --refresh to actually hit the network, which you should do
rarely: Overpass is a free shared service and re-querying it to reprint a
table you already have on disk is rude.

Licence is ODbL, same as V18. "© OpenStreetMap contributors" already appears
wherever these are displayed, which is the condition for using them.
"""

import json
import math
import os
import sys
from time import time
import urllib.error
import urllib.parse
import urllib.request
import time
import datetime
from datetime import date

# Tried in order. The main instance is the busiest and refuses with 502 or 429
# when its queue is full, which in the afternoon it usually is. The mirrors run
# the same software against the same data and are generally idle.
ENDPOINTS = (
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass-api.de/api/interpreter",
)

# If the main instance is busy it answers 429 or just hangs. This mirror
# speaks the same API. Swap ENDPOINT for it rather than retrying in a loop,
# because a loop against a rate limiter is how you get blocked properly.
MIRROR = "https://overpass.kumi.systems/api/interpreter"

# Public instances ask that you identify your client. A script with no
# User-Agent looks like scraping and gets treated like it.
USER_AGENT = "ScreenBook-ingest/1.0 (portfolio project; mohitvasnani47@gmail.com)"

CACHE = os.path.join(os.path.dirname(__file__), "osm_cinemas.json")

# 20km around each centre. Big enough to reach the out-of-town multiplexes
# that are the whole reason a radius search is interesting, small enough that
# Delhi does not swallow Ghaziabad. Overlaps are fine: every result is
# assigned to its nearest centre afterwards, so a cinema between Mumbai and
# Thane lands in exactly one of them.
RADIUS_METRES = 20000

# The tags a composed address can draw on, and the count used to decide which
# copy of a duplicated venue survives.
ADDR_KEYS = ("addr:full", "addr:housenumber", "addr:street", "addr:suburb",
             "addr:city", "addr:postcode")

# Two records of one venue. OSM frequently holds a point for the entrance and a
# building way for the same cinema, and there are 15 such pairs in this data.
# Generous on purpose: two genuinely different cinemas sharing a name this close
# together are in one shopping centre, where one row is the right answer anyway.
DUPLICATE_METRES = 150

TIMEOUT_SECONDS = 300

# Name, state and centre, where the centre is the mean of that city's existing
# cinemas in V18. Derived from the data rather than typed from memory, so the
# query is centred on where the dataset actually thinks each city is.
CITIES = (
    ("Agra", "Uttar Pradesh", 27.1834, 78.0070),
    ("Ahmedabad", "Gujarat", 23.0362, 72.6004),
    ("Bengaluru", "Karnataka", 12.9865, 77.5959),
    ("Bhopal", "Madhya Pradesh", 23.2273, 77.4205),
    ("Birmingham", "England", 52.4566, -1.9242),
    ("Chandigarh", "Chandigarh", 30.7336, 76.7713),
    ("Coimbatore", "Tamil Nadu", 11.0355, 76.9312),
    ("Dehradun", "Uttarakhand", 30.3605, 78.0485),
    ("Delhi", "Delhi", 28.5524, 77.2081),
    ("Ghaziabad", "Uttar Pradesh", 28.6550, 77.3452),
    ("Glasgow", "Scotland", 55.8489, -4.3181),
    ("Guwahati", "Assam", 26.1797, 91.7466),
    ("Hyderabad", "Telangana", 17.4045, 78.4680),
    ("Jaipur", "Rajasthan", 26.8970, 75.7771),
    ("Jodhpur", "Rajasthan", 26.2804, 73.0060),
    ("Kanpur", "Uttar Pradesh", 26.4654, 80.3132),
    ("Kochi", "Kerala", 9.9440, 76.2825),
    ("Kolkata", "West Bengal", 22.5388, 88.3507),
    ("Kozhikode", "Kerala", 11.2701, 75.8442),
    ("Leeds", "England", 53.8080, -1.5172),
    ("Liverpool", "England", 53.3886, -2.9538),
    ("London", "England", 51.5306, -0.1436),
    ("Lucknow", "Uttar Pradesh", 26.8445, 80.9265),
    ("Ludhiana", "Punjab", 30.9070, 75.8475),
    ("Manchester", "England", 53.4594, -2.2688),
    ("Meerut", "Uttar Pradesh", 28.9738, 77.6900),
    ("Mumbai", "Maharashtra", 19.0680, 72.8582),
    ("Mysuru", "Karnataka", 12.2976, 76.6643),
    ("Nagpur", "Maharashtra", 21.1221, 79.0858),
    ("Patna", "Bihar", 25.6232, 85.1254),
    ("Pune", "Maharashtra", 18.5209, 73.8812),
    ("Rajkot", "Gujarat", 22.2994, 70.7921),
    ("Ranchi", "Jharkhand", 23.3767, 85.3164),
    ("Siliguri", "West Bengal", 26.7325, 88.4318),
    ("Surat", "Gujarat", 21.1538, 72.7661),
    ("Thane", "Maharashtra", 19.1877, 72.9720),
    ("Thiruvananthapuram", "Kerala", 8.6488, 76.8224),
    ("Tiruchirappalli", "Tamil Nadu", 10.9601, 78.6956),
    ("Varanasi", "Uttar Pradesh", 25.3024, 82.9886),
    ("Vijayawada", "Andhra Pradesh", 16.5053, 80.6354),
)

# What V18 currently holds, for the comparison column.
EXISTING = {
    "Agra": 4, "Ahmedabad": 4, "Bengaluru": 5, "Bhopal": 3, "Birmingham": 4,
    "Chandigarh": 1, "Coimbatore": 5, "Dehradun": 4, "Delhi": 6,
    "Ghaziabad": 1, "Glasgow": 2, "Guwahati": 2, "Hyderabad": 5, "Jaipur": 4,
    "Jodhpur": 1, "Kanpur": 1, "Kochi": 1, "Kolkata": 6, "Kozhikode": 1,
    "Leeds": 3, "Liverpool": 6, "London": 6, "Lucknow": 4, "Ludhiana": 6,
    "Manchester": 6, "Meerut": 1, "Mumbai": 6, "Mysuru": 1, "Nagpur": 2,
    "Patna": 3, "Pune": 3, "Rajkot": 1, "Ranchi": 4, "Siliguri": 1,
    "Surat": 3, "Thane": 2, "Thiruvananthapuram": 4, "Tiruchirappalli": 2,
    "Varanasi": 4, "Vijayawada": 2,
}


def country_of(state):
    """The same CASE V18, V17 and V22 all use. Three places now, which is two
    too many, and the reason V22 put the answer on a column."""
    return "GB" if state in ("England", "Scotland") else "IN"


def build_query():
    """One request, not forty.

    Forty separate requests to a free shared service, to ask forty versions of
    the same question, is the kind of thing that gets a client blocked. A
    union block asks all of them at once and Overpass answers in a single
    body. nwr is node, way and relation together: a small cinema is often a
    node, a multiplex is usually a building way, and a few are relations.

    "out body center" gives the tags plus one coordinate per element. Ways and
    relations have no coordinate of their own, so center computes one from
    their geometry, which is what makes a building and a point comparable.
    """
    clauses = "\n".join(
        f'  nwr["amenity"="cinema"](around:{RADIUS_METRES},{lat},{lon});'
        for _, _, lat, lon in CITIES
    )
    return f"[out:json][timeout:{TIMEOUT_SECONDS}];\n(\n{clauses}\n);\nout body center;"


def fetch():
    """Each instance in turn, then a widening wait, then once more.

    The server's own advice when it refuses is exponential backoff, so this
    takes it rather than retrying straight away. Two passes over three
    instances is the right amount of trying: if every public instance is
    saturated, the answer is to come back in an hour, not to keep knocking.
    """
    query = build_query()
    delay = 10

    for attempt in range(2):
        for endpoint in ENDPOINTS:
            request = urllib.request.Request(
                endpoint,
                data=urllib.parse.urlencode({"data": query}).encode("utf-8"),
                headers={"User-Agent": USER_AGENT},
            )
            print(f"querying {endpoint}", file=sys.stderr)
            try:
                with urllib.request.urlopen(
                    request, timeout=TIMEOUT_SECONDS
                ) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                print(f"  {error.code}, busy or refused", file=sys.stderr)
            except (urllib.error.URLError, TimeoutError) as error:
                print(f"  unreachable: {error}", file=sys.stderr)

        if attempt == 0:
            print(f"all instances busy, waiting {delay}s", file=sys.stderr)
            time.sleep(delay)

    sys.exit("every Overpass instance refused. Try again in an hour.")

def load(refresh):
    if not refresh and os.path.exists(CACHE):
        print(f"reading {CACHE} (pass --refresh to re-query)", file=sys.stderr)
        with open(CACHE, encoding="utf-8") as handle:
            return json.load(handle)

    body = fetch()
    with open(CACHE, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(body, handle, ensure_ascii=False, indent=1)
    return body


def coordinates(element):
    """Nodes carry lat and lon. Ways and relations carry a center because the
    query asked for one. Anything with neither is not usable."""
    if "lat" in element and "lon" in element:
        return element["lat"], element["lon"]
    centre = element.get("center")
    if centre:
        return centre["lat"], centre["lon"]
    return None


def distance_km(lat_a, lon_a, lat_b, lon_b):
    """Haversine. Good enough to decide which of two city centres is closer,
    which is the only thing it is used for. Nothing here reaches the database,
    where PostGIS does this properly on a geography column."""
    radius = 6371.0
    d_lat = math.radians(lat_b - lat_a)
    d_lon = math.radians(lon_b - lon_a)
    a = (math.sin(d_lat / 2) ** 2
         + math.cos(math.radians(lat_a)) * math.cos(math.radians(lat_b))
         * math.sin(d_lon / 2) ** 2)
    return radius * 2 * math.asin(math.sqrt(a))


def nearest_city(lat, lon):
    return min(CITIES, key=lambda c: distance_km(lat, lon, c[2], c[3]))

def report(rows, kept, dropped):
    counted = {}
    for row in kept:
        counted.setdefault(row["city"], 0)
        counted[row["city"]] += 1

    print()
    print(f"{'city':<22}{'state':<18}{'now':>5}{'osm':>6}{'delta':>8}")
    print("-" * 59)

    totals = {"GB": [0, 0], "IN": [0, 0]}
    for name, state, _, _ in CITIES:
        now = EXISTING.get(name, 0)
        osm = counted.get(name, 0)
        bucket = totals[country_of(state)]
        bucket[0] += now
        bucket[1] += osm
        print(f"{name:<22}{state:<18}{now:>5}{osm:>6}{osm - now:>+8}")

    print("-" * 59)
    for code in ("GB", "IN"):
        now, osm = totals[code]
        print(f"{code:<40}{now:>5}{osm:>6}{osm - now:>+8}")

    grand_now = totals["GB"][0] + totals["IN"][0]
    grand_osm = totals["GB"][1] + totals["IN"][1]
    print(f"{'total':<40}{grand_now:>5}{grand_osm:>6}{grand_osm - grand_now:>+8}")
    print()

    addressed = sum(1 for row in kept if row["address"])
    print(f"{len(rows)} named and locatable, {len(rows) - len(kept)} dropped as "
          f"one venue mapped twice, {len(kept)} kept", file=sys.stderr)
    print(f"{addressed} of those carry an address, {len(kept) - addressed} do not",
          file=sys.stderr)
    print(f"{dropped['unnamed']} tagged cinemas with no name, dropped",
          file=sys.stderr)
    if dropped["unplaceable"]:
        print(f"{dropped['unplaceable']} with no usable coordinate, dropped",
              file=sys.stderr)
def one_line(text):
    """OSM values arrive with newlines and double spaces in them.

    Postgres would accept a literal newline inside a quoted string, so this is
    for the reader rather than the parser: a migration where one value spans
    three lines is a migration nobody diffs. Returns None for empty, because
    the caller's question is always "is there one", never "is it blank".
    """
    collapsed = " ".join((text or "").split())
    return collapsed or None


def lit(value):
    """A single-quoted SQL literal, or NULL.

    Doubling the quote is the whole of the escaping, because every value here
    is a string or NULL and goes into a file a human reads before it runs.
    Nothing from OSM reaches a live query, so this is not an injection surface,
    but "Shoppers' Stop Cinema" is an ordinary name and would break the file.
    """
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def compose_address(tags):
    """A street line from whatever tags exist, or None.

    addr:full wins when present, because a mapper wrote it as one line on
    purpose. Otherwise house number and street join with a space, since
    "12 Bold Street" is how an address is written and "12, Bold Street" is not,
    and the suburb and postcode follow as their own comma-separated parts.

    addr:city is deliberately not used. The city column already holds it, and
    repeating it inside the address is the kind of duplication that disagrees
    with itself eventually.

    Returns None rather than "". V24 made the column nullable so an unmapped
    address is absent, and an empty string would be a third state to test for.
    """
    full = one_line(tags.get("addr:full"))
    if full:
        return full

    street = one_line(tags.get("addr:street"))
    number = one_line(tags.get("addr:housenumber"))
    first = " ".join(x for x in (number, street) if x) if street else None

    parts = (first,
             one_line(tags.get("addr:suburb")),
             one_line(tags.get("addr:postcode")))
    return ", ".join(x for x in parts if x) or None


def records(body):
    """Every usable cinema from the cached response, assigned to a city.

    Usable means named and locatable. There are 49 cinemas in these cities that
    OSM has a position for and no name, and they are dropped for the reason V18
    dropped the same kind: a row called "Cinema" is not a cinema row.

    City comes from the nearest centre, not from addr:city, which only a third
    of these carry. The simplification is real and worth stating: a Birkenhead
    cinema comes out tagged Liverpool. For an app where people search for
    "Liverpool" that is arguably the right answer, and the migration header
    says so rather than leaving it to be discovered.

    state comes with the city, and the generated SQL derives currency, timezone
    and country from it using the same CASE V18 and V22 used, so that rule
    continues to live in exactly one place.
    """
    rows = []
    dropped = {"unnamed": 0, "unplaceable": 0}

    for element in body.get("elements", []):
        point = coordinates(element)
        if point is None:
            dropped["unplaceable"] += 1
            continue

        tags = element.get("tags") or {}
        name = one_line(tags.get("name"))
        if not name:
            dropped["unnamed"] += 1
            continue

        city = nearest_city(*point)
        rows.append({
            "name": name,
            "city": city[0],
            "state": city[1],
            "lat": point[0],
            "lon": point[1],
            "osm_type": element["type"],
            "osm_id": element["id"],
            "address": compose_address(tags),
            "detail": sum(1 for key in ADDR_KEYS if key in tags),
        })

    return rows, dropped


def dedupe(rows):
    """One venue mapped twice is still one cinema.

    V24's unique constraint is on (osm_type, osm_id), so Postgres cannot catch
    these: a node and a way are different objects with different ids and both
    would insert cleanly. The only thing that knows they are the same cinema is
    this function.

    Which copy survives, in order: the one with more address tags, because it
    is the better record; then a way over a node, because a building outline
    describes a venue better than a dropped pin; then the lower id, so the
    output is the same on every run and the migration does not reshuffle.

    Grouped by name before measuring, so this is 991 lookups and a handful of
    distance calculations rather than half a million.
    """
    ordered = sorted(rows, key=lambda r: (-r["detail"],
                                          r["osm_type"] != "way",
                                          r["osm_id"]))
    seen = {}
    kept = []

    for row in ordered:
        near = seen.setdefault(row["name"], [])
        if any(distance_km(row["lat"], row["lon"], other["lat"], other["lon"]) * 1000
               < DUPLICATE_METRES
               for other in near):
            continue
        near.append(row)
        kept.append(row)

    return kept


def write_sql(handle, rows):
    """The cinema rows, and nothing else.

    No screens and no seats. V18 generated those inline, but RoomSeeder now
    owns that job and converges on every boot, so this migration inserts
    buildings and the rooms appear by themselves on the next startup. That is
    the whole reason the seeder exists and the reason this file is 991 rows
    rather than 991 rows plus a copy of V18's generation logic.

    INSERT ... SELECT over a VALUES list rather than a plain INSERT, because
    currency, timezone and country are all derived from state by the same CASE
    V18 and V22 used and writing them out per row would be three more chances
    to disagree with that rule.

    ON CONFLICT (osm_type, osm_id) DO NOTHING is what makes this safe to run
    against a database that already holds some of these, which it does: all 130
    of V18's rows are in here too, and V24 backfilled their ids precisely so
    this would skip them.
    """
    out = handle.write
    rows = sorted(rows, key=lambda r: (r["city"], r["name"], r["osm_id"]))

    with_address = sum(1 for r in rows if r["address"])

    out(f"-- Generated by tools/osm_ingest.py on {date.today().isoformat()}.\n")
    out("-- Do not edit by hand. Regenerate into a new migration instead.\n")
    out("--\n")
    out(f"-- {len(rows)} cinemas from OpenStreetMap, via Overpass, within\n")
    out(f"-- {RADIUS_METRES // 1000}km of each of the {len(CITIES)} cities this\n")
    out("-- database already covers.\n")
    out("--\n")
    out("-- Overpass rather than Nominatim, which V18 used. Nominatim is a\n")
    out("-- geocoder: it ranks its answers and caps them, because it exists to\n")
    out("-- answer \"which one did you mean\". Overpass is the bulk query\n")
    out("-- interface to the same database and returns everything tagged. V18\n")
    out("-- used Nominatim only because Overpass refused that network, so the\n")
    out("-- 130 rows were an artefact of the tool rather than a statement\n")
    out("-- about what OSM holds.\n")
    out("--\n")
    out("-- Names and coordinates are real. The osm_type and osm_id on every\n")
    out("-- row are the identity V24 added, so any of these can be checked\n")
    out("-- against openstreetmap.org and so that re-running the ingestion is\n")
    out("-- idempotent rather than a hand-merge.\n")
    out("--\n")
    out(f"-- {with_address} of these carry an address and {len(rows) - with_address}\n")
    out("-- do not, because two thirds of OSM's cinemas have no addr:* tags at\n")
    out("-- all. V24 made the column nullable rather than filling the gap:\n")
    out("-- bulk reverse geocoding through Nominatim is against their usage\n")
    out("-- policy, a commercial geocoder would dress a vendor's guess from\n")
    out("-- coordinates up as something a mapper wrote, and composing\n")
    out("-- \"Jaipur, Rajasthan\" would be inventing data. The map answers\n")
    out("-- where a building is, exactly, and the API sends the distance.\n")
    out("--\n")
    out("-- City is assigned by nearest centre rather than by addr:city, which\n")
    out("-- only a third of these carry. So a Birkenhead cinema is tagged\n")
    out("-- Liverpool. For an app where people search for \"Liverpool\" that is\n")
    out("-- arguably right, but it is a simplification and this is it stated.\n")
    out("--\n")
    out("-- Screens and seats are not here. RoomSeeder converges on every boot\n")
    out("-- and will build rooms for every one of these on the next startup.\n")
    out("--\n")
    out("-- Licence is ODbL, so \"(c) OpenStreetMap contributors\" has to appear\n")
    out("-- wherever these are displayed, which it already does.\n")
    out("\n")

    out("INSERT INTO cinema\n")
    out("    (name, city, state, address, currency, timezone, location,\n")
    out("     country, osm_type, osm_id)\n")
    out("SELECT v.name, v.city, v.state, v.address,\n")
    out("       CASE WHEN v.state IN ('England', 'Scotland') THEN 'GBP'\n")
    out("            ELSE 'INR' END,\n")
    out("       CASE WHEN v.state IN ('England', 'Scotland') THEN 'Europe/London'\n")
    out("            ELSE 'Asia/Kolkata' END,\n")
    out("       ST_SetSRID(ST_MakePoint(CAST(v.lon AS double precision),\n")
    out("                               CAST(v.lat AS double precision)),\n")
    out("                  4326)::geography,\n")
    out("       CASE WHEN v.state IN ('England', 'Scotland') THEN 'GB'\n")
    out("            ELSE 'IN' END,\n")
    out("       v.osm_type, v.osm_id\n")
    out("FROM (VALUES\n")

    values = []
    for row in rows:
        values.append(
            "    ({}, {}, {}, {}, {}, {}, {}, {})".format(
                lit(row["name"]), lit(row["city"]), lit(row["state"]),
                lit(row["address"]),
                f"{row['lon']:.6f}", f"{row['lat']:.6f}",
                lit(row["osm_type"]), row["osm_id"]))
    out(",\n".join(values))

    out("\n     ) AS v(name, city, state, address, lon, lat, osm_type, osm_id)\n")
    out("ON CONFLICT (osm_type, osm_id) DO NOTHING;\n")


def main():
    """No output path means report only.

    The report is the cheap thing you run to see what is there. Writing the SQL
    is the thing you do once you have decided, and it takes a path for the same
    reason the TMDB script does: a shell redirect on PowerShell 5.1 writes
    UTF-16LE and these names are full of non-ASCII.
    """
    arguments = sys.argv[1:]
    refresh = "--refresh" in arguments
    paths = [a for a in arguments if not a.startswith("--")]

    rows, dropped = records(load(refresh))
    kept = dedupe(rows)
    report(rows, kept, dropped)

    if not paths:
        print("no output path given, so no SQL was written", file=sys.stderr)
        return

    destination = paths[0]
    with open(destination, "w", encoding="utf-8", newline="\n") as handle:
        write_sql(handle, kept)

    print(f"wrote {len(kept)} cinemas to {destination}", file=sys.stderr)

if __name__ == "__main__":
    main()