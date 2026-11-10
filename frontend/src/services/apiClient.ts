/* The only place in the app that calls fetch. Everything else goes through
   api.*, which is what makes the mock-to-live switch a single env var. */

   import { API_BASE_URL } from "./apiConfig";
   import { ApiError, type ProblemDetail } from "./problemDetails";
   import type {
     Booking,
     ForgotPasswordRequest,
     Hold,
     HoldDetail,
     LoginRequest,
     ResetPasswordRequest,
     SeatMap,
     Showtime,
     SignupRequest,
     User,
     Cinema,
     CinemaDetail,
     NearestCinema,
   } from "@/types/api";
   
   /* Spring's CookieCsrfTokenRepository.withHttpOnlyFalse() writes this one so the
      page can read it and echo it back in a header. It is deliberately readable:
      a CSRF token is not a secret from the page, only from other origins, which
      the same-origin policy already enforces. */
   const CSRF_COOKIE = "XSRF-TOKEN";
   const CSRF_HEADER = "X-XSRF-TOKEN";
   
   function csrfToken(): string | null {
     /* No document during the server render pass. */
     if (typeof document === "undefined") return null;
   
     const entry = document.cookie
       .split("; ")
       .find((pair) => pair.startsWith(`${CSRF_COOKIE}=`));
   
     return entry ? decodeURIComponent(entry.slice(CSRF_COOKIE.length + 1)) : null;
   }
   
   /* Safe methods are exempt from CSRF on the server, so sending the header on a
      GET would be noise. */
   function needsCsrf(method: string): boolean {
     return !["GET", "HEAD", "OPTIONS", "TRACE"].includes(method.toUpperCase());
   }
   
   /* One refresh at a time within this tab.
   
      Refresh tokens rotate, and the server treats a replayed one as theft:
      presenting a spent token revokes every session for that user. Two requests
      that both see a 401 and both call refresh would do exactly that to the
      person using the app, so the second waits on the first rather than starting
      its own.
   
      Module scope rather than React state, because a refresh is not a rendering
      concern and components mount and unmount underneath it. */
   let inFlightRefresh: Promise<boolean> | null = null;
   
   /* The promise above only covers this tab. Two tabs are two JavaScript contexts
      sharing one cookie jar, so both would read the same refresh cookie and both
      would present it. Web Locks serialises them: the second tab waits, then
      refreshes with the cookie the first one just set.
   
      Degrades to running unserialised where the API is missing, which is the
      honest failure. Losing the lock costs a session; refusing to run costs the
      whole app. */
   async function withCrossTabLock<T>(run: () => Promise<T>): Promise<T> {
     if (typeof navigator === "undefined" || !("locks" in navigator)) return run();
   
     /* The DOM typings make request() generic over the callback's return type,
        so an async callback infers Promise<T> and the call reads as
        Promise<Promise<T>>. Promises flatten at runtime; awaiting here makes
        the type say what actually happens. */
     return await navigator.locks.request("screenbook-refresh", run);
   }
   
   function refreshSession(): Promise<boolean> {
     if (inFlightRefresh) return inFlightRefresh;
   
     inFlightRefresh = withCrossTabLock(() =>
       send<User>("/auth/refresh", { method: "POST" })
         .then(() => true)
         .catch(() => false),
     ).finally(() => {
       /* Cleared whichever way it went, so the next 401 starts a fresh attempt
          instead of reusing a settled answer forever. */
       inFlightRefresh = null;
     });
   
     return inFlightRefresh;
   }
   
   /* One fetch, no retry. The layer that knows about 401s is below. */
   async function send<T>(path: string, init: RequestInit = {}): Promise<T> {
     const method = init.method ?? "GET";
     const csrf = needsCsrf(method) ? csrfToken() : null;
   
     const response = await fetch(`${API_BASE_URL}/api${path}`, {
       ...init,
       /* Already the default for a same-origin request, stated rather than
          assumed because the entire session design rests on these being
          same-origin: the Next.js rewrite is what makes the cookie first-party. */
       credentials: "same-origin",
       headers: {
         Accept: "application/json",
         ...(init.body ? { "Content-Type": "application/json" } : {}),
         ...(csrf ? { [CSRF_HEADER]: csrf } : {}),
         ...init.headers,
       },
     });
   
     if (!response.ok) {
       /* The API sends problem details. A proxy, a CDN or a platform error
          page does not, so anything not recognisably RFC 9457 is replaced with
          the same shape and callers keep one error type to handle.
   
          The check is on the parsed body, not merely on whether it parsed. A
          Vercel 404 is valid JSON with no type field, and casting it handed
          every caller an object that lied about its shape. */
       const fallback: ProblemDetail = {
         type: "about:blank",
         title: response.statusText || "Request failed",
         status: response.status,
       };
   
       let detail = fallback;
       try {
         const body: unknown = await response.json();
         if (
           typeof body === "object" &&
           body !== null &&
           typeof (body as ProblemDetail).type === "string"
         ) {
           detail = body as ProblemDetail;
         }
       } catch {
         /* Not JSON at all, usually an HTML error page. fallback already says
            everything knowable about it. */
       }
       throw new ApiError(detail);
     }
   
     /* 204 from DELETE and logout, 202 from forgot-password: success with
        nothing to say. Checked on the status rather than by trying to parse and
        catching, because that would also swallow a genuinely malformed body. */
     if (response.status === 204 || response.status === 202) return undefined as T;
   
     return (await response.json()) as T;
   }
   
   async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
     try {
       return await send<T>(path, init);
     } catch (error) {
       if (!(error instanceof ApiError) || error.status !== 401) throw error;
   
       /* The auth endpoints own their own 401s. A wrong password is not an
          expired session, and refreshing after one would loop. */
       if (path.startsWith("/auth/")) throw error;
   
       if (!(await refreshSession())) throw error;
   
       /* Once only. If this also 401s the session is genuinely gone and a second
          refresh would not change that. init.body is a string here, never a
          stream, so replaying the request is safe.
   
          A booking retry reuses its Idempotency-Key, which is exactly the case
          that header exists for. */
       return await send<T>(path, init);
     }
   }
   
   export const api = {
    listCinemas: () => request<Cinema[]>("/cinemas"),

    /* URLSearchParams rather than template interpolation, so a coordinate
       never needs escaping by hand. limit is omitted when absent rather than
       sent empty, because the server reads an absent parameter as "use the
       default" and an unparseable one as a 400. */
    nearestCinemas: (lat: number, lon: number, limit?: number) =>
      request<NearestCinema[]>(
        `/cinemas/nearest?${new URLSearchParams({
          lat: String(lat),
          lon: String(lon),
          ...(limit === undefined ? {} : { limit: String(limit) }),
        })}`,
      ),

    getCinema: (cinemaId: number) => request<CinemaDetail>(`/cinemas/${cinemaId}`),

    /* cinemaId is required, as it is on the server. A call with no cinema is
       not a listing anyone can read, so it is a type error here rather than a
       400 at runtime. */
       listShowtimes: (cinemaId: number) =>
        request<Showtime[]>(`/showtimes?cinemaId=${cinemaId}`),
  
      /* One screening. The list is scoped to a cinema, and a page reached by URL
         with only a showtime id has no cinema to scope a list with, which is what
         three screens were doing by listing twelve to find one. */
      getShowtime: (showtimeId: number) =>
        request<Showtime>(`/showtimes/${showtimeId}`),
  
      getSeatMap: (showtimeId: number) =>
        request<SeatMap>(`/showtimes/${showtimeId}/seats`),
  
      createHold: (showtimeId: number, seatIds: number[]) =>
        request<Hold>("/holds", {
          method: "POST",
          body: JSON.stringify({ showtimeId, seatIds }),
        }),
  
      getHold: (holdId: string) => request<HoldDetail>(`/holds/${holdId}`),
   
     /* Hand a seat back before the hold lapses, rather than leaving it locked for
        the rest of its five minutes. */
     releaseHold: (holdId: string) =>
       request<void>(`/holds/${holdId}`, { method: "DELETE" }),
   
     /* The key makes this safe to retry: if the response is lost, sending the
        same key again returns the original booking rather than a 409.
   
        email is for guests and is optional. undefined drops out of
        JSON.stringify, so an omitted address sends no field at all. */
     createBooking: (holdId: string, idempotencyKey?: string, email?: string) =>
       request<Booking>("/bookings", {
         method: "POST",
         body: JSON.stringify({ holdId, email }),
         headers: idempotencyKey ? { "Idempotency-Key": idempotencyKey } : {},
       }),
   
     getBooking: (reference: string) => request<Booking>(`/bookings/${reference}`),
   
     /* Signing up signs you in. The response is the user; the session arrives as
        cookies the page cannot read. */
     signUp: (body: SignupRequest) =>
       request<User>("/auth/signup", {
         method: "POST",
         body: JSON.stringify(body),
       }),
   
     logIn: (body: LoginRequest) =>
       request<User>("/auth/login", {
         method: "POST",
         body: JSON.stringify(body),
       }),
   
     /* 204, and calling it without a session is not an error. */
     logOut: () => request<void>("/auth/logout", { method: "POST" }),
   
     /* 202 whatever happened, and deliberately so: an unknown address, a
        rate-limited one and a real one are indistinguishable. Nothing in the
        UI may imply otherwise. */
     forgotPassword: (body: ForgotPasswordRequest) =>
       request<void>("/auth/forgot-password", {
         method: "POST",
         body: JSON.stringify(body),
       }),
   
     /* 204, and it clears the session cookies as well, because the reset
        revoked every session including this browser's. */
     resetPassword: (body: ResetPasswordRequest) =>
       request<void>("/auth/reset-password", {
         method: "POST",
         body: JSON.stringify(body),
       }),
   
     /* Who the cookie says you are. For a guest this 401s, attempts one refresh
        that also 401s, and throws: two requests to learn there is no session.
        That is the cost of the refresh cookie being httpOnly and therefore
        invisible to this code. Worth fixing later with a readable hint cookie,
        not worth a client-side guess now. */
     me: () => request<User>("/me"),
   
     myBookings: () => request<Booking[]>("/me/bookings"),
   };