/* Why a hold costs what it costs.
   
   Renders rows into the checkout's own <dl>, which is why it has no container
   of its own: a wrapper here would nest a list inside a list for no reason.
   
   Every figure is the per-seat amount times the seat count, because the total
   underneath is per hold. A breakdown whose lines do not add up to the number
   being charged is worse than no breakdown. */

   import type { Pricing } from "@/types/api";
   import { money } from "@/utils/format";
   
   import styles from "@/css/PriceBreakdown.module.css";
   
   /* 2000 basis points is 20%. Signed, because the cap line is negative. */
   function percent(basisPoints: number): string {
     const sign = basisPoints > 0 ? "+" : "-";
     return `${sign}${Math.abs(basisPoints) / 100}%`;
   }
   
   function signed(minor: number, currency: string): string {
     const sign = minor > 0 ? "+" : "-";
     return `${sign}${money(Math.abs(minor), currency)}`;
   }
   
   export function PriceBreakdown({
     pricing,
     seatCount,
     currency,
   }: {
     pricing: Pricing;
     seatCount: number;
     currency: string;
   }) {
     const seats = seatCount === 1 ? "1 seat" : `${seatCount} seats`;
   
     return (
       <>
         <div className={styles.row}>
           <dt className={styles.label}>
             {seats} at {money(pricing.baseMinor, currency)}
           </dt>
           <dd className={`${styles.amount} tnum`}>
             {money(pricing.baseMinor * seatCount, currency)}
           </dd>
         </div>
   
         {/* Only the rules that moved the price arrive here; the server filters
             the bands that did not fire. An empty list leaves just the line
             above, which is the ordinary case and not a missing panel. */}
         {pricing.rules.map((rule) => (
           <div key={rule.code} className={styles.row}>
             <dt className={styles.label}>
               {rule.label}
               <span className={`${styles.percent} tnum`}>
                 {percent(rule.basisPoints)}
               </span>
             </dt>
             <dd className={`${styles.amount} tnum`}>
               {signed(rule.adjustmentMinor * seatCount, currency)}
             </dd>
           </div>
         ))}
       </>
     );
   }