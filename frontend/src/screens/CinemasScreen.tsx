"use client";

import Link from "next/link";
import { useState } from "react";
import dynamic from "next/dynamic";
import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Skeleton } from "@/components/Skeleton";
import styles from "@/css/CinemasScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { api } from "@/services/apiClient";
import type { Cinema, NearestCinema } from "@/types/api";
import { distance } from "@/utils/format";


/* Loaded only when this screen renders, for two reasons that happen to have
   one fix. MapLibre is around three quarters of a megabyte, and importing it
   normally would put it in the bundle for the seat map and the checkout too.
   It also needs a real browser window, so it cannot be server-rendered at all.
   ssr false is legal here because this file is a client component. */
   const CinemaMap = dynamic(
    () => import("@/components/CinemaMap").then((m) => m.CinemaMap),
    { ssr: false, loading: () => <Skeleton height="320px" /> },
  );

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

  /* The API returns cinemas ordered by city then name, so inserting in order
     groups them with no sort of our own. */
  const byCity = new Map<string, Cinema[]>();
  for (const cinema of data ?? []) {
    byCity.set(cinema.city, [...(byCity.get(cinema.city) ?? []), cinema]);
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Find a cinema</h1>
      <p className={styles.subtitle}>
        {data ? `${data.length} cinemas, in two countries` : "Loading cinemas"}
      </p>

      <div className={styles.locate}>
        <button
          type="button"
          className={styles.locateButton}
          onClick={findNearby}
          disabled={locating}
        >
          {locating ? "Finding you..." : "Use my location"}
        </button>

        {/* Not an ErrorState. A declined permission is a choice, not a failure,
            and the page still works: the city list is right underneath. */}
        {locationError && (
          <p className={styles.locateNote} role="status">
            {locationError}
          </p>
        )}
      </div>
      {data && data.length > 0 && <CinemaMap cinemas={data} />}
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

      {data?.length === 0 && <EmptyState title="No cinemas yet." />}

      {[...byCity.entries()].map(([city, cinemas]) => (
        <section key={city} className={styles.group}>
          <h2 className={styles.groupTitle}>{city}</h2>
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