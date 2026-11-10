"use client";
import Link from "next/link";
import { useParams } from "next/navigation";

import { EmptyState } from "@/components/EmptyState";
import { ErrorState } from "@/components/ErrorState";
import { Skeleton } from "@/components/Skeleton";
import styles from "@/css/ShowtimesScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { api } from "@/services/apiClient";
import type { Showtime } from "@/types/api";
import { clockTimeAt, dayAt, money, runtime } from "@/utils/format";

export function ShowtimesScreen() {
  /* From the route, the same way SeatSelectionScreen reads its showtime id. */
  const cinemaId = Number(useParams().cinemaId);

  /* Two requests rather than one. The alternative is the showtimes response
     carrying its cinema on every row, which repeats the same building twelve
     times to save a round trip, and the two reads are independent so they run
     in parallel. The cinema is also what the page needs first: without its
     timezone, every time on this screen would be in the viewer's zone. */
  const cinema = useApi(() => api.getCinema(cinemaId), `cinema-${cinemaId}`);
  const showtimes = useApi(() => api.listShowtimes(cinemaId), `showtimes-${cinemaId}`);

  /* The API returns a flat list; the screen shows them grouped under each film. */
  const byFilm = new Map<string, Showtime[]>();
  for (const showtime of showtimes.data ?? []) {
    byFilm.set(showtime.filmTitle, [...(byFilm.get(showtime.filmTitle) ?? []), showtime]);
  }

  const zone = cinema.data?.timezone;

  /* "Today" is a question about where the cinema is, not where the viewer is.
     At 23:30 in Liverpool it is already tomorrow in Jaipur, and a heading that
     says otherwise contradicts the times underneath it. */
  const today =
    zone && showtimes.data?.length
      ? dayAt(showtimes.data[0].startsAt, zone)
      : null;

  const error = cinema.error ?? showtimes.error;
  const loading = cinema.loading || showtimes.loading;

  return (
    <div className={styles.page}>
      <p className={styles.back}>
        <Link href="/">All cinemas</Link>
      </p>

      <h1 className={styles.title}>{cinema.data?.name ?? "What's on"}</h1>
      <p className={styles.subtitle}>
        {cinema.data
          ? [cinema.data.city, today].filter(Boolean).join(" · ")
          : "Loading"}
      </p>

      {loading && (
        <div className={styles.skeletons} aria-busy aria-label="Loading showtimes">
          <Skeleton height="112px" />
          <Skeleton height="112px" />
        </div>
      )}

      {error && (
        <ErrorState
          error={error}
          title="Could not load showtimes."
          onRetry={() => {
            cinema.reload();
            showtimes.reload();
          }}
        />
      )}

      {/* A real cinema with nothing scheduled, which is a different answer from
          a cinema that does not exist: that one arrives as a 404 and renders
          above as an error. */}
      {showtimes.data?.length === 0 && (
        <EmptyState
          title="Nothing on here today."
          detail="Try another cinema, or check back tomorrow."
        >
          <Link href="/">All cinemas</Link>
        </EmptyState>
      )}

      {zone &&
        [...byFilm.entries()].map(([film, forFilm]) => (
          <section key={film} className={styles.film}>
            <h2 className={styles.filmTitle}>{film}</h2>
            <p className={styles.filmMeta}>{runtime(forFilm[0].runtimeMinutes)}</p>

            <ul className={styles.times}>
              {forFilm.map((showtime) => (
                <li key={showtime.id}>
                  <ShowtimeCard showtime={showtime} timeZone={zone} />
                </li>
              ))}
            </ul>
          </section>
        ))}
    </div>
  );
}

/* Lives here rather than in components/ because nothing else renders one.
   Move it if that changes. */
function ShowtimeCard({
  showtime,
  timeZone,
}: {
  showtime: Showtime;
  timeZone: string;
}) {
  const soldOut = showtime.seatsAvailable === 0;
  const nearlyFull = !soldOut && showtime.seatsAvailable <= 12;
  const filled = 1 - showtime.seatsAvailable / showtime.seatsTotal;

  const body = (
    <>
      <span className={soldOut ? styles.timeGone : styles.time}>
        {clockTimeAt(showtime.startsAt, timeZone)}
      </span>

      <span className={styles.meta}>
        <span>{showtime.screenName}</span>
        <span className="tnum">{money(showtime.priceMinor, showtime.currency)}</span>
      </span>

      <span className={styles.gauge} aria-hidden>
        <span className={styles.gaugeFill} style={{ width: `${filled * 100}%` }} />
      </span>

      <span className={nearlyFull ? styles.seatsLow : styles.seats}>
        {soldOut
          ? "Sold out"
          : nearlyFull
            ? `Nearly full · ${showtime.seatsAvailable} left`
            : `${showtime.seatsAvailable} of ${showtime.seatsTotal} left`}
      </span>
    </>
  );

  if (soldOut) {
    return <div className={styles.cardGone}>{body}</div>;
  }

  return (
    <Link
      href={`/showtimes/${showtime.id}`}
      className={nearlyFull ? styles.cardLow : styles.card}
    >
      {body}
    </Link>
  );
}