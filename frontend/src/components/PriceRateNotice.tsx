/* Why every price on this seat map is above its listed figure.
   
   Percentages, no money. The map spans three tiers and each rule is a
   percentage of each tier's own listed price, so a single money figure here
   would be wrong for two of the three. The checkout gets the money version,
   where a hold is always one tier.
   
   Labels are the server's copy, used as sent rather than shortened: they are a
   snapshot of what a customer was told, and paraphrasing them here would mean
   the seat map and the receipt describe the same rule differently. */

   import type { PriceRate } from "@/types/api";

   import styles from "@/css/PriceRateNotice.module.css";
   
   /* 2000 basis points is 20%. Signed, because a discount band would be negative. */
   const percent = (basisPoints: number): string =>
     `${basisPoints > 0 ? "+" : "-"}${Math.abs(basisPoints) / 100}%`;
   
   export function PriceRateNotice({ rules }: { rules: PriceRate[] }) {
     /* Nothing firing is the ordinary case: every seat is at its listed price and
        there is nothing to explain. An empty panel would imply otherwise. */
     if (rules.length === 0) return null;
   
     return (
       <div className={styles.root}>
         <p className={styles.label}>Prices are higher than usual</p>
         <ul className={styles.rules}>
           {rules.map((rule) => (
             <li key={rule.code} className={styles.rule}>
               {rule.label}
               <span className={`${styles.percent} tnum`}>{percent(rule.basisPoints)}</span>
             </li>
           ))}
         </ul>
         <p className={styles.note}>
           Hold your seats and the price stops moving.
         </p>
       </div>
     );
   }