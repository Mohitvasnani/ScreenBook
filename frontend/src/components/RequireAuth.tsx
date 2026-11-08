"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, type ReactNode } from "react";

import { useAuth } from "@/components/AuthProvider";
import styles from "@/css/AccountScreen.module.css";
import { AUTH_ENABLED } from "@/services/apiConfig";

/**
 * Wraps a screen that has nothing to show a guest.
 *
 * A client component rather than Next middleware, and that is a real choice
 * with a cost. Middleware would run on the server and avoid the empty beat
 * below, but it can only see that a cookie exists, not that it is valid: the
 * signing secret lives in the Spring service and deliberately never reaches
 * the frontend. So middleware would gate on an unverified guess, and the API
 * would still have to reject the request properly afterwards.
 *
 * That makes this guard a UX device and nothing more. It exists so a screen
 * does not fire requests that are certain to 401, not to protect anything.
 * The protection is `requestMatchers("/api/me/**").authenticated()` on the
 * server, which is the only place it can honestly live.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, ready } = useAuth();
  const pathname = usePathname();
  const router = useRouter();

  useEffect(() => {
    if (!AUTH_ENABLED) return;
    /* "Not known yet" is not "not signed in". Redirecting before the startup
       check lands would bounce everyone, including people with a valid
       session. */
    if (!ready || user) return;

    router.replace(`/login?next=${encodeURIComponent(pathname)}`);
  }, [ready, user, pathname, router]);

  if (!AUTH_ENABLED) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>Not available in demo mode</h1>
        <p className={styles.lede}>
          There are no accounts here, so there is nothing for this page to show.
          Sending you to a sign-in form that also does not work would be worse.
        </p>
        <Link href="/" className={styles.back}>
          ← All showtimes
        </Link>
      </div>
    );
  }

  if (!ready) {
    return (
      <div className={styles.page}>
        <div className={styles.skeleton} />
      </div>
    );
  }

  /* The redirect is in flight. Rendering children here would start their
     requests as a guest and collect a 401 for each one on the way out. */
  if (!user) return null;

  return <>{children}</>;
}