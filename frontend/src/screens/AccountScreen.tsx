"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import styles from "@/css/AccountScreen.module.css";
import { MAX_SEATS_GUEST } from "@/services/apiConfig";
import { dayAndTime } from "@/utils/format";

export function AccountScreen() {
  const router = useRouter();
  /* RequireAuth has already established there is a user, so the non-null
     assertion here is the guard's guarantee rather than an assumption. */
  const { user, maxSeats, logOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);

  if (!user) return null;

  async function signOut() {
    setSigningOut(true);
    try {
      await logOut();
      /* Goes home, unlike the header's log out which stays put. This page
         needs a session, so leaving the guard to react would send someone to
         a sign-in form immediately after they asked to leave. */
      router.replace("/");
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>{user.displayName}</h1>
      <p className={styles.lede}>
        Your account holds your bookings and raises the seat limit. Nothing else
        about booking changes: guests get the same flow.
      </p>

      <div className={styles.panel}>
        <div className={styles.row}>
          <span className={styles.rowLabel}>Name</span>
          <span>{user.displayName}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.rowLabel}>Email</span>
          <span>{user.email}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.rowLabel}>Joined</span>
          <span>{dayAndTime(user.createdAt)}</span>
        </div>
        <div className={styles.row}>
          <span className={styles.rowLabel}>Seats per booking</span>
          <span>
            {maxSeats}, up from {MAX_SEATS_GUEST} as a guest
          </span>
        </div>
      </div>

      <div className={styles.actions}>
        <Link href="/account/bookings" className={styles.primary}>
          My bookings
        </Link>
        <button
          type="button"
          className={styles.quiet}
          onClick={signOut}
          disabled={signingOut}
        >
          {signingOut ? "Signing out..." : "Log out"}
        </button>
      </div>

      <p className={styles.footnote}>
        Changing your name, email or password is not built yet, and neither is
        deleting your account. Both are listed as out of scope rather than
        hidden: an account page that offers a form which quietly does nothing is
        worse than one that says what it does not do.
      </p>

      <Link href="/" className={styles.back}>
        ← All showtimes
      </Link>
    </div>
  );
}