/* Money is stored and sent as integer minor units. It becomes a decimal string
   here and nowhere else.

   Fraction digits are all or nothing. A whole number of rupees prints as 350,
   and anything else prints both digits as 367.50. Never one digit: a 5% band
   on a 350 listing is 17.50, so the half rupee is real and has to show, but
   "367.5" is not a way anybody writes money. The amount decides, which is why
   minimumFractionDigits is computed rather than fixed. */
   export function money(minor: number, currency: string): string {
    const wholeUnits = minor % 100 === 0;
    return new Intl.NumberFormat("en-GB", {
      style: "currency",
      currency,
      minimumFractionDigits: wholeUnits ? 0 : 2,
      maximumFractionDigits: 2,
    }).format(minor / 100);
  }
  
  export function clockTime(iso: string): string {
    return new Date(iso).toLocaleTimeString("en-GB", {
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    });
  }
  
  export function dayAndTime(iso: string): string {
    const day = new Date(iso).toLocaleDateString("en-GB", {
      weekday: "short",
      day: "numeric",
      month: "short",
    });
    return `${day}, ${clockTime(iso)}`;
  }
  
  export function runtime(minutes: number): string {
    return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, "0")}m`;
  }
  
  export function mmss(totalSeconds: number): string {
    const s = Math.max(0, totalSeconds);
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
  }
  
  export const seatLabel = (rowLabel: string, seatNumber: number): string =>
    `${rowLabel}${seatNumber}`;
  
  /* "A2" or "A2, A3, F1". Used wherever a hold or booking names its seats. */
  export const seatNames = (
    seats: { rowLabel: string; seatNumber: number }[],
  ): string => seats.map((s) => seatLabel(s.rowLabel, s.seatNumber)).join(", ");