"use client";

import { useState, type ComponentProps } from "react";

import styles from "@/css/PasswordInput.module.css";

type Props = Omit<ComponentProps<"input">, "type">;

/* A password field with a show toggle. Everything else goes through to the
   input, so each screen keeps its own autoComplete and length rules. */
export function PasswordInput({ className, ...rest }: Props) {
  const [shown, setShown] = useState(false);

  return (
    <div className={styles.wrap}>
      <input
        {...rest}
        className={`${className ?? ""} ${styles.field}`}
        type={shown ? "text" : "password"}
        autoCapitalize="none"
        spellCheck={false}
      />
      <button
        type="button"
        className={styles.toggle}
        onClick={() => setShown((value) => !value)}
        aria-label={shown ? "Hide password" : "Show password"}
      >
        {shown ? "Hide" : "Show"}
      </button>
    </div>
  );
}