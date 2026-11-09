/* A second implementation of the backend's PricingRules, for demo mode.
   
   Two implementations of one rule set will drift, and that is accepted here for
   one reason: API_MODE defaults to mock, so the deployed demo is what a
   recruiter actually clicks, and a demo where the breakdown never renders does
   not show the feature the wave exists for. The DEMO badge says which mode the
   viewer is in.
   
   The bands below mirror screenbook.pricing.* in application.properties. If you
   change one, change both. */

   import type { PriceLine, PriceRate, Pricing, Tier } from "@/types/api";

   export type HallClass = "STANDARD" | "LUXURY";
   
   /* Stands in for hall_class_tier_price in migration V12. Hand-set figures, in
      paise, not computed from a base plus an uplift. */
   export const LISTED_PRICE_MINOR: Record<HallClass, Record<Tier, number>> = {
     STANDARD: { NORMAL: 20_000, PREMIUM: 35_000, EXECUTIVE: 50_000 },
     LUXURY: { NORMAL: 30_000, PREMIUM: 55_000, EXECUTIVE: 80_000 },
   };
   
   const BANDS = {
     occupancy: {
       nearlyFullPercent: 80,
       nearlyFullBp: 2000,
       halfFullPercent: 50,
       halfFullBp: 1000,
     },
     leadTime: { imminentHours: 6, imminentBp: 1000, soonHours: 48, soonBp: 500 },
     evening: { fromHour: 17, toHour: 22, bp: 500 },
     /* 5 is Friday, 6 is Saturday. */
     weekend: { days: [5, 6], bp: 500 },
     maxAdjustmentBp: 4000,
   };
   
   /* India Standard Time is UTC+5:30 and has no daylight saving, so a fixed
      offset is exact. The server uses a ZoneId because it has to be correct for
      zones in general; here one number is honest and avoids an Intl round trip
      per seat. */
   const IST_OFFSET_MS = 5.5 * 60 * 60 * 1000;
   
   const inIndia = (instant: Date) => new Date(instant.getTime() + IST_OFFSET_MS);
   
   /* Half away from zero, in integers. 10000 basis points is 100%. */
   export function applyBp(baseMinor: number, basisPoints: number): number {
     const n = baseMinor * basisPoints;
     return n >= 0
       ? Math.floor((n + 5_000) / 10_000)
       : -Math.floor((-n + 5_000) / 10_000);
   }
   
   function line(
     code: PriceLine["code"],
     label: string,
     basisPoints: number,
     baseMinor: number,
   ): PriceLine {
     return { code, label, basisPoints, adjustmentMinor: applyBp(baseMinor, basisPoints) };
   }
   
   function occupancyLine(baseMinor: number, taken: number, total: number): PriceLine {
     const percent = total <= 0 ? 0 : Math.floor((taken * 100) / total);
     const { nearlyFullPercent, nearlyFullBp, halfFullPercent, halfFullBp } = BANDS.occupancy;
   
     if (percent >= nearlyFullPercent) {
       return line("OCCUPANCY", "Nearly full", nearlyFullBp, baseMinor);
     }
     if (percent >= halfFullPercent) {
       return line("OCCUPANCY", "Filling up", halfFullBp, baseMinor);
     }
     return line("OCCUPANCY", "Quiet", 0, baseMinor);
   }
   
   function leadTimeLine(baseMinor: number, startsAt: Date, now: Date): PriceLine {
     const hours = Math.floor((startsAt.getTime() - now.getTime()) / 3_600_000);
     const { imminentHours, imminentBp, soonHours, soonBp } = BANDS.leadTime;
   
     if (hours < imminentHours) {
       return line("LEAD_TIME", `Under ${imminentHours} hours to showtime`, imminentBp, baseMinor);
     }
     if (hours < soonHours) {
       return line("LEAD_TIME", `Under ${soonHours} hours to showtime`, soonBp, baseMinor);
     }
     return line("LEAD_TIME", "Plenty of time", 0, baseMinor);
   }
   
   function timeOfDayLine(baseMinor: number, startsAt: Date): PriceLine {
     const hour = inIndia(startsAt).getUTCHours();
     const evening = hour >= BANDS.evening.fromHour && hour <= BANDS.evening.toHour;
     return evening
       ? line("TIME_OF_DAY", "Evening show", BANDS.evening.bp, baseMinor)
       : line("TIME_OF_DAY", "Daytime show", 0, baseMinor);
   }
   
   function dayOfWeekLine(baseMinor: number, startsAt: Date): PriceLine {
     const day = inIndia(startsAt).getUTCDay();
     return BANDS.weekend.days.includes(day)
       ? line("DAY_OF_WEEK", "Weekend", BANDS.weekend.bp, baseMinor)
       : line("DAY_OF_WEEK", "Weekday", 0, baseMinor);
   }
   
   /**
    * Every rule returns a line, including the zero bands, exactly as the server
    * stores them. visibleRules is what the API would send.
    */
   export function priceFor(
     baseMinor: number,
     seatsTaken: number,
     seatsTotal: number,
     startsAt: Date,
     now: Date,
   ): Pricing {
     const rules: PriceLine[] = [
       occupancyLine(baseMinor, seatsTaken, seatsTotal),
       leadTimeLine(baseMinor, startsAt, now),
       timeOfDayLine(baseMinor, startsAt),
       dayOfWeekLine(baseMinor, startsAt),
     ];
   
     const totalBp = rules.reduce((sum, rule) => sum + rule.basisPoints, 0);
     let totalAdjustment = rules.reduce((sum, rule) => sum + rule.adjustmentMinor, 0);
   
     if (totalBp > BANDS.maxAdjustmentBp) {
       /* The cap is a line, not a truncation, and its amount is the difference
          rather than applyBp of its own basis points, so the lines still sum
          exactly to the price charged. */
       const capped = applyBp(baseMinor, BANDS.maxAdjustmentBp);
       rules.push({
         code: "CAP",
         label: "Capped",
         basisPoints: BANDS.maxAdjustmentBp - totalBp,
         adjustmentMinor: capped - totalAdjustment,
       });
       totalAdjustment = capped;
     }
   
     return { baseMinor, adjustedMinor: baseMinor + totalAdjustment, rules };
   }
   
   /* Only the lines that moved the price. The zero bands are kept in the
      breakdown, as the server keeps them in the database, but a customer does not
      need to read "Occupancy, plus nothing". */
   export const visibleRules = (pricing: Pricing): PriceLine[] =>
     pricing.rules.filter((rule) => rule.basisPoints !== 0);
   
   /* The seat map spans three tiers, so it reports percentages and leaves each
      seat to carry its own money. */
   export const asRates = (pricing: Pricing): PriceRate[] =>
     visibleRules(pricing).map(({ code, label, basisPoints }) => ({ code, label, basisPoints }));