"use client";

import Link from "next/link";
import dynamic from "next/dynamic";
import { Fragment, useMemo, useState } from "react";
import { CinemaSearch } from "@/components/CinemaSearch";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Skeleton } from "@/components/Skeleton";
import { RouteLegend } from "@/components/RouteLegend";
import styles from "@/css/CinemasScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { useRoadRoutes } from "@/hooks/useRoadRoutes";
import { api } from "@/services/apiClient";
import type { Cinema, CinemaCity, CinemasWithin, NearestCinema } from "@/types/api";
import { distance, money } from "@/utils/format";
import { MARKETS,  type Market } from "@/utils/markets";
import { pickRoutes } from "@/utils/routes";


/* Loaded only when this screen renders, for two reasons that happen to have
   one fix. MapLibre is around three quarters of a megabyte, and importing it
   normally would put it in the bundle for the seat map and the checkout too.
   It also needs a real browser window, so it cannot be server-rendered at all. */
const CinemaMap = dynamic(
  () => import("@/components/CinemaMap").then((m) => m.CinemaMap),
  { ssr: false, loading: () => null },
);


/* The chips that used to live here are gone. They were five shortcuts plus
   "All cities", which was a reasonable row at 130 cinemas and stopped being
   one at 980: London alone has 127, so a shortcut to a city is no longer a
   shortcut to a readable list. The search box does the same job without
   claiming five cities matter more than the other thirty-five. */
/* Three radii, and 10km is the middle one because that is also
   screenbook.cinemas.within-default-metres. The control and the server's
   default agree, so the first fetch is not a different question from the one
   the pressed segment claims to be asking. */
const RADII = [5_000, 10_000, 15_000] as const;

/* The browser's geolocation callback API as a promise, so the handler below
   reads as one sequence instead of nesting the fetch inside a callback.

   No high accuracy and a short timeout: this picks a cinema, so a few hundred
   metres is irrelevant and waiting on GPS to settle is worse than a slightly
   wrong city. */
function currentPosition(): Promise<GeolocationPosition> {
  return new Promise((resolve, reject) => {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      reject(new Error("This browser cannot report a location."));
      return;
    }
    navigator.geolocation.getCurrentPosition(resolve, reject, {
      enableHighAccuracy: false,
      timeout: 8_000,
      maximumAge: 5 * 60 * 1000,
    });
  });
}

/* The browser's own messages are unhelpful and vary by vendor, so each code
   gets a sentence that says what the viewer can do about it. Code 1 is the only
   one that is a decision rather than a failure, and it must not be nagged at:
   the city list below is a complete way to use the site. */
function geolocationMessage(error: unknown): string {
  if (typeof GeolocationPositionError !== "undefined" && error instanceof GeolocationPositionError) {
    switch (error.code) {
      case error.PERMISSION_DENIED:
        return "Location is off for this site, so pick a city instead.";
      case error.POSITION_UNAVAILABLE:
        return "Your browser could not work out where you are. Pick a city instead.";
      case error.TIMEOUT:
        return "Finding your location took too long. Try again, or pick a city.";
    }
  }
  return error instanceof Error ? error.message : "Could not find your location.";
}

/* The API returns cinemas ordered by city then name, so inserting in order
   groups them with no sort of our own. */
function groupByCity(cinemas: Cinema[]): [string, Cinema[]][] {
  const byCity = new Map<string, Cinema[]>();
  for (const cinema of cinemas) {
    byCity.set(cinema.city, [...(byCity.get(cinema.city) ?? []), cinema]);
  }
  return [...byCity.entries()];
}

