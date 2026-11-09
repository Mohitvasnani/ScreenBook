/* In-memory stand-in for the Postgres schema, used in demo mode. Mirrors
   docs/api-contract.md closely enough that swapping in the real API changes no
   component code.

   State lives in module scope: it survives client-side navigation and resets on
   a full page reload. */

   import { HOLD_TTL_SECONDS } from "@/services/apiConfig";
   import type {
     BookedSeat,
     Booking,
     PriceRate,
     Pricing,
     Seat,
     SeatStatus,
     Showtime,
     Tier,
     UnavailableSeat,
   } from "@/types/api";
   
   import { asRates, type HallClass, LISTED_PRICE_MINOR, priceFor } from "./pricing";
   
   const ROWS = ["A", "B", "C", "D", "E", "F", "G", "H", "I", "J"] as const;
   const SEATS_PER_ROW = 12;
   const SEAT_ID_BASE = 1000;
   const CURRENCY = "INR";
   
   /* ------------------------------------------------------------------ seats */
   
   /* Stands in for the seat_tier table in migration V6. The price uplift it used
      to carry is gone: V12 replaced base-plus-uplift with a listed price per hall
      class and tier, because one global uplift is a steep ladder in a cheap hall
      and nearly flat in an expensive one. */
   const SEAT_TIERS: Record<Tier, { displayName: string }> = {
     NORMAL: { displayName: "Normal" },
     PREMIUM: { displayName: "Premium" },
     EXECUTIVE: { displayName: "Executive" },
   };
   
   /* Stands in for screen.hall_class in V12. Screen 4 is the recliner hall. */
   const HALL_CLASS_BY_SCREEN: Record<number, HallClass> = {
     1: "STANDARD",
     2: "STANDARD",
     3: "STANDARD",
     4: "LUXURY",
   };
   
   const hallClassOf = (screenName: string): HallClass =>
     HALL_CLASS_BY_SCREEN[Number(screenName.replace("Screen ", ""))] ?? "STANDARD";
   
   /* The only place a tier is derived from a row letter, because here it stands in
      for V6's seed rather than guessing at it. */
   function tierForRow(rowLabel: string): Tier {
     if (rowLabel <= "E") return "NORMAL";
     if (rowLabel <= "H") return "PREMIUM";
     return "EXECUTIVE";
   }
   
   export interface SeatPosition {
     id: number;
     rowLabel: string;
     seatNumber: number;
     tier: Tier;
   }
   
   export const SEATS: SeatPosition[] = ROWS.flatMap((rowLabel, rowIndex) =>
     Array.from({ length: SEATS_PER_ROW }, (_, i) => ({
       id: SEAT_ID_BASE + rowIndex * SEATS_PER_ROW + (i + 1),
       rowLabel,
       seatNumber: i + 1,
       tier: tierForRow(rowLabel),
     })),
   );
   
   export const SEATS_TOTAL = SEATS.length;
   
   export function seatById(seatId: number): SeatPosition | undefined {
     return SEATS.find((s) => s.id === seatId);
   }
   
   /* The listed price, before any rule. Stands in for the join from
      seat_availability to hall_class_tier_price. */
   export function listedPriceMinor(showtime: Showtime, seat: SeatPosition): number {
     return LISTED_PRICE_MINOR[hallClassOf(showtime.screenName)][seat.tier];
   }
   
   /* SEATS is built row by row, so filtering it gives reading order with no sort.
      The real API gets the same order from ORDER BY row_label, seat_number. */
   export const seatsInOrder = (seatIds: number[]): SeatPosition[] =>
     SEATS.filter((seat) => seatIds.includes(seat.id));
   
   /* -------------------------------------------------------------- showtimes */
   
   /* India Standard Time, matching V13. UTC+5:30 with no daylight saving, so a
      fixed offset is exact rather than an approximation. */
   const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000;
   
   /* Films are invented. Real titles drag in studio-owned posters and nothing here
      needs them to be real. */
   function buildShowtimes(): Showtime[] {
     const films = [
       { title: "The Long Quiet", runtime: 128 },
       { title: "Saltwater", runtime: 112 },
       { title: "Paper Moons", runtime: 99 },
     ];
   
     /* Midnight today in India, as an India wall clock. */
     const midnightInIndia = new Date(Date.now() + IST_OFFSET_MS);
     midnightInIndia.setUTCHours(0, 0, 0, 0);
   
     /* Chosen so every rule is reachable: the daytime weekday screenings fire
        nothing, the evening ones fire the evening band, and Friday and Saturday
        add the weekend band on top. No price per slot any more: a seat's price
        comes from its hall class and tier. */
     const slots = [
       { film: 0, screen: 1, hour: 10, minute: 0 },
       { film: 0, screen: 1, hour: 13, minute: 20 },
       { film: 0, screen: 1, hour: 19, minute: 10 },
       { film: 1, screen: 2, hour: 12, minute: 10 },
       { film: 1, screen: 2, hour: 18, minute: 20 },
       { film: 1, screen: 2, hour: 21, minute: 40 },
       { film: 2, screen: 3, hour: 11, minute: 20 },
       { film: 2, screen: 3, hour: 17, minute: 10 },
       { film: 2, screen: 4, hour: 14, minute: 40 },
       { film: 0, screen: 4, hour: 20, minute: 20 },
     ];
   
     return slots.map((slot, index) => {
       /* Built as an India wall clock, then shifted back to a real instant. */
       const india = new Date(midnightInIndia);
       india.setUTCHours(slot.hour, slot.minute, 0, 0);
       const startsAt = new Date(india.getTime() - IST_OFFSET_MS);
   
       /* Anything already past rolls to tomorrow, so the list never shows a
          screening that has been and gone, which the real API now rejects
          outright with a 409. */
       if (startsAt.getTime() < Date.now()) {
         startsAt.setUTCDate(startsAt.getUTCDate() + 1);
       }
   
       return {
         id: index + 1,
         filmTitle: films[slot.film].title,
         screenName: `Screen ${slot.screen}`,
         startsAt: startsAt.toISOString(),
         runtimeMinutes: films[slot.film].runtime,
         /* Both prices depend on how full the house is, so they are worked out
            per request in the handler rather than frozen into this list. */
         basePriceMinor: 0,
         priceMinor: 0,
         currency: CURRENCY,
         seatsAvailable: 0,
         seatsTotal: SEATS_TOTAL,
       };
     });
   }
   
   export const SHOWTIMES: Showtime[] = buildShowtimes();
   
   export function findShowtime(showtimeId: number): Showtime | undefined {
     return SHOWTIMES.find((s) => s.id === showtimeId);
   }
   
   /* ------------------------------------------- pre-existing occupancy */
   
   /* Showtime 5 is seeded full so the sold-out card is always visible. */
   const SOLD_OUT_SHOWTIMES = new Set([5]);
   
   /* Derived from the ids rather than random, so a reload shows the same cinema. */
   function seededBooked(showtimeId: number, seatId: number): boolean {
     if (SOLD_OUT_SHOWTIMES.has(showtimeId)) return true;
     return (seatId * 7 + showtimeId * 13) % 11 < 3;
   }
   
   /* Other people's live holds. Permanent, since there is nobody to let them lapse. */
   function seededHeld(showtimeId: number, seatId: number): boolean {
     return !seededBooked(showtimeId, seatId) && (seatId * 5 + showtimeId * 3) % 17 === 0;
   }
   
   interface HoldRecord {
     holdId: string;
     showtimeId: number;
     /* The hold is the unit: it expires whole and converts whole, so there is no
        per-seat state here. */
     seatIds: number[];
     /* Frozen at claim time and never recomputed, which is what makes the quoted
        price the charged price. A hold covers one tier, so one breakdown and one
        per-seat figure describe all of it. */
     pricing: Pricing;
     pricePerSeatMinor: number;
     expiresAt: number; // epoch ms
     convertedAt: number | null;
   }
   
   const holds = new Map<string, HoldRecord>();
   const bookings = new Map<string, Booking>();
   const bookedKeys = new Set<string>(); // "showtimeId:seatId" booked this session
   
   const key = (showtimeId: number, seatId: number) => `${showtimeId}:${seatId}`;
   
   export const getHoldRecord = (holdId: string) => holds.get(holdId);
   export const getBooking = (reference: string) => bookings.get(reference);
   
   function isBooked(showtimeId: number, seatId: number): boolean {
     return bookedKeys.has(key(showtimeId, seatId)) || seededBooked(showtimeId, seatId);
   }
   
   function activeHold(showtimeId: number, seatId: number): HoldRecord | undefined {
     const now = Date.now();
     for (const hold of holds.values()) {
       if (
         hold.showtimeId === showtimeId &&
         hold.seatIds.includes(seatId) &&
         hold.convertedAt === null &&
         hold.expiresAt > now
       ) {
         return hold;
       }
     }
     return undefined;
   }
   
   /* Nothing stores HELD against a seat: it is worked out per request from
      bookings and unexpired holds, which is why a hold expires with no timer and
      no cleanup job. Same rule as the seat_availability view. */
   export function seatStatus(showtimeId: number, seatId: number): SeatStatus {
     if (isBooked(showtimeId, seatId)) return "BOOKED";
     if (seededHeld(showtimeId, seatId)) return "HELD";
     if (activeHold(showtimeId, seatId)) return "HELD";
     return "AVAILABLE";
   }
   
   export function seatsAvailableFor(showtimeId: number): number {
     return SEATS.reduce(
       (n, seat) => n + (seatStatus(showtimeId, seat.id) === "AVAILABLE" ? 1 : 0),
       0,
     );
   }
   
   /* ---------------------------------------------------------------- pricing */
   
   /* Booked plus held, which is what the occupancy rule counts. Stands in for
      showtime_occupancy. */
   export function seatsTakenFor(showtimeId: number): number {
     return SEATS_TOTAL - seatsAvailableFor(showtimeId);
   }
   
   export function pricingFor(showtime: Showtime, baseMinor: number): Pricing {
     return priceFor(
       baseMinor,
       seatsTakenFor(showtime.id),
       SEATS_TOTAL,
       new Date(showtime.startsAt),
       new Date(),
     );
   }
   
   /* The handler checks the showtime first, so the empty case is for the type,
      not a real one. */
   export function seatMapFor(showtimeId: number): Seat[] {
     const showtime = findShowtime(showtimeId);
     if (!showtime) return [];
   
     /* One pricing call per distinct listed price, so three for a house of 120
        rather than 120. The rules are facts about the screening; only the base
        they scale differs between tiers. */
     const byBase = new Map<number, Pricing>();
   
     return SEATS.map((seat) => {
       const baseMinor = listedPriceMinor(showtime, seat);
       if (!byBase.has(baseMinor)) byBase.set(baseMinor, pricingFor(showtime, baseMinor));
   
       return {
         id: seat.id,
         rowLabel: seat.rowLabel,
         seatNumber: seat.seatNumber,
         status: seatStatus(showtimeId, seat.id),
         tier: seat.tier,
         tierName: SEAT_TIERS[seat.tier].displayName,
         basePriceMinor: baseMinor,
         priceMinor: byBase.get(baseMinor)!.adjustedMinor,
         currency: showtime.currency,
       };
     });
   }
   
   /* The percentages for the whole map. Any tier's breakdown answers for all of
      them, because only the base differs, so this uses the cheapest. */
   export function priceRatesFor(showtimeId: number): PriceRate[] {
     const showtime = findShowtime(showtimeId);
     if (!showtime) return [];
     const cheapest = LISTED_PRICE_MINOR[hallClassOf(showtime.screenName)].NORMAL;
     return asRates(pricingFor(showtime, cheapest));
   }
   
   /* The cheapest seat actually on sale, for the showtimes list. */
   export function fromPriceFor(showtimeId: number): { basePriceMinor: number; priceMinor: number } {
     const showtime = findShowtime(showtimeId);
     if (!showtime) return { basePriceMinor: 0, priceMinor: 0 };
     const cheapest = LISTED_PRICE_MINOR[hallClassOf(showtime.screenName)].NORMAL;
     return { basePriceMinor: cheapest, priceMinor: pricingFor(showtime, cheapest).adjustedMinor };
   }
   
   /* ----------------------------------------------------------------- writes */
   
   export type HoldOutcome =
     | { ok: true; hold: HoldRecord; seats: SeatPosition[] }
     | { ok: false; reason: "no-such-showtime" }
     | { ok: false; reason: "no-such-seat"; seatId: number }
     | { ok: false; reason: "unavailable"; unavailable: UnavailableSeat[] };
   
   /* All or nothing: every seat is checked before anything is written, so a losing
      claim leaves no hold behind. */
   export function createHold(showtimeId: number, seatIds: number[]): HoldOutcome {
     const showtime = findShowtime(showtimeId);
     if (!showtime) return { ok: false, reason: "no-such-showtime" };
   
     for (const seatId of seatIds) {
       if (!seatById(seatId)) return { ok: false, reason: "no-such-seat", seatId };
     }
   
     const unavailable: UnavailableSeat[] = seatsInOrder(seatIds)
       .map((seat) => ({ seat, status: seatStatus(showtimeId, seat.id) }))
       .filter(({ status }) => status !== "AVAILABLE")
       .map(({ seat, status }) => ({
         seatId: seat.id,
         rowLabel: seat.rowLabel,
         seatNumber: seat.seatNumber,
         status,
       }));
   
     if (unavailable.length > 0) return { ok: false, reason: "unavailable", unavailable };
   
     const seats = seatsInOrder([...seatIds].sort((a, b) => a - b));
   
     /* Priced before the hold is written, so the request's own seats are not
        counted in its own occupancy. The first seat's listed price is the base
        for the whole hold, which holds because every seat on a hold shares a row
        and tier follows the row band, exactly as on the server. */
     const pricing = pricingFor(showtime, listedPriceMinor(showtime, seats[0]));
   
     const hold: HoldRecord = {
       /* v4 here; the real service issues v7 so the index stays time-ordered. */
       holdId: crypto.randomUUID(),
       showtimeId,
       seatIds: seats.map((seat) => seat.id),
       pricing,
       pricePerSeatMinor: pricing.adjustedMinor,
       expiresAt: Date.now() + HOLD_TTL_SECONDS * 1000,
       convertedAt: null,
     };
     holds.set(hold.holdId, hold);
     return { ok: true, hold, seats };
   }
   
   export type ReleaseOutcome =
     | { ok: true }
     | { ok: false; reason: "no-such-hold" | "already-converted" };
   
   /* Pushing expiry into the past frees every seat on the hold, because status is
      derived from it. Nothing else needs updating. */
   export function releaseHold(holdId: string): ReleaseOutcome {
     const hold = holds.get(holdId);
     if (!hold) return { ok: false, reason: "no-such-hold" };
     if (hold.convertedAt !== null) return { ok: false, reason: "already-converted" };
     hold.expiresAt = Date.now() - 1;
     return { ok: true };
   }
   
   export type BookingOutcome =
     | { ok: true; booking: Booking }
     | { ok: false; reason: "no-such-hold" | "expired" | "already-converted" };
   
   export function createBooking(holdId: string): BookingOutcome {
     const hold = holds.get(holdId);
     if (!hold) return { ok: false, reason: "no-such-hold" };
     if (hold.convertedAt !== null) return { ok: false, reason: "already-converted" };
     if (hold.expiresAt <= Date.now()) return { ok: false, reason: "expired" };
   
     const showtime = findShowtime(hold.showtimeId);
     const seats = seatsInOrder(hold.seatIds);
     if (!showtime || seats.length !== hold.seatIds.length) {
       return { ok: false, reason: "no-such-hold" };
     }
   
     hold.convertedAt = Date.now();
     for (const seat of seats) {
       bookedKeys.add(key(hold.showtimeId, seat.id));
     }
   
     /* The price comes off the hold, never recomputed. Once occupancy is an
        input, working it out again here would charge a number the customer never
        saw, and the house has very likely filled since they picked their seats.
        No stored total: it is the sum of the lines. */
     const bookedSeats: BookedSeat[] = seats.map((seat) => ({
       rowLabel: seat.rowLabel,
       seatNumber: seat.seatNumber,
       tier: seat.tier,
       pricePaidMinor: hold.pricePerSeatMinor,
     }));
   
     const booking: Booking = {
       bookingReference: newBookingReference(),
       showtimeId: showtime.id,
       filmTitle: showtime.filmTitle,
       screenName: showtime.screenName,
       startsAt: showtime.startsAt,
       seats: bookedSeats,
       totalPaidMinor: bookedSeats.reduce((sum, seat) => sum + seat.pricePaidMinor, 0),
       currency: showtime.currency,
       bookedAt: new Date().toISOString(),
     };
     bookings.set(booking.bookingReference, booking);
     return { ok: true, booking };
   }
   
   /* No I, L, O, U, 0 or 1, so the code survives being read out over a phone. */
   const REFERENCE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ";
   
   function newBookingReference(): string {
     const bytes = crypto.getRandomValues(new Uint8Array(6));
     const code = Array.from(
       bytes,
       (b) => REFERENCE_ALPHABET[b % REFERENCE_ALPHABET.length],
     ).join("");
     return `SB-${code}`;
   }
   
   export function remainingSeconds(expiresAt: number): number {
     return Math.max(0, Math.ceil((expiresAt - Date.now()) / 1000));
   }
   
   /* ------------------------------------------------------- idempotency keys */
   
   /* Mirrors the idempotency_key table. No in-progress state: JavaScript handles
      one request at a time here, so the window the real server has to guard
      against cannot open. */
   const idempotencyKeys = new Map<string, { requestHash: string; reference: string }>();
   
   export const findIdempotency = (idempotencyKey: string) =>
     idempotencyKeys.get(idempotencyKey);
   
   export const rememberIdempotency = (
     idempotencyKey: string,
     requestHash: string,
     reference: string,
   ) => idempotencyKeys.set(idempotencyKey, { requestHash, reference });