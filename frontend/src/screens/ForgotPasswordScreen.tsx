"use client";

import Link from "next/link";
import { useState, type FormEvent } from "react";

import styles from "@/css/AuthScreen.module.css";
import { api } from "@/services/apiClient";
import { AUTH_ENABLED } from "@/services/apiConfig";

export function ForgotPasswordScreen() {
  const [email, setEmail] = useState("");
  const [pending, setPending] = useState(false);
  const [sent, setSent] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;

    setPending(true);
    setProblem(null);

    try {
      await api.forgotPassword({ email });
      setSent(true);
    } catch {
      /* Only a transport or validation failure reaches here. The API answers
         202 for an unknown address and for a rate-limited one, so there is no
         error case that means "no such account". */
      setProblem("Could not send that. Try again.");
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

  /* The same message whether or not that address has an account. Saying "we
     sent you an email" only when one exists would turn this screen into a way
     to test which addresses are registered, which is the one thing the
     endpoint is built not to reveal. The wording has to carry that honestly
     rather than hint. */
  if (sent) {
    return (
      <div className={styles.page}>
        <h1 className={styles.title}>Check your email</h1>
        <p className={styles.lede}>
          If {email} has an account, a reset link is on its way. It is good for
          thirty minutes and can be used once. Nothing has changed yet.
        </p>
        <p className={styles.alt}>
          <Link href="/login">Back to sign in</Link>
        </p>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Reset your password</h1>
      <p className={styles.lede}>
        Tell us the address on your account and we will send a link to set a new
        password.
      </p>

      <form className={styles.form} onSubmit={submit}>
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

        <button type="submit" className={styles.submit} disabled={pending}>
          {pending ? "Sending..." : "Send the link"}
        </button>
      </form>

      <p className={styles.alt}>
        Remembered it? <Link href="/login">Sign in</Link>
      </p>
    </div>
  );
}