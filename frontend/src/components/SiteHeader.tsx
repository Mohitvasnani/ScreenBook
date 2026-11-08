"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import styles from "@/css/SiteHeader.module.css";
import { AUTH_ENABLED, IS_MOCK } from "@/services/apiConfig";

/* Wordmark, mode badge and nav. Sticky, so the mode badge stays visible: on the
   seat map you are making a decision about money, and whether this is a demo or
   the real API should never be something you have to scroll up to recheck.

   ModeBanner is rendered above this in app/layout.tsx rather than inside it,
   so the banner scrolls away and only the bar stays. */
export function SiteHeader() {
  const pathname = usePathname();
  const onRace = pathname.startsWith("/race");
  /* No point offering a way in from the way in. Without this, signing in from
     /login would set ?next=/login and land you back on the form. */
  const onAuthPage = pathname === "/login" || pathname === "/signup";

  const { user, ready, logOut } = useAuth();
  const [signingOut, setSigningOut] = useState(false);

  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    /* passive: true tells the browser this listener will not call
       preventDefault, so scrolling is never blocked waiting on it. The read is
       window.scrollY only, which costs nothing. */
    const onScroll = () => setScrolled(window.scrollY > 8);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  async function signOut() {
    setSigningOut(true);
    try {
      await logOut();
      /* No navigation here on purpose. Logging out of the showtimes list should
         leave you on the showtimes list. Whether the current page is still
         viewable is the route guard's question, not the header's, and it
         answers it by watching the same user that just became null. */
    } finally {
      setSigningOut(false);
    }
  }

  return (
    <header className={`${styles.header} ${scrolled ? styles.scrolled : ""}`}>
      <div className={styles.bar}>
        <Link href="/" className={styles.wordmark}>
          Screen<span className={styles.wordmarkAccent}>Book</span>
        </Link>

        <span className={IS_MOCK ? styles.badgeDemo : styles.badgeLive}>
          <span aria-hidden className={styles.badgeDot} />
          {IS_MOCK ? "DEMO" : "LIVE"}
        </span>

        <nav aria-label="Main" className={styles.nav}>
          <Link href="/" className={onRace ? styles.navLink : styles.navLinkActive}>
            Showtimes
          </Link>
          <Link href="/race" className={onRace ? styles.navLinkActive : styles.navLink}>
            <svg
              aria-hidden
              width="15"
              height="15"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M9 3h6M10 3v6L4.5 19a1.5 1.5 0 0 0 1.3 2h12.4a1.5 1.5 0 0 0 1.3-2L14 9V3" />
            </svg>
            Race demo
          </Link>
        </nav>

        {/* Nothing at all in mock mode: there is no session to be in, so a
            Sign in link would lead to a screen explaining that it does not
            work here. */}
        {AUTH_ENABLED && (
          <div className={styles.account}>
            {/* Renders nothing until the startup check settles. Before it does,
                user is null for everyone, so anything rendered here would be a
                guess, and the guess shows "Sign in" to people who are already
                signed in. An empty slot for one beat is the honest state. */}
            {!ready ? null : user ? (
              <>
                <Link href="/account" className={styles.accountName}>
                  {user.displayName}
                </Link>
                <button
                  type="button"
                  className={styles.logOut}
                  onClick={signOut}
                  disabled={signingOut}
                >
                  {signingOut ? "Signing out..." : "Log out"}
                </button>
              </>
            ) : onAuthPage ? null : (
              <Link
                href={`/login?next=${encodeURIComponent(pathname)}`}
                className={styles.signIn}
              >
                Sign in
              </Link>
            )}
          </div>
        )}
      </div>
    </header>
  );
}