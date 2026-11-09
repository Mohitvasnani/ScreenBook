/* Money is stored and sent as integer minor units. It becomes a decimal string
   here and nowhere else.

   Fraction digits are 0 to 2 rather than a fixed 2. Every listed price is a
   whole number of rupees and reads as one, while a 5% band on an odd price can
   produce a genuine 50 paise line and that still shows correctly. */
   export function money(minor: number, currency: string): string {
    return new Intl.NumberFormat("en-GB", {
      style: "currency",
      currency,
      minimumFractionDigits: 0,
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