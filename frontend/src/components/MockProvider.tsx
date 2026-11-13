"use client";

import { useEffect, useState, type ReactNode } from "react";

import styles from "@/css/MockProvider.module.css";
import { IS_MOCK } from "@/services/apiConfig";

/* Holds rendering until the worker is listening. Without the gate, the first
   request can fire before interception is active, which looks like a flaky
   network. In live mode children render immediately and the msw chunk is
   never downloaded. */
export function MockProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(!IS_MOCK);

  useEffect(() => {
    if (!IS_MOCK) return;

    let cancelled = false;

    void (async () => {
      /* msw maps "./browser" to null outside a browser, deliberately. Next
         compiles client components twice, once for the browser and once for
         server rendering, and the server pass still has to resolve this import
         even though it never runs it. import.meta.env.SSR is substituted at
         build time, so the branch is removed from the server bundle entirely.

         Turbopack-specific, and load-bearing: webpack does not define
         import.meta.env, so building with --webpack fails to resolve
         msw/browser and would also throw on .SSR of undefined at runtime. */
      if (import.meta.env.SSR) return;

      const { startWorker } = await import("@/mocks/browser");
      await startWorker();
      if (!cancelled) setReady(true);
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  if (!ready) {
    return <div className={styles.pending}>Starting demo mode...</div>;
  }

  return <>{children}</>;
}