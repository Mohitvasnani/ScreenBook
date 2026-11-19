"use client";

import Link from "next/link";
import dynamic from "next/dynamic";
import { useMemo, useState } from "react";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Skeleton } from "@/components/Skeleton";
import styles from "@/css/CinemasScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { api } from "@/services/apiClient";
import type { Cinema, NearestCinema } from "@/types/api";
import { distance } from "@/utils/format";
import { DEFAULT_MARKET, MARKETS, marketOf, type Market } from "@/utils/markets";

/* Loaded only when this screen renders, for two reasons that happen to have
   one fix. MapLibre is around three quarters of a megabyte, and importing it
   normally would put it in the bundle for the seat map and the checkout too.
   It also needs a real browser window, so it cannot be server-rendered at all.
   ssr false is legal here because this file is a client component. */
const CinemaMap = dynamic(
  () => import("@/components/CinemaMap").then((m) => m.CinemaMap),
  { ssr: false, loading: () => null },
);

/* Five, plus All cities. A chip is a shortcut, and a page full of shortcuts
   is a list. */
const CHIP_LIMIT = 5;

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

export function CinemasScreen() {
  const { data, error, loading, reload } = useApi(() => api.listCinemas(), "cinemas");

  const [market, setMarket] = useState<Market>(DEFAULT_MARKET);
  const [city, setCity] = useState<string | null>(null);

  const [nearby, setNearby] = useState<NearestCinema[] | null>(null);
  const [locating, setLocating] = useState(false);
  const [locationError, setLocationError] = useState<string | null>(null);

  async function findNearby() {
    setLocationError(null);
    setLocating(true);
    try {
      const position = await currentPosition();
      setNearby(
        await api.nearestCinemas(
          position.coords.latitude,
          position.coords.longitude,
        ),
      );
    } catch (failure) {
      setNearby(null);
      setLocationError(geolocationMessage(failure));
    } finally {
      setLocating(false);
    }
  }

  /* Changing country clears the city in the same handler rather than in an
     effect watching market. An effect would render once with a city that is
     not in the new country, then correct itself, and the list would flash
     empty on every toggle. */
  function chooseMarket(next: Market) {
    setMarket(next);
    setCity(null);
  }

  const inMarket = useMemo(
    () => (data ?? []).filter((cinema) => marketOf(cinema) === market),
    [data, market],
  );

  /* The cities with the most cinemas, not the first five alphabetically. A
     shortcut to Agra's two screens is not worth the pixel, and ties break on
     the name so the row does not reshuffle between loads. */
  const chipCities = useMemo(() => {
    const counts = new Map<string, number>();
    for (const cinema of inMarket) {
      counts.set(cinema.city, (counts.get(cinema.city) ?? 0) + 1);
    }
    return [...counts.entries()]
      .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
      .slice(0, CHIP_LIMIT)
      .map(([name]) => name);
  }, [inMarket]);

  const visible = city === null ? inMarket : inMarket.filter((c) => c.city === city);

  /* The API returns cinemas ordered by city then name, so inserting in order
     groups them with no sort of our own. */
  const byCity = new Map<string, Cinema[]>();
  for (const cinema of visible) {
    byCity.set(cinema.city, [...(byCity.get(cinema.city) ?? []), cinema]);
  }

  const sentence = MARKETS.find((m) => m.id === market)?.inSentence ?? "the UK";

  return (
    <>
      <section className={styles.hero}>
        {/* Every cinema, never the filtered list: this component's build
            effect depends on the array it is handed, so a filtered array
            would destroy and rebuild the map on every toggle. The market
            goes in as its own prop and reaches the live map as setData and
            flyTo. */}
                <div className={styles.globe}>
          {data && data.length > 0 && (
            <CinemaMap
              cinemas={data}
              market={market}
              city={city}
              onPickCity={setCity}
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
            <h1 className={styles.title}>Find a cinema in {sentence}</h1>
            <p className={styles.subtitle}>
              Pick a city on the globe or browse the list below.
            </p>

            {/* aria-pressed rather than a radio group: these are buttons that
                change the page, not a value being submitted with a form. */}
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

            <div className={styles.chips}>
              {chipCities.map((name) => (
                <button
                  key={name}
                  type="button"
                  className={
                    name === city ? `${styles.chip} ${styles.chipOn}` : styles.chip
                  }
                  aria-pressed={name === city}
                  onClick={() => setCity(name === city ? null : name)}
                >
                  {name}
                </button>
              ))}

              <button
                type="button"
                className={
                  city === null
                    ? `${styles.chip} ${styles.chipAll} ${styles.chipOn}`
                    : `${styles.chip} ${styles.chipAll}`
                }
                aria-pressed={city === null}
                onClick={() => setCity(null)}
              >
                All cities
              </button>
            </div>

            <div className={styles.locate}>
              <button
                type="button"
                className={styles.locateButton}
                onClick={findNearby}
                disabled={locating}
              >
                {locating ? "Finding you..." : "Use my location"}
              </button>

              {/* Not an ErrorState. A declined permission is a choice, not a
                  failure, and the page still works: the city list is right
                  underneath. */}
              {locationError && (
                <p className={styles.locateNote} role="status">
                  {locationError}
                </p>
              )}
            </div>

            {/* An equivalent, not a disclaimer. A canvas cannot be read out,
                and what makes this page accessible is that the same cinemas
                are listed below as links. Saying so beats hiding the map from
                assistive tech, which would hide the tile credit with it. */}
            <p className={styles.note}>
              The globe shows every cinema in {sentence}. The list below has the
              same cinemas as links.
            </p>
          </div>
        </div>
      </section>

      <div className={styles.page}>
        {nearby && (
          <section className={styles.group}>
            <h2 className={styles.groupTitle}>Nearest to you</h2>
            {nearby.length === 0 ? (
              <EmptyState title="No cinemas found near you." />
            ) : (
              <ul className={styles.cards}>
                {nearby.map((cinema) => (
                  <li key={cinema.id}>
                    <CinemaCard cinema={cinema} metres={cinema.distanceMetres} />
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
          <EmptyState title={`No cinemas in ${city ?? sentence} yet.`} />
        )}

        {[...byCity.entries()].map(([name, cinemas]) => (
          <section key={name} className={styles.group}>
            <h2 className={styles.groupTitle}>{name}</h2>
            <ul className={styles.cards}>
              {cinemas.map((cinema) => (
                <li key={cinema.id}>
                  <CinemaCard cinema={cinema} />
                </li>
              ))}
            </ul>
          </section>
        ))}
      </div>
    </>
  );
}

/* metres is optional rather than two components, because the only difference
   between a nearby result and a city one is whether a distance is known. */
function CinemaCard({ cinema, metres }: { cinema: Cinema; metres?: number }) {
  return (
    <Link href={`/cinemas/${cinema.id}`} className={styles.card}>
      <span className={styles.cardName}>{cinema.name}</span>

      <span className={styles.cardMeta}>
        <span>
          {cinema.screenCount} {cinema.screenCount === 1 ? "screen" : "screens"}
        </span>
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