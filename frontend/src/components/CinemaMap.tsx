"use client";

/* No default export in maplibre-gl 6, so everything is named. The Map class is
   exported under two names and MapLibreMap is the one to take: importing it as
   Map would shadow the built-in Map, which this codebase uses for grouping
   rows and seats. */
import {
  GeoJSONSource,
  LngLatBounds,
  MapLibreMap,
  NavigationControl,
  Popup,
} from "maplibre-gl";
import type { FeatureCollection, Point } from "geojson";
import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";

import styles from "@/css/CinemaMap.module.css";
import type { Cinema } from "@/types/api";

import "maplibre-gl/dist/maplibre-gl.css";

/* Public by necessity. The browser is what talks to MapTiler, so this is
   compiled into the page and anyone can read it out of the source. The
   protection is the domain restriction on the key in MapTiler's dashboard,
   not secrecy.

   Worth contrasting with API_ORIGIN, which is deliberately server-side because
   the browser never needs to know where the API lives. Two environment
   variables, opposite answers, each correct for its own reason. */
const MAPTILER_KEY = process.env.NEXT_PUBLIC_MAPTILER_KEY;

/* dataviz-dark is a deliberately muted basemap, designed to sit under data
   rather than compete with it, which is what 130 dots need. Swap the slug for
   dataviz if the site ever gets a light theme. */
const styleUrl = (key: string) =>
  `https://api.maptiler.com/maps/dataviz-dark/style.json?key=${key}`;

/* MapLibre paints into a canvas and cannot resolve CSS variables, so the theme
   is read out of the stylesheet once instead of hardcoded. It parses the
   common colour syntaxes but not every modern function, so anything it would
   choke on is swapped for a literal rather than failing at paint time. */
