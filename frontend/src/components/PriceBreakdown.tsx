/* Why a hold costs what it costs.

   Renders rows into the checkout's own <dl>, which is why it has no container
   of its own: a wrapper here would nest a list inside a list for no reason.

   One block per tier on the hold. The rules are percentages of a base, so a
   hold across two tiers has two bases and two sets of amounts, and each block
   adds up to its own subtotal. A hold in one tier is one block with no
   heading, which is the ordinary case and reads as it always did.

   Every figure is the per-seat amount times that tier's seat count, because
   the total underneath is per hold. A breakdown whose lines do not add up to
   the number being charged is worse than no breakdown. */

import type { PricingGroup } from "@/types/api";
import { money } from "@/utils/format";
import { TIERS } from "@/utils/seatTiers";

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
  groups,
  currency,
}: {
  groups: PricingGroup[];
  currency: string;
}) {
  /* A heading per tier only when there is more than one. */
  const showHeadings = groups.length > 1;

  return (
    <>
      {groups.map((group) => (
        <TierBlock
          key={group.tier}
          group={group}
          currency={currency}
          heading={showHeadings}
        />
      ))}
    </>
  );
}

function TierBlock({
  group,
  currency,
  heading,
}: {
  group: PricingGroup;
  currency: string;
  heading: boolean;
}) {
  const seats = group.seatCount === 1 ? "1 seat" : `${group.seatCount} seats`;

  return (
    <>
      {/* The tier's name and its subtotal, which is the figure the lines
          below add up to. */}
      {heading && (
        <div className={styles.heading}>
          <dt className={styles.label}>{TIERS[group.tier].label}</dt>
          <dd className={`${styles.amount} tnum`}>
            {money(group.adjustedMinor * group.seatCount, currency)}
          </dd>
        </div>
      )}

      <div className={styles.row}>
        <dt className={styles.label}>
          {seats} at {money(group.baseMinor, currency)}
        </dt>
        <dd className={`${styles.amount} tnum`}>
          {money(group.baseMinor * group.seatCount, currency)}
        </dd>
      </div>

      {/* Only the rules that moved the price arrive here; the server filters
          the bands that did not fire. An empty list leaves just the line
          above, which is the ordinary case and not a missing panel. */}
      {group.rules.map((rule) => (
        <div key={rule.code} className={styles.row}>
          <dt className={styles.label}>
            {rule.label}
            <span className={`${styles.percent} tnum`}>
              {percent(rule.basisPoints)}
            </span>
          </dt>
          <dd className={`${styles.amount} tnum`}>
            {signed(rule.adjustmentMinor * group.seatCount, currency)}
          </dd>
        </div>
      ))}
    </>
  );
}