"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { PriceRateNotice } from "@/components/PriceRateNotice";
import { useAuth } from "@/components/AuthProvider";
import { ConflictNotice } from "@/components/ConflictNotice";
import { SeatLegend } from "@/components/SeatLegend";
import { SeatMap } from "@/components/SeatMap";
import styles from "@/css/SeatSelectionScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { api } from "@/services/apiClient";
import { MAX_SEATS_MEMBER } from "@/services/apiConfig";
import { ApiError } from "@/services/problemDetails";
import type { Seat, UnavailableSeat } from "@/types/api";
import { extendsRun, isRunEnd } from "@/utils/adjacency";
import { dayAndTime, money, seatNames } from "@/utils/format";

/* Reading order, so the summary lists A2 before A3 however they were tapped. */
const byPosition = (a: Seat, b: Seat) =>
  a.rowLabel.localeCompare(b.rowLabel) || a.seatNumber - b.seatNumber;

export function SeatSelectionScreen() {
  const router = useRouter();
  const showtimeId = Number(useParams().showtimeId);
  const { user, maxSeats } = useAuth();

  const [selected, setSelected] = useState<Seat[]>([]);
  const [holding, setHolding] = useState(false);
  const [lost, setLost] = useState<UnavailableSeat[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  /* Guidance, not failure: the cap, or an explanation of why the selection
     just restarted. Styled as a warning rather than an error. */
  const [note, setNote] = useState<string | null>(null);

  const seats = useApi(() => api.getSeatMap(showtimeId), `seats:${showtimeId}`);
  const showtimeQuery = useApi(
    () => api.getShowtime(showtimeId),
    `showtime:${showtimeId}`,
  );
  const showtime = showtimeQuery.data;

  /* The map is the authority on what is free. Whenever it reloads, drop any
     chosen seat that is no longer available. Without this, a seat someone else
     took stays rendered as selected, because Seat.tsx puts selected above held
     in its state ladder, and the user can submit a seat that has gone. */
  const live = seats.data?.seats;
  useEffect(() => {
    if (!live) return;
    setSelected((current) => {
      const stillFree = new Set(
        live.filter((s) => s.status === "AVAILABLE").map((s) => s.id),
      );
      const kept = current.filter((s) => stillFree.has(s.id));
      /* Same array back when nothing changed, or this re-renders forever. */
      return kept.length === current.length ? current : kept;
    });
  }, [live]);

  /* Signing out mid-selection drops the cap from six to three, so trim from
     the far end rather than leaving a selection the API would now refuse. */
  useEffect(() => {
    setSelected((current) =>
      current.length <= maxSeats ? current : current.slice(0, maxSeats),
    );
  }, [maxSeats]);

  const selectedIds = new Set(selected.map((s) => s.id));
  const subtotal = selected.reduce((sum, seat) => sum + seat.priceMinor, 0);
  const currency = selected[0]?.currency ?? showtime?.currency ?? "GBP";
  const count = selected.length;

  function toggle(seat: Seat) {
    setLost([]);
    setProblem(null);

    if (selectedIds.has(seat.id)) {
      /* Only the ends come off, because removing from the middle would split
         the run in two. Tapping a middle seat starts again from there, the
         same thing tapping any non-adjacent seat does. */
      if (isRunEnd(selected, seat)) {
        setSelected(selected.filter((s) => s.id !== seat.id));
        setNote(null);
      } else {
        setSelected([seat]);
        setNote(
          `Started again at ${seat.rowLabel}${seat.seatNumber}. Seats have to stay in one unbroken row.`,
        );
      }
      return;
    }
    

    if (!extendsRun(selected, seat)) {
      /* Not next to what is already chosen. Starting a new selection there is
         friendlier than refusing the tap, and it is what the person almost
         certainly meant if they tapped somewhere else entirely. */
      setSelected([seat]);
      setNote(
        selected.length > 0
          ? `Started again at ${seat.rowLabel}${seat.seatNumber}. Seats have to be next to each other in one row.`
          : null,
      );
      return;
    }

    if (selected.length >= maxSeats) {
      setNote(
        user
          ? `${maxSeats} seats per booking. Remove one from either end to swap it.`
          : `${maxSeats} seats per booking as a guest. Sign in for up to ${MAX_SEATS_MEMBER}, or remove one from either end to swap it.`,
      );
      return;
    }

    setNote(null);
    setSelected([...selected, seat].sort(byPosition));
  }

  async function hold() {
    if (selected.length === 0) return;

    setHolding(true);
    setLost([]);
    setProblem(null);

    try {
      const created = await api.createHold(
        showtimeId,
        selected.map((s) => s.id),
      );
      router.push(`/hold/${created.holdId}`);
    } catch (err) {
      /* Losing a seat between rendering the map and tapping Hold is ordinary,
         not an error page. The 409 names which seats went, so those are dropped
         from the selection and the rest are kept. */
      if (err instanceof ApiError && err.is("seats-unavailable")) {
        const unavailable =
          (err.problem.unavailableSeats as UnavailableSeat[] | undefined) ?? [];
        const lostIds = new Set(unavailable.map((s) => s.seatId));

        /* A 409 that does not name the seats should not happen against our own
           API, but showing nothing at all is the worst possible response. */
        if (unavailable.length === 0) {
          setProblem("Some of those seats just went. The map has been refreshed.");
        }

        setLost(unavailable);
        setSelected((current) => current.filter((s) => !lostIds.has(s.id)));
        seats.reload();
      } else {
        setProblem("Could not hold those seats. Try again.");
      }
      setHolding(false);
    }
  }

  return (
    <div className={styles.page}>
            {/* Back to the cinema you came from, not to the top of the site. The
          showtime carries its cinemaId, which is the reason that field exists:
          this page is reachable by URL with nothing else in it. */}
      <Link
        href={showtime ? `/cinemas/${showtime.cinemaId}` : "/"}
        className={styles.back}
      >
        ← {showtime ? "Back to this cinema" : "All cinemas"}
      </Link>

      <h1 className={styles.title}>{showtime?.filmTitle ?? "\u00a0"}</h1>
      {showtime && (
        <p className={styles.meta}>
          {dayAndTime(showtime.startsAt)} · {showtime.screenName} · from{" "}
          {money(showtime.priceMinor, showtime.currency)}
        </p>
      )}

      {/* The "from" figure above is already the adjusted one, so without this
          a customer sees a price with no reason attached. */}
      {seats.data && <PriceRateNotice rules={seats.data.priceRules} />}

      <div className={styles.layout}>
        <div>
          {seats.loading && <div className={styles.skeleton} />}

          {seats.error && (
            <div className={styles.panel}>
              <p>Could not load the seat map.</p>
              <button type="button" onClick={seats.reload} className={styles.retry}>
                Try again
              </button>
            </div>
          )}

          {seats.data && (
            <>
                            <SeatMap
                seats={seats.data.seats}
                aisleAfter={seats.data.aisleAfter}
                selectedIds={selectedIds}
                pending={holding}
                onSelect={toggle}
              />
              <SeatLegend />
            </>
          )}
        </div>

        <aside className={styles.aside}>
          {lost.length > 0 && <ConflictNotice seats={lost} keptCount={count} />}

          <div className={styles.summary}>
            <p className={styles.kicker}>
              {count === 0
                ? "YOUR SEATS"
                : `YOUR SEATS · ${count} of ${maxSeats}`}
            </p>

            {count === 0 ? (
              <p className={styles.empty}>No seats chosen yet.</p>
            ) : (
              <>
                <ul className={styles.picked}>
                  {selected.map((seat) => (
                    <li key={seat.id} className={styles.pickedItem}>
                      <span className={styles.pickedName}>
                        {seat.rowLabel}
                        {seat.seatNumber}
                      </span>
                      <span className={styles.pickedMeta}>{seat.tierName}</span>
                      <span className={`${styles.pickedPrice} tnum`}>
                        {money(seat.priceMinor, seat.currency)}
                      </span>
                      <button
                        type="button"
                        className={styles.remove}
                        onClick={() => toggle(seat)}
                        /* Disabled in the middle of a run, because removing it
                           would split the selection in two. The ends stay
                           removable, which is enough to shrink a run from
                           either side. */
                        disabled={holding || !isRunEnd(selected, seat)}
                        aria-label={`Remove seat ${seat.rowLabel}${seat.seatNumber}`}
                      >
                        ×
                      </button>
                    </li>
                  ))}
                </ul>

                <div className={styles.subtotal}>
                  <span>Total</span>
                  <span className="tnum">{money(subtotal, currency)}</span>
                </div>
              </>
            )}

            {note && (
              <p className={styles.cap} role="status">
                {note}
              </p>
            )}

            {problem && <p className={styles.failed}>{problem}</p>}

            <button
              type="button"
              className={styles.hold}
              disabled={count === 0 || holding}
              onClick={hold}
            >
              {holding
                ? "Holding..."
                : count === 0
                  ? "Hold seats"
                  : `Hold ${seatNames(selected)} for 5 minutes`}
            </button>

            <p className={styles.note}>
              Seats have to be next to each other in one row, so groups sit
              together. Holding keeps them for five minutes while you check out.
              All of them or none: if one goes while you are choosing, nothing is
              held. Nothing is charged until you confirm.
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}