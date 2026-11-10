/* A second implementation of the backend's PricingRules, for demo mode.

   Two implementations of one rule set will drift, and that is accepted here for
   one reason: API_MODE defaults to mock, so the deployed demo is what a
   recruiter actually clicks, and a demo where the breakdown never renders does
   not show the feature the wave exists for. The DEMO badge says which mode the
   viewer is in.

   The bands below mirror screenbook.pricing.* in application.properties. If you
   change one, change both. */

   import type { PriceLine, PriceRate, Pricing, Tier } from "@/types/api";

   import { localHourAndDay } from "./zones";
   
   export type HallClass = "STANDARD" | "LUXURY";
   
   /* Stands in for hall_class_tier_price, keyed on currency since V17.
   
      Two ladders, never converted between. The INR figures are V12's, in paise;
      the GBP figures are V18's, in pence. The UK ladder is flatter, 1.8x bottom to
      top against 2.5x, which is what the two markets look like rather than an
      oversight.
   
      Keyed by currency string rather than a union type, because the server keys it
      on a currency lookup table and a third currency there should not need a type
      change here. */
   export const LISTED_PRICE_MINOR: Record<
     string,
     Record<HallClass, Record<Tier, number>>
   > = {
     INR: {
       STANDARD: { NORMAL: 20_000, PREMIUM: 35_000, EXECUTIVE: 50_000 },
       LUXURY: { NORMAL: 30_000, PREMIUM: 55_000, EXECUTIVE: 80_000 },
     },
     GBP: {
       STANDARD: { NORMAL: 900, PREMIUM: 1_300, EXECUTIVE: 1_600 },
       LUXURY: { NORMAL: 1_400, PREMIUM: 1_900, EXECUTIVE: 2_500 },
     },
   };
   
   /* A missing currency would make every seat cost NaN, which renders as a blank
      price rather than an error, so it fails loudly here instead. The server has
      the same hole closed by a foreign key to the currency table. */
   export function listedPrice(
     currency: string,
     hallClass: HallClass,
     tier: Tier,
   ): number {
     const ladder = LISTED_PRICE_MINOR[currency];
     if (!ladder) throw new Error(`No price ladder for currency ${currency}.`);
     return ladder[hallClass][tier];
   }
   
   const BANDS = {
     occupancy: {
       nearlyFullPercent: 80,
       nearlyFullBp: 2000,
       halfFullPercent: 50,
       halfFullBp: 1000,
     },
     leadTime: { imminentHours: 6, imminentBp: 1000, soonHours: 48, soonBp: 500 },
     evening: { fromHour: 17, toHour: 22, bp: 500 },
     /* 5 is Friday, 6 is Saturday, matching Date.getUTCDay. */
     weekend: { days: [5, 6], bp: 500 },
     maxAdjustmentBp: 4000,
   };
   
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
   
   /* Evening and weekend are questions about the time where the cinema is, so the
      zone arrives as an argument. The previous version used one fixed IST offset,
      which was right while every cinema was in India and would have called a 19:00
      Liverpool screening a small-hours one.
   
      This is the change PricingRules still needs on the server, where the zone is
      read from screenbook.pricing.zone rather than from cinema.timezone. For now
      the mock is the more correct of the two. */
   function timeOfDayLine(baseMinor: number, startsAt: Date, timeZone: string): PriceLine {
     const { hour } = localHourAndDay(startsAt, timeZone);
     const evening = hour >= BANDS.evening.fromHour && hour <= BANDS.evening.toHour;
     return evening
       ? line("TIME_OF_DAY", "Evening show", BANDS.evening.bp, baseMinor)
       : line("TIME_OF_DAY", "Daytime show", 0, baseMinor);
   }
   
   function dayOfWeekLine(baseMinor: number, startsAt: Date, timeZone: string): PriceLine {
     const { day } = localHourAndDay(startsAt, timeZone);
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
     timeZone: string,
   ): Pricing {
     const rules: PriceLine[] = [
       occupancyLine(baseMinor, seatsTaken, seatsTotal),
       leadTimeLine(baseMinor, startsAt, now),
       timeOfDayLine(baseMinor, startsAt, timeZone),
       dayOfWeekLine(baseMinor, startsAt, timeZone),
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