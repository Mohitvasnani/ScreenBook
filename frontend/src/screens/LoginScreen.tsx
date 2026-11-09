"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { useAuth } from "@/components/AuthProvider";
import styles from "@/css/AuthScreen.module.css";
import { AUTH_ENABLED } from "@/services/apiConfig";
import { ApiError } from "@/services/problemDetails";
import { safeNext } from "@/utils/redirects";

export function LoginScreen() {
  const router = useRouter();
  const next = safeNext(useSearchParams().get("next"));
  const { user, ready, logIn } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  /* Already signed in, so there is nothing to do here. Waits for ready,
     because before the startup check lands user is null for everyone and this
     would bounce nobody anywhere. replace rather than push, so Back does not
     return to a form that has already been passed. */
  useEffect(() => {
    if (ready && user) router.replace(next);
  }, [ready, user, next, router]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;

    setPending(true);
    setProblem(null);

    try {
      await logIn({ email, password });
      router.replace(next);
      /* No setPending(false) on success. The navigation is what ends this
         screen, and re-enabling the button first would let a second submit
         through in the gap. */
    } catch (error) {
      /* One message for a wrong password and for an address with no account,
         because the API deliberately answers both identically. Splitting them
         here would undo that and hand anyone a way to test which addresses
         are registered. */
      setProblem(
        error instanceof ApiError && error.is("invalid-credentials")
          ? "That email and password do not match an account."
          : "Could not sign you in. Try again.",
      );
      setPending(false);
    }
  }

  if (!AUTH_ENABLED) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>Accounts are off in demo mode</h1>
        <p className={styles.lede}>
          A session here is an httpOnly cookie with CSRF protection and refresh
          rotation, and a service worker cannot reproduce any of the three.
          Mocking it would be a different mechanism wearing the same screens, so
          it is deliberately absent. Point the app at the live API to sign in.
        </p>
        <Link href="/" className={styles.secondary}>
          ← All showtimes
        </Link>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Sign in</h1>
      <p className={styles.lede}>
        An account keeps your bookings together and raises the limit from three
        seats to six. You can always book as a guest instead.
      </p>

      <form className={styles.form} onSubmit={submit} noValidate={false}>
        {problem && (
          <p className={styles.formError} role="alert">
            {problem}
          </p>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="email">
            Email
          </label>
          <input
            id="email"
            className={styles.input}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            maxLength={254}
            required
            disabled={pending}
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="password">
            Password
          </label>
          <input
            id="password"
            className={styles.input}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            /* current-password, not password: it tells a password manager to
               fill rather than to offer a generated one. */
            autoComplete="current-password"
            required
            disabled={pending}
          />
          {/* No minLength here, unlike signup. A password that no longer meets
              today's rules still has to be able to log in. */}
        </div>

        <button type="submit" className={styles.submit} disabled={pending}>
          {pending ? "Signing in..." : "Sign in"}
        </button>
      </form>

      <p className={styles.alt}>
        No account?{" "}
        <Link href={`/signup?next=${encodeURIComponent(next)}`}>Create one</Link>
        {" · "}
        <Link href="/forgot-password">Forgot your password?</Link>
      </p>
    </div>
  );
}