/* NEXT_PUBLIC_ values are substituted at build time, so these are constants in
   the bundle rather than runtime lookups. */

   export type ApiMode = "mock" | "live";

   export const API_MODE: ApiMode =
     process.env.NEXT_PUBLIC_API_MODE === "live" ? "live" : "mock";
   
   /* Empty, so every request is relative and same-origin. In mock mode the
      service worker sees them before they leave the page; in live mode the
      Next.js rewrite forwards /api/* to the Spring service, which is what makes
      the session cookie first-party. */
   export const API_BASE_URL = "";
   
   export const IS_MOCK = API_MODE === "mock";
   
   /* Auth is live-mode only, and that is a decision rather than a gap. MSW cannot
      reproduce httpOnly cookies, CSRF tokens or refresh rotation, so a mocked
      version would be a different mechanism wearing the same UI and would prove
      nothing. The DEMO badge already tells a viewer which mode they are in. */
   export const AUTH_ENABLED = !IS_MOCK;
   
   /* Mirrors the server value in docs/api-contract.md. */
   export const HOLD_TTL_SECONDS = 300;
   export const MAX_SEATS_GUEST = 3;
   export const MAX_SEATS_MEMBER = 6;