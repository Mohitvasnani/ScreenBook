"use client";

import { useState } from "react";
import { seatNames } from "@/utils/format";
import { RequestLane, type LaneResult } from "@/components/RequestLane";
import styles from "@/css/RaceScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { IS_MOCK } from "@/services/apiConfig";
import { api } from "@/services/apiClient";
import { ApiError } from "@/services/problemDetails";
import type { Seat } from "@/types/api";
import { clockTime, seatLabel } from "@/utils/format";

type Attempt = LaneResult & { holdId?: string };

export function RaceScreen() {
    /* This page is about one seat at one screening, so which building it is in
     proves nothing either way. It takes the first cinema rather than adding a
     third select: the showtime and seat pickers are the controls that matter
     here, and a cinema picker would just be more to read. */
     const cinemas = useApi(() => api.listCinemas(), "race-cinemas");
     const cinemaId = cinemas.data?.[0]?.id;
   
     const showtimes = useApi(
       () => (cinemaId ? api.listShowtimes(cinemaId) : Promise.resolve(null)),
       `race-showtimes:${cinemaId ?? "none"}`,
     );

  const [showtimeId, setShowtimeId] = useState<number | null>(null);
  const showtime = showtimes.data?.find((s) => s.id === showtimeId) ?? showtimes.data?.[0];

  const seats = useApi(
    () => (showtime ? api.getSeatMap(showtime.id) : Promise.resolve(null)),
    `race-seats:${showtime?.id ?? "none"}`,
  );
  const free: Seat[] = (seats.data?.seats ?? []).filter((s) => s.status === "AVAILABLE");

  const [seatId, setSeatId] = useState<number | null>(null);
  const chosen = free.find((s) => s.id === seatId) ?? free[0];

  const [firing, setFiring] = useState(false);
  const [lanes, setLanes] = useState<[Attempt | null, Attempt | null]>([null, null]);

  async function fire() {
    if (!showtime || !chosen) return;

    setFiring(true);
    setLanes([null, null]);

    const attempt = async (): Promise<Attempt> => {
      const started = performance.now();
      try {
               const hold = await api.createHold(showtime.id, [chosen.id]);
        return {
          status: 201,
          outcome: "won",
          ms: performance.now() - started,
          holdId: hold.holdId,
                   body: `{"holdId":"${hold.holdId.slice(0, 8)}…","seat":"${seatNames(hold.seats)}","ttlSeconds":${hold.ttlSeconds}}`,
        };
      } catch (err) {
        const ms = performance.now() - started;
        if (err instanceof ApiError) {
          return {
            status: err.status,
            outcome: err.is("seats-unavailable") ? "lost" : "other",
            ms,
            body: `{"type":"…/${err.slug}","title":"${err.problem.title}"}`,
          };
        }
        return { status: 0, outcome: "other", ms, body: "network error" };
      }
    };

    /* Both start in the same tick. Awaiting the first before sending the
       second would serialise them and prove nothing. */
    const [a, b] = await Promise.all([attempt(), attempt()]);
    setLanes([a, b]);
    setFiring(false);

    /* Give the seat back so the demo can be run again, instead of locking it
       for five minutes after a single press. */
    for (const result of [a, b]) {
      if (result.holdId) await api.releaseHold(result.holdId).catch(() => {});
    }
    seats.reload();
  }

  const settled = lanes[0] !== null && lanes[1] !== null;
  const winners = [lanes[0], lanes[1]].filter((l) => l?.outcome === "won").length;

  return (
    <div className={styles.page}>
      <p className={styles.kicker}>FOR DEVELOPERS</p>
      <h1 className={styles.title}>The race</h1>
      <p className={styles.intro}>
        Two people tap the same seat at the same moment. This page sends both hold
        requests at once so you can watch exactly one of them win.
      </p>

      <div className={styles.controls}>
        <label className={styles.field}>
          Showtime
          <select
            className={styles.select}
            value={showtime?.id ?? ""}
            onChange={(e) => {
              setShowtimeId(Number(e.target.value));
              setSeatId(null);
            }}
          >
            {(showtimes.data ?? []).map((s) => (
              <option key={s.id} value={s.id}>
                {s.filmTitle} · {clockTime(s.startsAt)} · {s.screenName}
              </option>
            ))}
          </select>
        </label>

        <label className={styles.field}>
          Seat
          <select
            className={styles.select}
            value={chosen?.id ?? ""}
            onChange={(e) => setSeatId(Number(e.target.value))}
          >
            {free.slice(0, 40).map((s) => (
              <option key={s.id} value={s.id}>
                {seatLabel(s.rowLabel, s.seatNumber)} · currently free
              </option>
            ))}
          </select>
        </label>

        <button
          type="button"
          className={styles.fire}
          onClick={fire}
          disabled={firing || !chosen}
        >
          {firing ? "Firing..." : "Fire both requests"}
        </button>
      </div>

      <div className={styles.lanes}>
        <RequestLane name="A" result={lanes[0]} firing={firing} />
        <RequestLane name="B" result={lanes[1]} firing={firing} />
      </div>

      <div className={IS_MOCK ? styles.caveatDemo : styles.caveatLive}>
        <p className={styles.mode}>mode: {IS_MOCK ? "DEMO" : "LIVE"}</p>

        <p className={styles.caveatBody}>
          {IS_MOCK ? (
            <>
              A mock server in this browser settles the race. This proves the UI
              handles a 409. <strong>It proves nothing about database locking.</strong>{" "}
              Switch to live to test the real API.
            </>
          ) : (
            <>
              Both requests reached the real API. One 201 and one 409 means the
              database turned down the second hold. Run it a few times: the winner
              may change but the count should not.
            </>
          )}
        </p>

        {settled && (
          <p className={styles.verdict}>
            {winners === 1
              ? "Exactly one won, which is the result we want."
              : `${winners} winners. That is the bug this page exists to catch.`}
          </p>
        )}
      </div>
    </div>
  );
}