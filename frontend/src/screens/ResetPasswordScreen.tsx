"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import styles from "@/css/AuthScreen.module.css";
import { api } from "@/services/apiClient";
import { AUTH_ENABLED } from "@/services/apiConfig";
import { ApiError } from "@/services/problemDetails";

export function ResetPasswordScreen() {
  const router = useRouter();
  const params = useSearchParams();

  /* Captured once, on mount, because the effect below removes it from the URL
     and a later read would find nothing. */
  const [token] = useState(() => params.get("token") ?? "");

  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [done, setDone] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  /* Strips the token from the address bar as soon as it is in hand. A reset
     token is a credential, and leaving it in the URL leaves it in history, in
     any referrer this page sends, and in whatever the browser syncs. Replace
     rather than push so Back does not restore it.

     Terminates on its own: once replaced, params.get returns null and the
     condition is false on the re-run. */
  useEffect(() => {
    if (params.get("token")) router.replace("/reset-password");
  }, [params, router]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;

    setPending(true);
    setProblem(null);

    try {
      await api.resetPassword({ token, password });
      setDone(true);
    } catch (error) {
      setProblem(
        error instanceof ApiError && error.is("invalid-reset-token")
          ? "That link has expired or has already been used. Request a new one."
          : "Could not set your password. Try again.",
      );
    }
    setPending(false);
  }

  if (!AUTH_ENABLED) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>Accounts are off in demo mode</h1>
        <p className={styles.lede}>
          Password reset needs a real session and a real mail sender, and a
          service worker can reproduce neither. Point the app at the live API to
          use it.
        </p>
        <Link href="/" className={styles.secondary}>
          ← All showtimes
        </Link>
      </div>
    );
  }

  /* No token at all, so the link was truncated by a mail client or someone
     reached this page directly. Saying so beats an empty form that can only
     fail. */
  if (!token) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>That link is incomplete</h1>
        <p className={styles.lede}>
          Reset links carry a one-time token and this one has none, which
          usually means it was cut short on the way. Ask for a fresh one.
        </p>
        <p className={styles.alt}>
          <Link href="/forgot-password">Send a new link</Link>
        </p>
      </div>
    );
  }

  /* Not signed in automatically, on purpose: typing the new password once more
     is a second of friction against the chance the link was opened by the
     wrong person on a shared machine. Every previous session is already dead. */
  if (done) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>Password changed</h1>
        <p className={styles.lede}>
          You have been signed out everywhere, including anywhere you had left
          yourself signed in. Sign in with the new password to carry on.
        </p>
        <p className={styles.alt}>
          <Link href="/login">Sign in</Link>
        </p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Choose a new password</h1>
      <p className={styles.lede}>
        This link works once. Setting a password here signs you out of every
        other session.
      </p>

      <form className={styles.form} onSubmit={submit}>
        {problem && (
          <p className={styles.formError} role="alert">
            {problem}
          </p>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="password">
            New password
          </label>
          <input
            id="password"
            className={styles.input}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            /* new-password, so a manager offers to generate and then save one
               rather than filling the old value. */
            autoComplete="new-password"
            minLength={8}
            maxLength={72}
            required
            disabled={pending}
          />
          {/* One field, not two. A typo here is recoverable by asking for
              another link, and a confirm field is friction against a mistake
              the flow already tolerates. Password managers fill both
              identically anyway, so it mostly catches nothing. */}
        </div>

        <button type="submit" className={styles.submit} disabled={pending}>
          {pending ? "Saving..." : "Set password"}
        </button>
      </form>
    </div>
  );
}