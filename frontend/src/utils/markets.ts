import type { Cinema } from "@/types/api";

/* The two markets the dataset covers.

   Country is not on the cinema contract yet. V22 adds cinema.country in wave
   7 and marketOf becomes one field read. Until then the currency is the
   honest proxy, because currency is already a property of the building for
   exactly the same reason the country is. Keeping it in one file is what
   makes wave 7 a one-line change rather than a search. */
export type Market = "GB" | "IN";

export const MARKETS: { id: Market; label: string; inSentence: string }[] = [
  { id: "IN", label: "India", inSentence: "India" },
  { id: "GB", label: "United Kingdom", inSentence: "the UK" },
];

/* The board opens on India. This opens on the UK, because the people being
   shown this are in the UK. One word. */
export const DEFAULT_MARKET: Market = "GB";

export const marketOf = (cinema: Cinema): Market =>
  cinema.currency === "GBP" ? "GB" : "IN";

/* Where the camera sits for each market.

   Not fitBounds. Fitting the data to the container is the right tool for a
   data map and the wrong one for a hero: the brief is "a planet, turned to
   this country", and the container is deliberately cropped on three sides, so
   a fit is fitting to a rectangle nobody can see all of.

   zoom is the one number to tune. Each whole step doubles the planet's
   diameter on screen, so 2.1 is a planet that overhangs the hero and 1.1 is
   one that sits inside it. */
export const CAMERA: Record<Market, { center: [number, number]; zoom: number }> = {
  GB: { center: [-3.2, 54.2], zoom: 2.8 },
  IN: { center: [79, 22], zoom: 2.8 },
};

