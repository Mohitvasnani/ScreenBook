import { useMemo, useState } from "react";

import { api } from "@/services/apiClient";
import type { CinemaRoute } from "@/types/api";
import type { Route } from "@/utils/routes";

type Point = { lat: number; lon: number };

/* Lives as long as the tab. The server rounds the origin to about 110 m, so
   the key rounds the same way and a repeat click costs nothing. */
const cache = new Map<string, CinemaRoute>();

function keyOf(cinemaId: number, origin: Point): string {
  return `${cinemaId}:${origin.lat.toFixed(3)}:${origin.lon.toFixed(3)}`;
}

/* Fetches road routes for the picked cinemas, but only when load() is called.
   Routes already in the cache are used straight away. */
export function useRoadRoutes(routes: Route[], origin: Point | null) {
  const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(false);
  const [failedFor, setFailedFor] = useState<string | null>(null);

  const picksKey =
    origin === null ? "" : routes.map((route) => keyOf(route.cinema.id, origin)).join("|");

  /* version is here so this runs again when a fetch finishes and the cache has
     new entries. */
  const withRoads = useMemo<Route[]>(
    () =>
      routes.map((route) => ({
        ...route,
        road: origin === null ? undefined : cache.get(keyOf(route.cinema.id, origin)),
      })),
    [routes, origin, version],
  );

  async function load() {
    if (origin === null || loading) return;

    const missing = routes.filter((route) => !cache.has(keyOf(route.cinema.id, origin)));
    if (missing.length === 0) return;

    setLoading(true);
    const results = await Promise.allSettled(
      missing.map((route) => api.cinemaRoute(route.cinema.id, origin.lat, origin.lon)),
    );

    results.forEach((result, index) => {
      const route = missing[index];
      if (route && result.status === "fulfilled") {
        cache.set(keyOf(route.cinema.id, origin), result.value);
      }
    });

    setFailedFor(results.some((result) => result.status === "rejected") ? picksKey : null);
    setVersion((value) => value + 1);
    setLoading(false);
  }

  return {
    routes: withRoads,
    loading,
    failed: picksKey !== "" && failedFor === picksKey,
    load,
  };
}