export function CinemasScreen() {
  const { data, error, loading, reload } = useApi(() => api.listCinemas(), "cinemas");

  /* 40 rows against 980, and it carries each city's centroid, so one fetch
     answers both halves of search: what to offer, and where to centre a
     radius when the viewer picks one. The browser used to compute those
     centroids itself from the full list, which is the reason the full list
     had to be here at all. */
  const { data: cityRows } = useApi(() => api.cinemaCities(), "cities");

  /* Null is the opening state, not a missing value: no country chosen, the
     whole planet on screen, and both countries in the list. */
  const [market, setMarket] = useState<Market | null>(null);
  const [city, setCity] = useState<string | null>(null);
  
    /* Which question the camera should answer. The viewer's last action sets it,
     because a city chosen after a radius and a radius chosen after a city are
     different intentions and props alone cannot tell them apart. */
  const [focus, setFocus] = useState<"selection" | "radius">("selection");

    /* The viewer's location and the radius are separate state, and that is the
     decision worth defending. Geolocation is a one-shot event the viewer
     triggers; the radius is a value they change afterwards. Holding them
     together would mean re-prompting the browser on every radius press, which
     is both slower and, after a declined permission, impossible. */
  /* The origin carries its own label. "Within 10 km of you" and "within 10 km
     of Hyderabad" are different sentences, and deriving which one to say by
     comparing coordinates back against the city list would be guessing at
     something the action already knew. */
  const [origin, setOrigin] = useState<
    { lat: number; lon: number; label: string } | null
  >(null);
  const [radius, setRadius] = useState<number>(10_000);
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);

  /* Derived, not imperative. The radius query is a function of the origin and
     the radius, so useApi keyed on both refetches when either changes and
     never when neither does. With no origin the key is idle and fn resolves
     null, because useApi has no notion of "do not fetch yet" and inventing one
     for a promise that resolves immediately would be the more complicated
     half. */
  const {
    data: within,
    error: withinError,
    loading: withinLoading,
  } = useApi<CinemasWithin | null>(
    () =>
      origin === null
        ? Promise.resolve(null)
        : api.cinemasWithin(origin.lat, origin.lon, radius),
    origin === null ? "within:idle" : `within:${origin.lat},${origin.lon},${radius}`,
  );

  /* Geolocation only. The fetch is not in here, so a failure to locate and a
     failure to query are separate states with separate messages: one is a
     permission the viewer controls, the other is the API being down. */
  async function locate() {
    setFocus("radius");
    setLocationError(null);
    setLocating(true);
    try {
      const position = await currentPosition();
      setOrigin({
        lat: position.coords.latitude,
        lon: position.coords.longitude,
        label: "you",
      });
    } catch (failure) {
      setOrigin(null);
      setLocationError(geolocationMessage(failure));
    } finally {
      setLocating(false);
    }
  }

  /* Pressing the country you are already in takes you back out to the planet.
     The segment is a toggle rather than a one-way door, which is also the only
     way back from a city or a street without using the browser's back button.

     The city clears in the same handler rather than in an effect watching
     market. An effect would render once with a city that is not in the new
     country, then correct itself, and the list would flash on every press. */
  function chooseMarket(next: Market) {
    const leaving = next === market;
    setMarket(leaving ? null : next);
    setCity(null);
    setOrigin(null);
    setFocus("selection");
  }

  /* Picking a city from the planet selects its country too. The heading, the
     chips and the list then agree with what the camera is looking at, which
     they would not if the country stayed unset. */
  function chooseCity(name: string) {
    setCity(name);
    setOrigin(null);
    setFocus("selection");
    if (market === null) {
      const found = (data ?? []).find((cinema) => cinema.city === name);
      if (found) setMarket(found.country);
    }
  }

  /* Picking from search already knows the country, so unlike chooseCity it
     does not have to find a cinema to work it out. */
  function chooseCityRow(row: CinemaCity) {
    setMarket(row.country);
    setCity(row.city);
    setOrigin(null);
    setFocus("selection");
  }

  const inMarket = useMemo(
   ()=> (data ?? []).filter((cinema) => market === null || cinema.country === market),
   [data, market],
  );


    /* Memoised because CinemaMap's update effect lists it. A fresh array every
     render would re-run that effect, and with it a camera move, on every
     keystroke anywhere on this page. */
  const inRadiusIds = useMemo(
    () => (within?.cinemas ?? []).map((cinema) => cinema.id),
    [within],
  );

    /* The three picks, from the rows the radius query already returned.
     Memoised on within: pickRoutes builds a fresh array every call, and
     useRoadRoutes and CinemaMap both depend on this one. A new array each
     render would re-run both on every keystroke. */
  const routes = useMemo(() => pickRoutes(within?.cinemas ?? []), [within]);

  
  /* Picks with their road route joined on, once one has been fetched. */
  const {
    routes: drawnRoutes,
    loading: roadsLoading,
    failed: roadsFailed,
    load: loadRoads,
  } = useRoadRoutes(routes, origin);

  const cities = useMemo(() => cityRows ?? [], [cityRows]);

  /* The row behind the chosen city, which is what holds its centroid. Scoped
     by market as well as name because two cities can share a name across the
     two countries, and the one the viewer is looking at is the one in the
     country they chose. */
  const selectedCityRow = useMemo<CinemaCity | null>(
    () =>
      cities.find(
        (row) => row.city === city && (market === null || row.country === market),
      ) ?? null,
    [cities, city, market],
  );

  const visible = city === null ? inMarket : inMarket.filter((c) => c.city === city);

  /* With a country chosen the list is that country's cities. With none chosen
     it is both countries, each with its cities under it: someone who scrolls
     past the globe should meet a hundred and thirty cinemas, not an
     instruction to go back and press something. */
  const sections = (market === null ? MARKETS.map((m) => m.id) : [market]).map(
    (id) => ({
      id,
      label: MARKETS.find((m) => m.id === id)?.label ?? "",
      cities: groupByCity(visible.filter((cinema) => cinema.country === id)),
    }),
  );

  const sentence = market === null
    ? null
    : MARKETS.find((m) => m.id === market)?.inSentence ?? null;

  return (
    <>
      <section className={styles.hero}>
        {/* Every cinema, never the filtered list: this component's build
            effect depends on the array it is handed, so a filtered array
            would destroy and rebuild the map on every click. The selection
            goes in as its own props and reaches the live map as setData and
            a camera move. */}
        <div className={styles.globe}>
          {data && data.length > 0 && (
            <CinemaMap
              cinemas={data}
              market={market}
              city={city}
              origin={origin}
              radiusMetres={radius}
              inRadiusIds={inRadiusIds}
              routes={drawnRoutes}
              focus={focus}
              onPickCity={chooseCity}
            />
          )}
        </div>

        {/* Decoration, and neither may take pointer events or the globe stops
            being draggable wherever the panel has whitespace. */}
        <div className={styles.scrim} aria-hidden />
        <div className={styles.fade} aria-hidden />

        <div className={styles.panel}>
          <div className={styles.panelInner}>
            <span className={styles.eyebrow}>Cinemas</span>

            <h1 className={styles.title}>
              {sentence ? `Find a cinema in ${sentence}` : "Find a cinema"}
            </h1>

            <p className={styles.subtitle}>
              {sentence
                ? "Pick a city on the globe or browse the list below."
                                : `Two countries, ${data?.length ?? 0} cinemas. Pick one to open it up.`}
            </p>

            {/* aria-pressed rather than a radio group: these are buttons that
                change the page, not a value being submitted with a form, and
                pressing the pressed one releases it. */}
            <div className={styles.toggle} role="group" aria-label="Country">
              {MARKETS.map((option) => (
                <button
                  key={option.id}
                  type="button"
                  className={
                    option.id === market
                      ? `${styles.segment} ${styles.segmentOn}`
                      : styles.segment
                  }
                  aria-pressed={option.id === market}
                  onClick={() => chooseMarket(option.id)}
                >
                  {option.label}
                </button>
              ))}
            </div>

            {/* Always available, unlike the chips it replaces, which needed a
                country chosen first. Searching "Jaipur" from the opening
                planet view is a reasonable thing to do and that row could not
                answer it. */}
            <CinemaSearch
              cinemas={data ?? []}
              cities={cities}
              selectedCity={city}
              onPickCity={chooseCityRow}
            />

            <div className={styles.locate}>
                            <button
                type="button"
                className={styles.locateButton}
                onClick={locate}
                disabled={locating}
              >
                {/* Inline, the way the footer's links carry theirs, rather
                    than an icon font or a dependency. aria-hidden because the
                    label beside it already says what this does. */}
                <svg
                  aria-hidden
                  className={styles.locateIcon}
                  width="15"
                  height="15"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                >
                  <path d="M20 10c0 5-8 12-8 12s-8-7-8-12a8 8 0 0 1 16 0Z" />
                  <circle cx="12" cy="10" r="3" />
                </svg>
                {locating ? "Finding you..." : "Use my location"}
              </button>

              {/* The manual origin, and deliberately a separate action from
                  picking the city. One action, one effect: choosing a city to
                  browse should never silently put the page into radius mode.

                  It is also the thing that makes a radius usable by someone
                  who is not standing in it. Looking at what is on within 10km
                  of Jaipur from a desk in Liverpool is a real thing to want,
                  and it was impossible while the only origin was the device's
                  own GPS. */}
              {selectedCityRow && (
                <button
                  type="button"
                  className={styles.locateButton}
                  onClick={() => {
                    setLocationError(null);
                    setFocus("radius");
                    setOrigin({
                      lat: selectedCityRow.latitude,
                      lon: selectedCityRow.longitude,
                      label: selectedCityRow.city,
                    });
                  }}
                >
                  Search around {selectedCityRow.city}
                </button>
              )}

              {/* Wave 6 left no way back to the planet except the country
                  toggle, and pressing the chip for the city you are already in
                  changes no state and therefore moves no camera. One button
                  that clears everything is the only honest "back" when the
                  camera is driven from state. */}
              {(market !== null || city !== null || origin !== null) && (
                <button
                  type="button"
                  className={styles.locateButton}
                  onClick={() => {
                    setMarket(null);
                    setCity(null);
                    setOrigin(null);
                    setFocus("selection");
                  }}
                >
                  Back to the globe
                </button>
              )}

              {/* Not an ErrorState. A declined permission is a choice, not a
                  failure, and the page still works: the list is right
                  underneath. */}
              {locationError && (
                <p className={styles.locateNote} role="status">
                  {locationError}
                </p>
              )}

              {/* Only once there is an origin. A radius with nothing to centre
                  on is a control that cannot do anything, and the country
                  toggle's styles carry over because it is the same kind of
                  thing: a small set of mutually exclusive choices. */}
              {origin !== null && (
                <div className={styles.toggle} role="group" aria-label="Search radius">
                  {RADII.map((metres) => (
                    <button
                      key={metres}
                      type="button"
                      className={
                        metres === radius
                          ? `${styles.segment} ${styles.segmentOn}`
                          : styles.segment
                      }
                      aria-pressed={metres === radius}
                      onClick={() => {
                        setRadius(metres);
                        setFocus("radius");
                      }}
                    >
                      {metres / 1000} km
                    </button>
                  ))}
                </div>
              )}
            </div>

            
            <RouteLegend
              routes={drawnRoutes}
              loading={roadsLoading}
              failed={roadsFailed}
              onLoadRoads={loadRoads}
            />

            {/* An equivalent, not a disclaimer. A canvas cannot be read out,
                and what makes this page accessible is that the same cinemas
                are listed below as links. */}
            <p className={styles.note}>
              The globe shows every cinema {sentence ? `in ${sentence}` : "in both countries"}.
                                         Pick a city to list its cinemas below as links.
            </p>
          </div>
        </div>
      </section>

      <div className={styles.page}>
                {origin !== null && (
          <section className={styles.group}>
            <h2 className={styles.groupTitle}>
              Within {radius / 1000} km of {origin.label}
            </h2>

            {withinLoading && (
              <div className={styles.skeletons} aria-busy aria-label="Searching nearby">
                <Skeleton height="96px" />
              </div>
            )}

            {withinError && (
              <ErrorState error={withinError} title="Could not search near you." />
            )}

            {/* The empty case is the one this endpoint was built for, so it
                gets the sentence and the card rather than a shrug. The server
                already clamps, so radiusMetres is what was actually searched
                and not what was asked for. */}
            {within && within.cinemas.length === 0 && (
              <>
                <EmptyState
                  title={`No cinemas within ${Math.round(within.radiusMetres / 1000)} km.`}
                />
                {within.nearestOutside && (
                  <ul className={styles.cards}>
                    <li>
                      <CinemaCard
                        cinema={within.nearestOutside}
                        metres={within.nearestOutside.distanceMetres}
                        fromPriceMinor={within.nearestOutside.fromPriceMinor}
                      />
                    </li>
                  </ul>
                )}
              </>
            )}

            {within && within.cinemas.length > 0 && (
              <ul className={styles.cards}>
                {within.cinemas.map((cinema) => (
                  <li key={cinema.id}>
                    <CinemaCard
                      cinema={cinema}
                      metres={cinema.distanceMetres}
                      fromPriceMinor={cinema.fromPriceMinor}
                    />
                  </li>
                ))}
              </ul>
            )}
          </section>
        )}

        {loading && (
          <div className={styles.skeletons} aria-busy aria-label="Loading cinemas">
            <Skeleton height="96px" />
            <Skeleton height="96px" />
          </div>
        )}

        {error && (
          <ErrorState error={error} title="Could not load cinemas." onRetry={reload} />
        )}

        {data && visible.length === 0 && (
          <EmptyState title={`No cinemas in ${city ?? sentence ?? "the dataset"} yet.`} />
        )}

                {/* Nothing chosen: forty cities rather than 980 cinemas. The cinemas
            appear once somebody picks a city. */}
        {city === null && (
          <CityIndex cities={cities} market={market} onPick={chooseCityRow} />
        )}

        {city !== null &&
          sections.map((section) => (
            <Fragment key={section.id}>
              {/* The country heading exists only when the page is showing more
                  than one country. With a country chosen the h1 already names
                  it and a second heading saying the same word is noise. */}
              {market === null && section.cities.length > 0 && (
                <h2 className={styles.countryTitle}>{section.label}</h2>
              )}

              {section.cities.map(([name, cinemas]) => (
                <section key={`${section.id}-${name}`} className={styles.group}>
                  {market === null ? (
                    <h3 className={styles.groupTitle}>{name}</h3>
                  ) : (
                    <h2 className={styles.groupTitle}>{name}</h2>
                  )}

                  <ul className={styles.cards}>
                    {cinemas.map((cinema) => (
                      <li key={cinema.id}>
                        <CinemaCard cinema={cinema} />
                      </li>
                    ))}
                  </ul>
                </section>
              ))}

            </Fragment>
          ))}
      </div>
    </>
  );
}

