/* Timezone arithmetic for demo mode.

   The real service never does this: Postgres owns it, with AT TIME ZONE
   reading cinema.timezone as a column. This is the same three steps in
   JavaScript, for the mock only, and it is honest about being an
   approximation. */

/* A zone's offset from UTC at a given instant, in milliseconds.

   Intl is the only thing in the browser that knows a zone's rules, and it will
   only tell you by formatting. So this formats the instant in the zone, reads
   the wall clock back out, and treats that reading as if it were UTC: the
   difference is the offset. Positive for Asia/Kolkata, zero or an hour for
   Europe/London depending on the date.

   hourCycle h23 rather than hour12 false, because the latter prints midnight
   as 24 in some engines and the arithmetic would silently land a day out. */
   function offsetMs(timeZone: string, at: Date): number {
    const parts = new Intl.DateTimeFormat("en-US", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(at)
      .filter((part) => part.type !== "literal");
  
    const field = (type: string) =>
      Number(parts.find((part) => part.type === type)?.value ?? 0);
  
    const asIfUtc = Date.UTC(
      field("year"),
      field("month") - 1,
      field("day"),
      field("hour"),
      field("minute"),
      field("second"),
    );
  
    return asIfUtc - at.getTime();
  }
  
  /**
   * A wall clock reading at a cinema, as a real instant.
   *
   * Today's date in the cinema's zone, plus dayOffset days, at the given local
   * time. The same order as V13's SQL: find the local date, build a local
   * timestamp on it, then interpret that in the zone.
   *
   * The offset is read once, at the current instant, and reused for the whole
   * week. That is wrong for a screening on the far side of a daylight saving
   * change by exactly one hour. Accepted here, and it is the reason the real
   * seeder does this in Postgres instead.
   */
  export function localInstant(
    timeZone: string,
    dayOffset: number,
    hour: number,
    minute: number,
  ): Date {
    const now = new Date();
    const offset = offsetMs(timeZone, now);
  
    /* Shifted into the zone's wall clock, then manipulated with the UTC setters
       so the browser's own zone never enters the arithmetic. */
    const wall = new Date(now.getTime() + offset);
    wall.setUTCDate(wall.getUTCDate() + dayOffset);
    wall.setUTCHours(hour, minute, 0, 0);
  
    return new Date(wall.getTime() - offset);
  }
  
  /**
   * The hour and the weekday an instant falls on, at a cinema.
   *
   * 0 is Sunday, matching Date.getUTCDay, so the weekend band in pricing.ts can
   * keep the day numbers it already has.
   */
  export function localHourAndDay(
    instant: Date,
    timeZone: string,
  ): { hour: number; day: number } {
    const shifted = new Date(instant.getTime() + offsetMs(timeZone, instant));
    return { hour: shifted.getUTCHours(), day: shifted.getUTCDay() };
  }