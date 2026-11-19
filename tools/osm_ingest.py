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


def report(body):
    found = {name: [] for name, _, _, _ in CITIES}
    unnamed = 0
    unplaceable = 0
    seen = set()

    for element in body.get("elements", []):
        point = coordinates(element)
        if point is None:
            unplaceable += 1
            continue

        name = (element.get("tags") or {}).get("name")
        if not name:
            # A cinema OSM knows the location of but not the name of. V18
            # dropped these too. A row called "Cinema" is not a cinema row.
            unnamed += 1
            continue

        key = (element["type"], element["id"])
        if key in seen:
            continue
        seen.add(key)

        city = nearest_city(*point)
        found[city[0]].append(name)

    print()
    print(f"{'city':<22}{'state':<18}{'now':>5}{'osm':>6}{'delta':>8}")
    print("-" * 59)

    totals = {"GB": [0, 0], "IN": [0, 0]}
    for name, state, _, _ in CITIES:
        now = EXISTING.get(name, 0)
        osm = len(found[name])
        country = country_of(state)
        totals[country][0] += now
        totals[country][1] += osm
        print(f"{name:<22}{state:<18}{now:>5}{osm:>6}{osm - now:>+8}")

    print("-" * 59)
    for country in ("GB", "IN"):
        now, osm = totals[country]
        print(f"{country:<40}{now:>5}{osm:>6}{osm - now:>+8}")

    grand_now = totals["GB"][0] + totals["IN"][0]
    grand_osm = totals["GB"][1] + totals["IN"][1]
    print(f"{'total':<40}{grand_now:>5}{grand_osm:>6}{grand_osm - grand_now:>+8}")
    print()
    print(f"{unnamed} tagged cinemas with no name, dropped", file=sys.stderr)
    if unplaceable:
        print(f"{unplaceable} with no usable coordinate, dropped", file=sys.stderr)


def main():
    refresh = "--refresh" in sys.argv[1:]
    report(load(refresh))


if __name__ == "__main__":
    main()