/* The opening list. Forty rows from /api/cinemas/cities instead of every
   cinema, so nothing is listed until somebody chooses. Biggest first, with the
   count on the card, so the order says what it is: cinemas in the dataset,
   not a ranking of cities. A city is a button rather than a link because
   choosing one changes this page instead of leaving it. */
function CityIndex({
  cities,
  market,
  onPick,
}: {
  cities: CinemaCity[];
  market: Market | null;
  onPick: (row: CinemaCity) => void;
}) {
  const groups = MARKETS.filter((m) => market === null || m.id === market)
    .map((m) => ({
      id: m.id,
      label: m.label,
      rows: cities
        .filter((row) => row.country === m.id)
        .sort((a, b) => b.cinemaCount - a.cinemaCount || a.city.localeCompare(b.city)),
    }))
    .filter((group) => group.rows.length > 0);

  return (
    <>
      {groups.map((group) => (
        <Fragment key={group.id}>
          {market === null && <h2 className={styles.countryTitle}>{group.label}</h2>}
          <ul className={styles.cards}>
            {group.rows.map((row) => (
              <li key={`${row.country}-${row.state}-${row.city}`}>
                <button
                  type="button"
                  className={`${styles.card} ${styles.cardButton}`}
                  onClick={() => onPick(row)}
                >
                  <span className={styles.cardName}>{row.city}</span>
                  <span className={styles.cardMeta}>
                    <span>
                      {row.cinemaCount} {row.cinemaCount === 1 ? "cinema" : "cinemas"}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        </Fragment>
      ))}
    </>
  );
}

/* metres and fromPriceMinor are both optional rather than three components,
   because the only difference between a radius result and a city one is how
   much is known about it.

   fromPriceMinor is checked with != null on purpose, which catches both. Null
   is the server saying this cinema has no screening in the from-price window;
   undefined is this caller not having asked. They are different facts and they
   render the same, which is the one case where collapsing them is right:
   there is no price to show either way. */
function CinemaCard({
  cinema,
  metres,
  fromPriceMinor,
}: {
  cinema: Cinema;
  metres?: number;
  fromPriceMinor?: number | null;
}) {
  return (
    <Link href={`/cinemas/${cinema.id}`} className={styles.card}>
      <span className={styles.cardName}>{cinema.name}</span>

      <span className={styles.cardMeta}>
        <span>
          {cinema.screenCount} {cinema.screenCount === 1 ? "screen" : "screens"}
        </span>
        {fromPriceMinor != null && (
          <span className={`${styles.badge} tnum`}>
            from {money(fromPriceMinor, cinema.currency)}
          </span>
        )}
        {metres !== undefined && (
          <span className={`${styles.badge} tnum`}>{distance(metres)}</span>
        )}
      </span>

      <span className={styles.cardWhere}>
        {cinema.city}, {cinema.state}
      </span>
    </Link>
  );
}