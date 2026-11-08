"use client";

import Link from "next/link";
import { useMemo } from "react";

import { useAuth } from "@/components/AuthProvider";
import styles from "@/css/AccountScreen.module.css";
import { useApi } from "@/hooks/useApi";
import { api } from "@/services/apiClient";
import { dayAndTime, money, seatNames } from "@/utils/format";

export function MyBookingsScreen() {
  const { user } = useAuth();

  /* Keyed on the account, not just the path. Signing out and in as someone
     else on the same tab would otherwise show the previous person's list
     until the fetch landed. */
  const key = useMemo(() => `my-bookings:${user?.id ?? "none"}`, [user?.id]);
  const { data, error, loading, reload } = useApi(() => api.myBookings(), key);

  if (loading) {
    return (
      <div className={styles.page}>
        <div className={styles.skeleton} />
      </div>
    );
  }

  if (error) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>My bookings</h1>
        <div className={styles.panel}>
          <p>Could not load your bookings.</p>
          <div className={styles.actions}>
            <button type="button" className={styles.quiet} onClick={reload}>
              Try again
            </button>
          </div>
        </div>
        <Link href="/account" className={styles.back}>
          ← Account
        </Link>
      </div>
    );
  }

  const bookings = data ?? [];

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>My bookings</h1>
      <p className={styles.lede}>
        Newest first. Every booking stays readable by its reference, so these
        links work whether or not you are signed in.
      </p>

      {bookings.length === 0 ? (
        <div className={styles.empty}>
          <p>Nothing booked on this account yet.</p>
        </div>
      ) : (
        <ul className={styles.list}>
          {bookings.map((booking) => (
            <li key={booking.bookingReference}>
              <Link
                href={`/bookings/${booking.bookingReference}`}
                className={styles.item}
              >
                <div className={styles.itemTop}>
                  <span className={styles.itemTitle}>{booking.filmTitle}</span>
                  <span className={`${styles.itemTotal} tnum`}>
                    {money(booking.totalPaidMinor, booking.currency)}
                  </span>
                </div>
                <p className={styles.itemMeta}>
                  {dayAndTime(booking.startsAt)} · {booking.screenName} ·{" "}
                  {booking.seats.length === 1 ? "seat" : "seats"}{" "}
                  {seatNames(booking.seats)}
                </p>
                <p className={styles.itemRef}>{booking.bookingReference}</p>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {/* The one thing a user will otherwise think is a bug. Someone who booked
          as a guest and then signed up with the same address finds nothing
          here and concludes their booking is gone. It is not: it is reachable
          by its reference, and this list matches on the account only. */}
      <p className={styles.footnote}>
        Bookings made as a guest do not appear here, even if you used this email
        address at checkout. An address nobody has verified is not proof of
        ownership, so matching on it would let anyone read a stranger&apos;s
        history by signing up with their email. Guest bookings stay reachable by
        their reference.
      </p>

      <Link href="/account" className={styles.back}>
        ← Account
      </Link>
    </div>
  );
}