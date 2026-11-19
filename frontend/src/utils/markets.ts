import type { Cinema } from "@/types/api";

/* The two markets the dataset covers.

   Market is the cinema's own country field rather than a union declared here,
   so the two cannot drift: a third market is a change to the API contract and
   this follows it automatically. V22 adding cinema.country is what deleted
   marketOf, which existed only because the currency was the closest thing to
   a country the contract carried. */
export type Market = Cinema["country"];

export const MARKETS: { id: Market; label: string; inSentence: string }[] = [
  { id: "IN", label: "India", inSentence: "India" },
  { id: "GB", label: "United Kingdom", inSentence: "the UK" },
];

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