const colour = (value: string, fallback: string) =>
  /^(#|rgb|hsl)/.test(value.trim()) ? value.trim() : fallback;

export function CinemaMap({ cinemas }: { cinemas: Cinema[] }) {
  const router = useRouter();
  const holder = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!MAPTILER_KEY || !holder.current || cinemas.length === 0) return;

    const css = getComputedStyle(document.documentElement);
    const accent = colour(css.getPropertyValue("--color-accent"), "#d64545");
    const surface = colour(css.getPropertyValue("--color-surface"), "#ffffff");

    const map = new MapLibreMap({
      container: holder.current,
      style: styleUrl(MAPTILER_KEY),
      /* Compact so the credit is a small (i) on a phone rather than a line of
         text across the bottom. Never false: the basemap credit is a condition
         of using the tiles, not a decoration. */
      attributionControl: { compact: true },
    });

    map.addControl(new NavigationControl({ showCompass: false }), "top-right");

    const data: FeatureCollection<Point> = {
      type: "FeatureCollection",
      features: cinemas.map((cinema) => ({
        type: "Feature",
        geometry: {
          type: "Point",
          coordinates: [cinema.longitude, cinema.latitude],
        },
        properties: {
          id: cinema.id,
          name: cinema.name,
          city: cinema.city,
          screenCount: cinema.screenCount,
        },
      })),
    };

    /* Fitted to every cinema rather than opened on one country. 27 in Britain
       and 103 in India: opening on either makes the other look like it does
       not exist, and two counted clusters say what the dataset is in one
       glance. Not animated, because this is the first paint and nobody asked
       for a journey. */
    const bounds = new LngLatBounds();
    for (const cinema of cinemas) {
      bounds.extend([cinema.longitude, cinema.latitude]);
    }
    map.fitBounds(bounds, { padding: 48, maxZoom: 12, animate: false });

    map.on("load", () => {
      map.addSource("cinemas", {
        type: "geojson",
        data,
        /* MapLibre does the grouping itself, so there is no supercluster
           dependency and no clustering code of ours to defend. Past zoom 11 a
           city's cinemas are far enough apart to read individually. */
        cluster: true,
        clusterRadius: 48,
        clusterMaxZoom: 11,
      });

      map.addLayer({
        id: "clusters",
        type: "circle",
        source: "cinemas",
        filter: ["has", "point_count"],
        paint: {
          "circle-color": accent,
          "circle-opacity": 0.85,
          /* Three sizes rather than a continuous scale, so four and six look
             alike and only the order of magnitude reads. */
          "circle-radius": ["step", ["get", "point_count"], 16, 10, 22, 40, 30],
          "circle-stroke-width": 2,
          "circle-stroke-color": surface,
        },
      });

      map.addLayer({
        id: "cluster-count",
        type: "symbol",
        source: "cinemas",
        filter: ["has", "point_count"],
        layout: {
          "text-field": ["get", "point_count_abbreviated"],
          /* Must name fonts the style actually ships glyphs for. MapTiler
             carries the Noto Sans family; a name it does not have fails
             quietly and the numbers simply never appear, which is why there
             are fallbacks rather than one guess. */
          "text-font": ["Noto Sans Bold", "Open Sans Bold", "Arial Unicode MS Bold"],
          "text-size": 13,
        },
        paint: { "text-color": surface },
      });

      map.addLayer({
        id: "cinema",
        type: "circle",
        source: "cinemas",
        filter: ["!", ["has", "point_count"]],
        paint: {
          "circle-color": accent,
          "circle-radius": 6,
          "circle-stroke-width": 2,
          "circle-stroke-color": surface,
        },
      });
    });

    /* Tapping a cluster zooms to exactly the level where it breaks apart,
       which the source can work out. A guessed zoom step either does nothing
       visible or overshoots past the thing you aimed at. */
    map.on("click", "clusters", async (event) => {
      const feature = event.features?.[0];
      const clusterId = feature?.properties?.cluster_id;
      const source = map.getSource("cinemas") as GeoJSONSource | undefined;
      if (!feature || clusterId === undefined || !source) return;

      const zoom = await source.getClusterExpansionZoom(Number(clusterId));
      map.easeTo({
        center: (feature.geometry as Point).coordinates as [number, number],
        zoom,
      });
    });

    /* A popup, not a navigation. On a map a click that leaves the page feels
       like a trap, so the cinema gets named and the viewer decides. */
    map.on("click", "cinema", (event) => {
      const feature = event.features?.[0];
      if (!feature) return;

      new Popup({ offset: 12, closeButton: false })
        .setLngLat((feature.geometry as Point).coordinates as [number, number])
        .setDOMContent(
          popupContent(
            feature.properties as {
              id: number;
              name: string;
              city: string;
              screenCount: number;
            },
            (href) => router.push(href),
          ),
        )
        .addTo(map);
    });

    for (const layer of ["clusters", "cinema"]) {
      map.on("mouseenter", layer, () => {
        map.getCanvas().style.cursor = "pointer";
      });
      map.on("mouseleave", layer, () => {
        map.getCanvas().style.cursor = "";
      });
    }

    return () => map.remove();
  }, [cinemas, router]);

  /* No key, no map, and the list below is untouched. Someone cloning this repo
     with no MapTiler account gets a working page rather than a broken one. */
  if (!MAPTILER_KEY) return null;

  return (
    <section className={styles.wrap} aria-labelledby="cinema-map-heading">
      <h2 id="cinema-map-heading" className={styles.heading}>
        Where they are
      </h2>

      <div className={styles.map} ref={holder} />

      {/* An equivalent, not a disclaimer. A canvas cannot be read out, and the
          thing that makes this page accessible is that the same cinemas are
          listed below as links. Saying so beats hiding the map from assistive
          tech, which would hide the tile credit along with it. */}
      <p className={styles.note}>
        Every cinema in the dataset. The city list below has the same cinemas as
        links.
      </p>
    </section>
  );
}

/* Built as DOM rather than an HTML string. Cinema names come from
   OpenStreetMap, which is user-contributed, and setHTML on user-contributed
   text is the entire XSS story in one line. textContent cannot be anything but
   text.

   The navigation is passed in rather than the router, so this stays a plain
   function with no hook rules attached to it. */
function popupContent(
  cinema: { id: number; name: string; city: string; screenCount: number },
  go: (href: string) => void,
): HTMLElement {
  const href = `/cinemas/${cinema.id}`;

  const root = document.createElement("div");
  root.className = styles.popup;

  const name = document.createElement("strong");
  name.textContent = cinema.name;

  const meta = document.createElement("span");
  meta.textContent = `${cinema.city} · ${cinema.screenCount} ${
    cinema.screenCount === 1 ? "screen" : "screens"
  }`;

  /* A real href, so middle click and copy-link still behave, with the plain
     click intercepted for a client-side route. A span with an onClick would
     have neither. */
  const link = document.createElement("a");
  link.href = href;
  link.textContent = "See what is on";
  link.addEventListener("click", (event) => {
    event.preventDefault();
    go(href);
  });

  root.append(name, meta, link);
  return root;
}