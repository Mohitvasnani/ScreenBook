/* The mock implementation of docs/api-contract.md, running in a service worker
   so the app's fetch calls are intercepted before they leave the page. Status
   codes and error bodies match the contract exactly: when the Spring service
   replaces this, no component changes. */

   import { delay, http, HttpResponse } from "msw";

   import { HOLD_TTL_SECONDS, MAX_SEATS_MEMBER } from "@/services/apiConfig";
   import { PROBLEM_BASE, type ProblemDetail } from "@/services/problemDetails";
   import type {
    Cinema,
    CinemaCity,
    CinemaDetail,
    CinemasWithin,
    HeldSeat,
    Hold,
    HoldDetail,
    HoldStatus,
    NearestCinema,
    SeatMap,
    Showtime,
    UnavailableSeat,
  } from "@/types/api";
  import { CINEMAS, cinemaById, nearest, toDetail, toSummary, cities, within } from "./cinemas";
  import {
    createBooking,
    AISLE_AFTER,
    createHold,
    findIdempotency,
    findShowtime,
    fromPriceFor,
    getBooking,
    getHoldRecord,
    priceRatesFor,
    rememberIdempotency,
    releaseHold,
    remainingSeconds,
    seatMapFor,
    seatsAvailableFor,
    seatsInOrder,
    showtimesForCinema,
    type SeatPosition,
    priceCinemas,
      type HoldRecord,
  priceOfSeat,
  pricingGroupsOf,
  } from "./db";
   
   
   /* Enough latency that loading states are real rather than theoretical. */
   const LATENCY_MS = 180;
      /* Mirrors screenbook.cinemas.nearest-default-results and
      nearest-max-results in application.properties. If you change one, change
      both, same as the pricing bands. */
      const NEAREST_DEFAULT = 5;
      const NEAREST_MAX = 20;
            /* Mirrors screenbook.cinemas.within-default-metres, within-max-metres and
      from-price-window-hours. Same rule as the nearest pair: if you change one,
      change both. */
      const WITHIN_DEFAULT = 10_000;
      const WITHIN_MAX = 50_000;
      const FROM_PRICE_WINDOW_HOURS = 24;

      /* Spring's own 400, not one of our problem types. A missing or unparseable
      query parameter never reaches a controller, so there is nothing to throw an
      ApiException: spring.mvc.problemdetails.enabled turns Spring's internal
      exception into problem+json with type about:blank instead. Reproduced
      exactly, so the client's unknown-slug path is exercised in demo mode rather
      than only in production. */
      const badRequest = (instance: string, detail: string) =>
        problemResponse({
          type: "about:blank",
          title: "Bad Request",
          status: 400,
          detail,
          instance,
        });
   
   function problem(
     slug: string,
     status: number,
     title: string,
     detail: string,
     instance: string,
     extra: Record<string, unknown> = {},
   ): ProblemDetail {
     return { type: `${PROBLEM_BASE}/${slug}`, title, status, detail, instance, ...extra };
   }
   
   const problemResponse = (body: ProblemDetail) =>
     HttpResponse.json(body, {
       status: body.status,
       headers: { "Content-Type": "application/problem+json" },
     });
   
   const notFound = (instance: string, detail: string) =>
     problemResponse(problem("not-found", 404, "Not found", detail, instance));
   
   const invalid = (instance: string, detail: string) =>
     problemResponse(problem("validation-failed", 422, "Validation failed", detail, instance));
   
   /* Every seat on a hold carries the price frozen when it was claimed, not a
   price worked out now. That is the whole point of the freeze: the house fills
   while a customer is at the checkout. Each seat carries its own tier's price. */
