/* Shapes defined by docs/api-contract.md. Hand-written rather than generated,
   so the contract stays readable and any drift between client and server shows
   up here as a type error instead of a runtime surprise. */

   export type SeatStatus = "AVAILABLE" | "HELD" | "BOOKED";
   export type HoldStatus = "ACTIVE" | "EXPIRED" | "CONVERTED";
   
   export interface Showtime {
    id: number;
        /* The cinema this screening is at, so a page reached by showtime id alone
       can still name the building and format the time in its zone. */
    cinemaId: number;
    filmTitle: string;
    screenName: string;
    startsAt: string;
    runtimeMinutes: number;
    /* The cheapest seat's listed price, before any rule. Integer minor units. */
    basePriceMinor: number;
    /* The cheapest seat actually on sale, after the rules. Show this as the
       "from" price: a headline price nobody can buy at is misleading. */
    priceMinor: number;
    currency: string;
    seatsAvailable: number;
    seatsTotal: number;
  }

     /* A cinema, as GET /api/cinemas returns it. The two coordinates are what a
      map marker needs; MapLibre takes them as [lon, lat], which is the opposite
      order to how they are named here and on the wire. */
      export interface Cinema {
        id: number;
        name: string;
        city: string;
        state: string;
        latitude: number;
        longitude: number;
        /* ISO 4217, and the cinema's, not the customer's. Nothing in this system
           converts between currencies, so an amount is only meaningful next to the
           code it was priced in. */
        currency: string;
        screenCount: number;
      }
    
      /* GET /api/cinemas/nearest.
    
         extends rather than repeating the fields. The server repeats them because a
         Java record cannot extend another, which is a language limitation and not a
         statement about the contract: the two responses really are the same shape
         plus a distance. */
      export interface NearestCinema extends Cinema {
        /* Whole metres, rounded server-side. Sub-metre precision on the position of
           a building is noise. */
        distanceMetres: number;
      }
    
      /* GET /api/cinemas/{cinemaId}. */
      export interface CinemaDetail extends Cinema {
        address: string;
        /* An IANA zone name. Sent so a screening prints at its own cinema's local
           time rather than the browser's: 20:00 in Jaipur is 20:00 to the people in
           the room, and startsAt is an instant, so the client cannot work that out
           on its own. */
        timezone: string;
      }
   
   export type Tier = "NORMAL" | "PREMIUM" | "EXECUTIVE";
   
   export interface Seat {
    id: number;
    rowLabel: string;
    seatNumber: number;
    status: SeatStatus;
    tier: Tier;
    /* Display label for the tier, sent by the server so the code-to-text
       mapping lives in one place. */
    tierName: string;
    /* The listed price for this hall class and tier, before any rule. */
    basePriceMinor: number;
    /* What it costs right now, after the rules. Equal to basePriceMinor when no
       rule is firing. Not frozen: a price is only promised once a hold exists. */
    priceMinor: number;
    currency: string;
  }
  
  export interface SeatMap {
    showtimeId: number;
    seats: Seat[];
    /* Only the rules currently moving prices, as percentages. Empty means every
       seat is at its listed price. The money amounts are per tier, so they live
       on each seat rather than here. */
    priceRules: PriceRate[];
  }
  
  export interface PriceRate {
    code: PriceRuleCode;
    label: string;
    basisPoints: number;
  }
   
   export interface HeldSeat {
    seatId: number;
    rowLabel: string;
    seatNumber: number;
    tier: Tier;
    /* Integer minor units, frozen at claim time. This is the number that will be
       charged, not today's price. */
    priceMinor: number;
  }
  
  export type PriceRuleCode =
  | "OCCUPANCY"
  | "LEAD_TIME"
  | "TIME_OF_DAY"
  | "DAY_OF_WEEK"
  | "CAP";

export interface PriceLine {
  code: PriceRuleCode;
  /* Resolved copy from the server, snapshotted when the hold was taken, so an
     old booking keeps the wording it was shown. */
  label: string;
  /* Integer basis points. 2000 is 20 percent. Divide by 100 to display. */
  basisPoints: number;
  /* What that percentage came to, in minor units. Signed: the cap is negative. */
  adjustmentMinor: number;
}

export interface Pricing {
  /* The listed price for this hall class and tier, before any rule. */
  baseMinor: number;
  /* What one seat actually costs. */
  adjustedMinor: number;
  /* Only the rules that moved the price. Empty means the listed price stands,
     which the UI should render as one line, not an empty panel. */
  rules: PriceLine[];
}

  export interface Hold {
    holdId: string;
    showtimeId: number;
    seats: HeldSeat[];
    totalMinor: number;
    currency: string;
    pricing: Pricing;
    expiresAt: string;
    ttlSeconds: number;
  }
   
   export interface HoldDetail extends Hold {
     status: HoldStatus;
   }
   
   export interface BookedSeat {
     rowLabel: string;
     seatNumber: number;
     tier: Tier;
     /* What was actually charged for this seat, so a later price change never
        rewrites a receipt. */
     pricePaidMinor: number;
   }
   
   export interface Booking {
     /* Short code meant to be read aloud, not the primary key. */
     bookingReference: string;
     showtimeId: number;
     filmTitle: string;
     screenName: string;
     startsAt: string;
     seats: BookedSeat[];
     totalPaidMinor: number;
     currency: string;
     bookedAt: string;
   }
   
   export interface CreateHoldRequest {
     showtimeId: number;
     /* 1 to 3 as a guest, 1 to 6 signed in, no repeats. A repeated seat is
        rejected rather than deduped. */
     seatIds: number[];
   }
   
   export interface CreateBookingRequest {
     /* The hold already knows its seats and showtime, so sending them again would
        allow a combination that disagrees with the server. */
     holdId: string;
     /* Optional, and for guests: it is how a confirmation reaches someone with no
        account. Never used to decide who a booking belongs to. A signed-in
        booking is attributed from the session cookie instead. */
     email?: string;
   }
   
   /**
    * One seat that could not be claimed, from the seats-unavailable 409's
    * unavailableSeats member. Only the lost seats are listed: requested minus
    * these is the set still wanted, which is also the retry payload.
    */
   export interface UnavailableSeat {
     seatId: number;
     rowLabel: string;
     seatNumber: number;
     status: SeatStatus;
   }
   
   /* The signed-in account, as /api/me and every auth endpoint return it. No
      token: the session is in cookies the page cannot read. */
   export interface User {
     id: string;
     email: string;
     displayName: string;
     createdAt: string;
   }
   
   export interface SignupRequest {
     email: string;
     /* 8 to 72 characters. The ceiling is BCrypt's, which ignores everything past
        72 bytes, so a longer password would have a silent dead tail. */
     password: string;
     displayName: string;
   }
   
   export interface LoginRequest {
     email: string;
     password: string;
   }

   export interface ForgotPasswordRequest {
    email: string;
  }
  
  export interface ResetPasswordRequest {
    /* From the emailed link. The token names the account, which is why no
       address is sent: trusting one here would let anyone with any valid token
       reset anybody. */
    token: string;
    /* 8 to 72 characters, same bounds as signup. */
    password: string;
  }
