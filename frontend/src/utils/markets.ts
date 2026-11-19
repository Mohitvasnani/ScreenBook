import type { Cinema } from "@/types/api";

/* The two markets the dataset covers.

   Country is not on the cinema contract yet. V22 adds cinema.country in wave
   7 and marketOf becomes one field read. Until then the currency is the
   honest proxy, because currency is already a property of the building for
   exactly the same reason the country is. */
export type Market = "GB" | "IN";

export const MARKETS: { id: Market; label: string; inSentence: string }[] = [
  { id: "IN", label: "India", inSentence: "India" },
  { id: "GB", label: "United Kingdom", inSentence: "the UK" },
];

export const marketOf = (cinema: Cinema): Market =>
  cinema.currency === "GBP" ? "GB" : "IN";

/* The opening shot, and the only fixed camera left in the project. Everything
   else is a fit to the thing being talked about, so there is nothing else to
   tune by eye.

   bearing turns the planet's axis off vertical, which is what makes it read
   as an object rather than a diagram. pitch stays at zero this far out: an
   oblique sphere at planet distance looks like a rendering mistake, not a
   tilt. The pitch arrives on the way down. */
export const PLANET = {
  center: [34, 34] as [number, number],
  zoom: 1.6,
  bearing: -22,
  pitch: 0,
};

/* Applied the moment the camera is framing something real. */
export const GROUND_BEARING = -22;
export const GROUND_PITCH = 42;

/* The map container is wider than what you can see: its left is behind the
   panel. So the left needs the most room, and whatever is framed lands clear
   of the heading rather than underneath it. */
export const FRAME_PADDING = { left: 380, right: 160, top: 110, bottom: 110 };