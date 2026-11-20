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
/* Pitch is for arrival, not for overview, and it ramps.

   The planet is an object you look at and a country is a map you read.
   Neither is improved by perspective. A city is where the journey starts
   actually arriving somewhere, so the tilt comes in there, and the street
   keeps it.

   There is a mechanical reason as well as a visual one. fitBounds solves for a
   camera containing the bounds, and under pitch it is fitting a trapezoid
   rather than a rectangle, approximately. Over a city that error is invisible.
   Over India it is what pushes the country into the corner of the frame.

   A radius is zero for a third reason again: the circle is a measurement, and
   at 42 degrees a 5km circle draws half again as wide as it is tall, which is
   untrue about the only number that view exists to set. */
export const COUNTRY_PITCH = 0;
export const RADIUS_PITCH = 0;
export const CITY_PITCH = 35;
export const GROUND_PITCH = 50;

/* The map container is wider than what you can see: its left is behind the
   panel. So the left needs the most room, and whatever is framed lands clear
   of the heading rather than underneath it. */
export const FRAME_PADDING = { left: 380, right: 160, top: 110, bottom: 110 };

/* Under the breakpoint the globe sits in a block of its own and nothing covers
   it. The desktop padding is wider than a phone canvas, and fitBounds drops the
   whole move when the padding leaves no room. */
const NARROW_MAX = 860;
const FRAME_PADDING_NARROW = { left: 32, right: 32, top: 32, bottom: 32 };

export function framePadding(containerWidth: number) {
  return containerWidth <= NARROW_MAX ? FRAME_PADDING_NARROW : FRAME_PADDING;
}