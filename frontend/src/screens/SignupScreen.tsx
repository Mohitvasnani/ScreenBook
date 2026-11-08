"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";

import { useAuth } from "@/components/AuthProvider";
import styles from "@/css/AuthScreen.module.css";
import { AUTH_ENABLED } from "@/services/apiConfig";
import { ApiError } from "@/services/problemDetails";
import { safeNext } from "@/utils/redirects";

export function SignupScreen() {
  const router = useRouter();
  const next = safeNext(useSearchParams().get("next"));
  const { user, ready, signUp } = useAuth();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [pending, setPending] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  /* Kept apart from the form-level message, because this one belongs beside
     the field it is about. */
  const [emailProblem, setEmailProblem] = useState<string | null>(null);

  useEffect(() => {
    if (ready && user) router.replace(next);
  }, [ready, user, next, router]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) return;

    setPending(true);
    setProblem(null);
    setEmailProblem(null);

    try {
      await signUp({ email, password, displayName });
      router.replace(next);
    } catch (error) {
      if (error instanceof ApiError && error.is("email-taken")) {
        /* Named against the field, unlike login's deliberate vagueness. A
           signup form cannot hide this: it has to tell you the address is
           unavailable or you can never finish. The API already answers 409 for
           exactly this reason, so the asymmetry with login is an accepted
           trade rather than an oversight. */
        setEmailProblem("An account already exists for that email.");
      } else if (error instanceof ApiError && error.is("validation-failed")) {
        /* The server names which rule failed and its wording is better than
           anything generic this screen could substitute. */
        setProblem(error.problem.detail ?? "Check the details and try again.");
      } else {
        setProblem("Could not create your account. Try again.");
      }
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
          Point the app at the live API to create an account.
        </p>
        <Link href="/" className={styles.secondary}>
          ← All showtimes
        </Link>
      </div>
    );
  }

  return (
    <div className={styles.page}>
      <h1 className={styles.title}>Create an account</h1>
      <p className={styles.lede}>
        Keeps your bookings in one place and raises the limit from three seats to
        six. Booking as a guest stays available either way.
      </p>

      <form className={styles.form} onSubmit={submit}>
        {problem && (
          <p className={styles.formError} role="alert">
            {problem}
          </p>
        )}

        <div className={styles.field}>
          <label className={styles.label} htmlFor="displayName">
            Name
          </label>
          <input
            id="displayName"
            className={styles.input}
            type="text"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            autoComplete="name"
            maxLength={80}
            required
            disabled={pending}
          />
        </div>

        <div className={styles.field}>
          <label className={styles.label} htmlFor="email">
            Email
          </label>
          <input
            id="email"
            className={`${styles.input} ${emailProblem ? styles.inputRejected : ""}`}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            autoComplete="email"
            maxLength={254}
            required
            disabled={pending}
            aria-invalid={emailProblem ? true : undefined}
            aria-describedby={emailProblem ? "email-error" : undefined}
          />
          {emailProblem && (
            <p id="email-error" className={styles.fieldError}>
              {emailProblem}{" "}
              <Link href={`/login?next=${encodeURIComponent(next)}`}>
                Sign in instead
              </Link>
            </p>
          )}
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
            /* new-password, so a password manager offers to generate one
               rather than filling an existing credential. */
            autoComplete="new-password"
            minLength={8}
            maxLength={72}
            required
            disabled={pending}
            aria-describedby="password-hint"
          />
          <p id="password-hint" className={styles.hint}>
            8 to 72 characters. Length is what matters, so there are no rules
            about symbols or digits.
          </p>
        </div>

        <button type="submit" className={styles.submit} disabled={pending}>
          {pending ? "Creating account..." : "Create account"}
        </button>
      </form>

      <p className={styles.alt}>
        Already have an account?{" "}
        <Link href={`/login?next=${encodeURIComponent(next)}`}>Sign in</Link>
      </p>
    </div>
  );
}