"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { HoldCountdown } from "@/components/HoldCountdown";
import styles from "@/css/CheckoutScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { api } from "@/services/apiClient";
import { ApiError } from "@/services/problemDetails";
import { dayAndTime, money, seatNames } from "@/utils/format";

export function CheckoutScreen() {
  const router = useRouter();
  const holdId = String(useParams().holdId);
  const { user } = useAuth();

  /* One key per set of inputs, reused for every Confirm press that sends the
     same ones. A retry has to carry the same key as the attempt it is
     retrying, or the server sees two unrelated requests.

     Keyed on the email as well as the hold, because the server's request hash
     covers holdId, account and email. Keep the old key after someone corrects
     a typo and the hash no longer matches, so a legitimate second attempt
     comes back as key reuse and the booking can never be completed. Minting a
     new key when the inputs change is what keeps Confirm working.

     A ref rather than state because it is never rendered, and read at submit
     rather than during render so typing cannot churn it mid-flight. */
  const keyRef = useRef<{ id: string; key: string } | null>(null);
  function keyFor(address: string): string {
    const id = `${holdId}|${address}`;
    if (keyRef.current?.id !== id) {
      keyRef.current = { id, key: crypto.randomUUID() };
    }
    return keyRef.current.key;
  }

  const [email, setEmail] = useState("");
  const [expired, setExpired] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const holdQuery = useApi(() => api.getHold(holdId), `hold:${holdId}`);
  const showtimes = useApi(() => api.listShowtimes(), "showtimes");

  const hold = holdQuery.data;
  const showtime = showtimes.data?.find((s) => s.id === hold?.showtimeId);

  /* Names and plurals come from the hold, not the showtime, because a hold can
     cover one seat or six and the copy has to read correctly either way. */
  const seatList = hold ? seatNames(hold.seats) : "";
  const count = hold?.seats.length ?? 0;
  const noun = count === 1 ? "seat" : "seats";
  const verb = count === 1 ? "is" : "are";

  /* Expired either because the timer ran out in front of us, or because the
     hold was already past it when the page loaded. */
  const isOver = expired || hold?.status === "EXPIRED";

   async function confirm() {
    setBusy(true);
    setProblem(null);
    try {
      /* Nothing sent when signed in. The booking is attributed by user_id and
         the account already holds the address, so writing a copy onto the row
         would be a second place for it to live and go stale. */
      const address = user ? "" : email.trim().toLowerCase();

      const booking = await api.createBooking(
        holdId,
        keyFor(address),
        address || undefined,
      );
      router.push(`/bookings/${booking.bookingReference}`);
    } catch (err) {
      if (err instanceof ApiError && err.is("hold-expired")) {
        setExpired(true);
      } else if (err instanceof ApiError && err.is("idempotency-key-reused")) {
        /* Should be unreachable, since keyFor mints a new key whenever the
           inputs change. Named anyway: the generic message would send someone
           into a retry loop that cannot succeed. */
        setProblem("Something about this booking changed. Press Confirm again.");
      } else if (err instanceof ApiError && err.is("idempotency-key-in-progress")) {
        /* The previous press is still being processed. Not a failure, so this
           says so rather than suggesting something went wrong. */
        setProblem("Still confirming. Give it a second and press Confirm again.");
      } else {
        setProblem("Could not confirm. Try again.");
      }
      setBusy(false);
    }
  }

  async function release() {
    setBusy(true);
    try {
      await api.releaseHold(holdId);
    } catch {
      /* Releasing is a courtesy. If it fails the hold lapses on its own, so
         there is nothing useful to tell the user. */
    }
    router.push(hold ? `/showtimes/${hold.showtimeId}` : "/");
  }

  if (holdQuery.loading) {
    return (
      <div className={styles.page}>
        <div className={styles.skeleton} />
      </div>
    );
  }

  if (holdQuery.error || !hold) {
    return (
      <div className={styles.page}>
        <div className={styles.panel}>
          <p>That hold could not be found.</p>
        </div>
        <Link href="/" className={styles.back}>
          Back to showtimes
        </Link>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <HoldCountdown
        key={hold.holdId}
        ttlSeconds={hold.ttlSeconds}
        expiresAt={hold.expiresAt}
        seatName={seatList}
        onExpire={() => setExpired(true)}
      />

      <h1 className={styles.title}>
        {isOver ? `Your hold on ${seatList} ran out` : `Confirm your ${noun}`}
      </h1>

      {isOver ? (
        <div className={styles.panel}>
          <p className={styles.explain}>
            Holds last five minutes so nobody is kept waiting on a seat that never
            gets booked. {seatList} {verb} back on sale and {verb} still free a
            moment ago, so it is worth another try.
          </p>
          <Link href={`/showtimes/${hold.showtimeId}`} className={styles.primary}>
            Back to seats
          </Link>
          <Link href="/" className={styles.secondary}>
            Choose a different showtime
          </Link>
        </div>
      ) : (
        <>
          <dl className={styles.summary}>
            {showtime && (
              <>
                <Row label="Film" value={showtime.filmTitle} />
                <Row label="When" value={dayAndTime(showtime.startsAt)} />
                <Row label="Screen" value={showtime.screenName} />
              </>
            )}

            {/* A line per seat with its own price. Three seats across three
                tiers have three different prices, so one combined line would
                leave the total unexplained. */}
            {hold.seats.map((seat) => (
              <Row
                key={seat.seatId}
                label={count === 1 ? "Seat" : ""}
                value={`${seat.rowLabel}${seat.seatNumber} · ${seat.tier.toLowerCase()} · ${money(seat.priceMinor, hold.currency)}`}
              />
            ))}

            {/* The hold's own total, not the showtime's price. The showtime
                carries the cheapest tier's price, so using it here undercharged
                every Premium and Executive seat. */}
            <div className={styles.total}>
              <dt>Total</dt>
              <dd className={`${styles.totalValue} tnum`}>
                {money(hold.totalMinor, hold.currency)}
              </dd>
            </div>
          </dl>

          {user ? (
            <p className={styles.account}>
              Booking as {user.displayName}. This will appear in your bookings
              and the confirmation goes to {user.email}.
            </p>
          ) : (
            <div className={styles.field}>
              <label className={styles.fieldLabel} htmlFor="email">
                Email for your confirmation
                <span className={styles.optional}>optional</span>
              </label>
              <input
                id="email"
                className={styles.input}
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                maxLength={254}
                placeholder="you@example.com"
                disabled={busy}
              />
              <p className={styles.fieldHint}>
                Leave it blank and your booking reference on the next screen is
                the only record you get, so save it. An address here is not an
                account: nobody has verified it, so it is never used to decide
                who a booking belongs to.
              </p>
            </div>
          )}

          {problem && <p className={styles.problem}>{problem}</p>}

          <button
            type="button"
            className={styles.confirm}
            onClick={confirm}
            disabled={busy}
          >
            {busy
              ? "Confirming..."
              : `Confirm booking · ${money(hold.totalMinor, hold.currency)}`}
          </button>

          <button
            type="button"
            className={styles.release}
            onClick={release}
            disabled={busy}
          >
            Release {seatList} and go back
          </button>
        </>
      )}
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className={styles.row}>
      <dt className={styles.rowLabel}>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}
