import type { CinemaRoute, NearestCinema } from "@/types/api";
import { distance } from "@/utils/format";

export type RouteKind = "nearest" | "cheapest" | "furthest";

/* One entry per distinct cinema. kinds is never empty and runs nearest,
   cheapest, furthest, so kinds[0] is the one that colours the line. */
export type Route = {
  cinema: NearestCinema;
  kinds: RouteKind[];
  /* Set once the road route has been fetched. Without it the line is straight. */
  road?: CinemaRoute;
};

/* None of these is near the accent, which means a cinema everywhere else on
   the map. */
export const ROUTE_COLOURS: Record<RouteKind, string> = {
  nearest: "#4cc9f0",
  cheapest: "#ffd166",
  furthest: "#b388ff",
};

export const ROUTE_LABELS: Record<RouteKind, string> = {
  nearest: "Nearest",
  cheapest: "Cheapest",
  furthest: "Furthest",
};

type Priced = NearestCinema & { fromPriceMinor: number };

function isPriced(cinema: NearestCinema): cinema is Priced {
  return cinema.fromPriceMinor !== null;
}

/* Every comparison ends on the id, so the same rows always give the same
   picks whatever order the server sent them in. */
export function pickRoutes(cinemas: NearestCinema[]): Route[] {
  const byDistance = [...cinemas].sort(
    (a, b) => a.distanceMetres - b.distanceMetres || a.id - b.id,
  );

  const nearest = byDistance[0];
  if (!nearest) return [];

  /* One cinema is trivially the nearest. Calling it the furthest as well
     says nothing. */
  const furthest = byDistance.length > 1 ? byDistance[byDistance.length - 1] : undefined;

  /* Null means no screening in the from-price window, so that cinema has no
     price to compare. All rows in a radius share a currency, so the minor
     units are comparable. A price tie goes to the closer cinema. */
  const cheapest = cinemas
    .filter(isPriced)
    .sort(
      (a, b) =>
        a.fromPriceMinor - b.fromPriceMinor ||
        a.distanceMetres - b.distanceMetres ||
        a.id - b.id,
    )[0];

  const picks: [RouteKind, NearestCinema | undefined][] = [
    ["nearest", nearest],
    ["cheapest", cheapest],
    ["furthest", furthest],
  ];

  /* Keyed by id, so a cinema that wins two categories is one line with two
     kinds rather than two lines on top of each other. */
  const routes = new Map<number, Route>();
  for (const [kind, cinema] of picks) {
    if (!cinema) continue;
    const seen = routes.get(cinema.id);
    if (seen) {
      seen.kinds.push(kind);
    } else {
      routes.set(cinema.id, { cinema, kinds: [kind] });
    }
  }

  return [...routes.values()];
}


/* The figure shown for a route, on the legend and on the map. A road route
   adds the time, because that is the reason to fetch one. */
export function routeFigure(route: Route): string {
  if (!route.road) return distance(route.cinema.distanceMetres);

  const minutes = Math.max(1, Math.round(route.road.durationSeconds / 60));
  return `${distance(route.road.distanceMetres)} · ${minutes} min`;
}