function heldSeats(seats: SeatPosition[], hold: HoldRecord): HeldSeat[] {
  return seats.map((seat) => ({
    seatId: seat.id,
    rowLabel: seat.rowLabel,
    seatNumber: seat.seatNumber,
    tier: seat.tier,
    priceMinor: priceOfSeat(hold, seat),
  }));
}
   
   const totalOf = (seats: HeldSeat[]) =>
     seats.reduce((sum, seat) => sum + seat.priceMinor, 0);
   
   /* Reads naturally for one seat and for several, matching the service. */
   function unavailableDetail(requested: number, lost: UnavailableSeat[]): string {
     if (requested === 1) {
       return `Seat ${lost[0].rowLabel}${lost[0].seatNumber} is already held or booked for this showtime.`;
     }
     return `${lost.length} of the ${requested} seats you chose have just gone.`;
   }
   
   
   export const handlers = [
       /* country is a filter, not a required parameter, because the globe's opening
       state shows both markets at once. An unrecognised code is an empty list
       rather than a 400, matching CinemaQuery.inCountry: cinema.country is a
       foreign key to V22's country table, so the database already defines which
       codes exist, and "cinemas in ZZ" has an honest answer. */
    http.get("/api/cinemas", async ({ request }) => {
      await delay(LATENCY_MS);

      const country = new URL(request.url).searchParams.get("country");
      const rows = CINEMAS.map(toSummary);
      const body: Cinema[] =
        country === null ? rows : rows.filter((c) => c.country === country);

      return HttpResponse.json(body);
    }),

    /* Before the :cinemaId route below, and that is load-bearing. MSW matches
       handlers in array order, so a parameter route declared first would match
       /api/cinemas/nearest and try to read "nearest" as an id. Spring prefers a
       literal path segment regardless of order, so this is a difference between
       the two routers rather than something the contract says. */
        /* Before the :cinemaId route below, and that is load-bearing. MSW matches
       handlers in array order, so a parameter route declared first would match
       /api/cinemas/nearest and try to read "nearest" as an id. Spring prefers a
       literal path segment regardless of order, so this is a difference between
       the two routers rather than something the contract says. */
    http.get("/api/cinemas/nearest", async ({ request }) => {
      await delay(LATENCY_MS);

      const params = new URL(request.url).searchParams;
      const lat = Number(params.get("lat"));
      const lon = Number(params.get("lon"));
      const rawLimit = params.get("limit");

      /* Absent and unparseable are both 400 here, as they are on the server:
         neither ever reaches the controller's own validation. Number(null) is 0,
         which would otherwise read as a valid coordinate in the Gulf of Guinea,
         so the presence check cannot be skipped. */
      for (const name of ["lat", "lon"] as const) {
        const value = params.get(name);
        if (value === null) {
          return badRequest(
            "/api/cinemas/nearest",
            `Required request parameter '${name}' for method parameter type double is not present.`,
          );
        }
        if (!Number.isFinite(Number(value))) {
          return badRequest(
            "/api/cinemas/nearest",
            `Failed to convert value of type 'java.lang.String' to required type 'double'; nested exception is java.lang.NumberFormatException: For input string: "${value}"`,
          );
        }
      }

      /* 422 rather than 400 for a number that parsed but is not a coordinate,
         matching CinemaController: the request was understood, the value is
         wrong. */
      if (lat < -90 || lat > 90) {
        return invalid(
          "/api/cinemas/nearest",
          `lat must be between -90 and 90, got ${lat}.`,
        );
      }
      if (lon < -180 || lon > 180) {
        return invalid(
          "/api/cinemas/nearest",
          `lon must be between -180 and 180, got ${lon}.`,
        );
      }

      const requested = rawLimit === null ? NEAREST_DEFAULT : Number(rawLimit);
      if (!Number.isInteger(requested) || requested < 1) {
        return invalid(
          "/api/cinemas/nearest",
          `limit must be at least 1, got ${requested}.`,
        );
      }

      const body: NearestCinema[] = priceCinemas(
        nearest(lat, lon, Math.min(requested, NEAREST_MAX)),
        FROM_PRICE_WINDOW_HOURS,
      );
      return HttpResponse.json(body);
    }),
        /* Also before :cinemaId, same reason.

       The validation is nearest's with metres in place of limit, which is the
       shape CinemaController has: one requireValidPoint, then one clamped
       integer. Clamped rather than rejected, because asking for 500km is a
       client that does not know the cap rather than a mistake, and the honest
       answer is as far as we will look.

       The fallback is priced too. "Nothing within 10km, the closest is 34km away
       and from 850" is the sentence this endpoint exists to let the panel say,
       and the price is half of it. */
    http.get("/api/cinemas/within", async ({ request }) => {
      await delay(LATENCY_MS);

      const params = new URL(request.url).searchParams;
      const lat = Number(params.get("lat"));
      const lon = Number(params.get("lon"));
      const rawMetres = params.get("metres");

      for (const name of ["lat", "lon"] as const) {
        const value = params.get(name);
        if (value === null) {
          return badRequest(
            "/api/cinemas/within",
            `Required request parameter '${name}' for method parameter type double is not present.`,
          );
        }
        if (!Number.isFinite(Number(value))) {
          return badRequest(
            "/api/cinemas/within",
            `Failed to convert value of type 'java.lang.String' to required type 'double'; nested exception is java.lang.NumberFormatException: For input string: "${value}"`,
          );
        }
      }

      if (lat < -90 || lat > 90) {
        return invalid(
          "/api/cinemas/within",
          `lat must be between -90 and 90, got ${lat}.`,
        );
      }
      if (lon < -180 || lon > 180) {
        return invalid(
          "/api/cinemas/within",
          `lon must be between -180 and 180, got ${lon}.`,
        );
      }

      const requested = rawMetres === null ? WITHIN_DEFAULT : Number(rawMetres);
      if (!Number.isInteger(requested) || requested < 1) {
        return invalid(
          "/api/cinemas/within",
          `metres must be at least 1, got ${requested}.`,
        );
      }

      const radiusMetres = Math.min(requested, WITHIN_MAX);
      const inside = priceCinemas(
        within(lat, lon, radiusMetres),
        FROM_PRICE_WINDOW_HOURS,
      );

      if (inside.length > 0) {
        const body: CinemasWithin = {
          radiusMetres,
          cinemas: inside,
          nearestOutside: null,
        };
        return HttpResponse.json(body);
      }

      const fallback = priceCinemas(
        nearest(lat, lon, 1),
        FROM_PRICE_WINDOW_HOURS,
      );
      const body: CinemasWithin = {
        radiusMetres,
        cinemas: [],
        nearestOutside: fallback[0] ?? null,
      };
      return HttpResponse.json(body);
    }),

    
    /* Demo mode has no routing provider, so it answers the way the server does
       when none is configured. The legend then shows its fallback message. */
    http.get("/api/cinemas/:cinemaId/route", async ({ params }) => {
      await delay(LATENCY_MS);
      return problemResponse(
        problem(
          "routing-unavailable",
          503,
          "Routing unavailable",
          "Routing is not configured.",
          `/api/cinemas/${params.cinemaId}/route`,
        ),
      );
    }),

        http.get("/api/cinemas/cities", async () => {
      await delay(LATENCY_MS);
      const body: CinemaCity[] = cities();
      return HttpResponse.json(body);
    }),

    http.get("/api/cinemas/:cinemaId", async ({ params }) => {
      await delay(LATENCY_MS);
      const cinemaId = Number(params.cinemaId);
      const cinema = Number.isInteger(cinemaId) ? cinemaById(cinemaId) : undefined;

      if (!cinema) {
        return notFound(
          `/api/cinemas/${params.cinemaId}`,
          `No cinema with id ${params.cinemaId}.`,
        );
      }

      const body: CinemaDetail = toDetail(cinema);
      return HttpResponse.json(body);
    }),
    
    http.get("/api/showtimes", async ({ request }) => {
      await delay(LATENCY_MS);

      const raw = new URL(request.url).searchParams.get("cinemaId");

      /* The three answers the server distinguishes, in the order it reaches
         them: no parameter is a 400 before any controller runs, an unknown
         cinema is a 404, and a real cinema with nothing scheduled is an empty
         list. Collapsing any two of them would make the client guess. */
      if (raw === null) {
        return badRequest(
          "/api/showtimes",
          "Required request parameter 'cinemaId' for method parameter type long is not present.",
        );
      }

      const cinemaId = Number(raw);
      if (!Number.isInteger(cinemaId)) {
        return badRequest(
          "/api/showtimes",
          `Failed to convert value of type 'java.lang.String' to required type 'long'; nested exception is java.lang.NumberFormatException: For input string: "${raw}"`,
        );
      }

      if (!cinemaById(cinemaId)) {
        return notFound("/api/showtimes", `No cinema with id ${cinemaId}.`);
      }

      const body: Showtime[] = showtimesForCinema(cinemaId).map((showtime) => ({
        ...showtime,
        ...fromPriceFor(showtime.id),
        seatsAvailable: seatsAvailableFor(showtime.id),
      }));
      return HttpResponse.json(body);
    }),

    http.get("/api/showtimes/:showtimeId", async ({ params }) => {
      await delay(LATENCY_MS);
      const showtimeId = Number(params.showtimeId);
      const showtime = Number.isInteger(showtimeId) ? findShowtime(showtimeId) : undefined;

      if (!showtime) {
        return notFound(
          `/api/showtimes/${params.showtimeId}`,
          `No showtime with id ${params.showtimeId}.`,
        );
      }

      /* No starts_at filter, matching the server: a screening stays readable
         after it has started so a bookmarked page still renders. */
      const body: Showtime = {
        ...showtime,
        ...fromPriceFor(showtime.id),
        seatsAvailable: seatsAvailableFor(showtime.id),
      };
      return HttpResponse.json(body);
    }),
   
     http.get("/api/showtimes/:showtimeId/seats", async ({ params }) => {
       await delay(LATENCY_MS);
       const showtimeId = Number(params.showtimeId);
   
       if (!Number.isInteger(showtimeId) || !findShowtime(showtimeId)) {
         return notFound(
           `/api/showtimes/${params.showtimeId}/seats`,
           `No showtime with id ${params.showtimeId}.`,
         );
       }
   
       const body: SeatMap = {
        showtimeId,
        seats: seatMapFor(showtimeId),
        aisleAfter: AISLE_AFTER,
        priceRules: priceRatesFor(showtimeId),
      };
       return HttpResponse.json(body);
     }),
   
     http.post("/api/holds", async ({ request }) => {
       await delay(LATENCY_MS);
   
       let payload: { showtimeId?: unknown; seatIds?: unknown };
       try {
         payload = (await request.json()) as typeof payload;
       } catch {
         payload = {};
       }
   
       const { showtimeId, seatIds } = payload;
   
       if (typeof showtimeId !== "number") {
         return invalid("/api/holds", "showtimeId is required and must be a number.");
       }
       if (!Array.isArray(seatIds) || seatIds.some((id) => typeof id !== "number")) {
         return invalid("/api/holds", "seatIds is required and must be an array of numbers.");
       }
       if (seatIds.length < 1 || seatIds.length > MAX_SEATS_MEMBER) {
         return invalid(
           "/api/holds",
           `seatIds must contain between 1 and ${MAX_SEATS_MEMBER} seats.`,
         );
       }
       /* Rejected rather than deduped: a repeated seat is a client bug worth
          reporting, and deduping makes "how many did you ask for" ambiguous. */
       if (new Set(seatIds).size !== seatIds.length) {
         return invalid("/api/holds", "seatIds must not repeat a seat.");
       }
   
       
   
       const outcome = createHold(showtimeId, seatIds as number[]);
   
       if (!outcome.ok) {
         if (outcome.reason === "unavailable") {
           return problemResponse(
             problem(
               "seats-unavailable",
               409,
               "Seats unavailable",
               unavailableDetail(seatIds.length, outcome.unavailable),
               "/api/holds",
               { unavailableSeats: outcome.unavailable },
             ),
           );
         }
         return notFound(
           "/api/holds",
           outcome.reason === "no-such-showtime"
             ? `No showtime with id ${showtimeId}.`
             : `No seat with id ${outcome.seatId}.`,
         );
       }
   
       const showtime = findShowtime(showtimeId)!;
           const seats = heldSeats(outcome.seats, outcome.hold);
   
       const body: Hold = {
         holdId: outcome.hold.holdId,
         showtimeId: outcome.hold.showtimeId,
         seats,
         totalMinor: totalOf(seats),
         currency: showtime.currency,
                 /* The zero bands are kept on the hold, as the server keeps them in the
           database, and filtered out of the response. One group per tier. */
        pricingGroups: pricingGroupsOf(outcome.hold, outcome.seats),
         expiresAt: new Date(outcome.hold.expiresAt).toISOString(),
         ttlSeconds: HOLD_TTL_SECONDS,
       };
   
       return HttpResponse.json(body, {
         status: 201,
         headers: { Location: `/api/holds/${outcome.hold.holdId}` },
       });
     }),
   
     http.get("/api/holds/:holdId", async ({ params }) => {
       await delay(LATENCY_MS);
       const holdId = String(params.holdId);
       const hold = getHoldRecord(holdId);
       const showtime = hold ? findShowtime(hold.showtimeId) : undefined;
   
       if (!hold || !showtime) {
         return notFound(`/api/holds/${holdId}`, `No hold with id ${holdId}.`);
       }
   
       const status: HoldStatus =
         hold.convertedAt !== null
           ? "CONVERTED"
           : hold.expiresAt > Date.now()
             ? "ACTIVE"
             : "EXPIRED";
   
           const positions = seatsInOrder(hold.seatIds);
    const seats = heldSeats(positions, hold);
   
       const body: HoldDetail = {
         holdId: hold.holdId,
         showtimeId: hold.showtimeId,
         seats,
         totalMinor: totalOf(seats),
         currency: showtime.currency,
                /* Read back unchanged, so a checkout reloaded four minutes in shows the
           same figures as the original quote. */
        pricingGroups: pricingGroupsOf(hold, positions),
         expiresAt: new Date(hold.expiresAt).toISOString(),
         ttlSeconds: remainingSeconds(hold.expiresAt),
         status,
       };
       return HttpResponse.json(body);
     }),
   
     http.delete("/api/holds/:holdId", async ({ params }) => {
       await delay(LATENCY_MS);
       const holdId = String(params.holdId);
       const outcome = releaseHold(holdId);
   
       if (!outcome.ok) {
         if (outcome.reason === "already-converted") {
           return problemResponse(
             problem(
               "hold-already-converted",
               409,
               "Hold already converted",
               "That hold is already a booking and cannot be released.",
               `/api/holds/${holdId}`,
             ),
           );
         }
         return notFound(`/api/holds/${holdId}`, `No hold with id ${holdId}.`);
       }
       return new HttpResponse(null, { status: 204 });
     }),
   
     http.post("/api/bookings", async ({ request }) => {
       await delay(LATENCY_MS);
   
       let payload: { holdId?: unknown };
       try {
         payload = (await request.json()) as typeof payload;
       } catch {
         payload = {};
       }
   
       if (typeof payload.holdId !== "string" || payload.holdId.length === 0) {
         return invalid("/api/bookings", "holdId is required.");
       }
   
       const holdId = payload.holdId;
       const key = request.headers.get("Idempotency-Key");
   
       /* The hash is over the one meaningful field, same as the service. */
       if (key) {
         const seen = findIdempotency(key);
         if (seen) {
           if (seen.requestHash !== holdId) {
             return problemResponse(
               problem(
                 "idempotency-key-reused",
                 422,
                 "Idempotency key reused",
                 "That Idempotency-Key was already used for a different request. Use a new key.",
                 "/api/bookings",
               ),
             );
           }
           /* Replay. Re-read rather than store a body: a booking never changes. */
           const existing = getBooking(seen.reference);
           if (existing) return HttpResponse.json(existing, { status: 201 });
         }
       }
   
       const outcome = createBooking(holdId);
   
       if (!outcome.ok) {
         switch (outcome.reason) {
           case "expired":
             return problemResponse(
               problem(
                 "hold-expired",
                 409,
                 "Hold expired",
                 "That hold expired before it was confirmed. The seats are available again.",
                 "/api/bookings",
               ),
             );
           case "already-converted":
             return problemResponse(
               problem(
                 "hold-already-converted",
                 409,
                 "Hold already converted",
                 "That hold has already been converted into a booking.",
                 "/api/bookings",
               ),
             );
           default:
             return notFound("/api/bookings", `No hold with id ${holdId}.`);
         }
       }
   
       if (key) {
         rememberIdempotency(key, holdId, outcome.booking.bookingReference);
       }
   
       return HttpResponse.json(outcome.booking, { status: 201 });
     }),
   
     http.get("/api/bookings/:reference", async ({ params }) => {
       await delay(LATENCY_MS);
       const reference = String(params.reference);
       const booking = getBooking(reference);
   
       if (!booking) {
         return notFound(
           `/api/bookings/${reference}`,
           `No booking with reference ${reference}.`,
         );
       }
       return HttpResponse.json(booking);
     }),
   ];