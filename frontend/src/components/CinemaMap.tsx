"use client";

/* No default export in maplibre-gl 6, so everything is named. The Map class is
   exported under two names and MapLibreMap is the one to take: importing it as
   Map would shadow the built-in Map, which this codebase uses for grouping
   rows and seats. */
import { GeoJSONSource, LngLatBounds, MapLibreMap, Popup, setWorkerUrl } from "maplibre-gl";
import type { FeatureCollection, Point } from "geojson";
import { useRouter } from "next/navigation";
import { useEffect, useRef } from "react";

import styles from "@/css/CinemaMap.module.css";
import type { Cinema } from "@/types/api";
import {
  FRAME_PADDING,
  GROUND_BEARING,
  GROUND_PITCH,
  PLANET,
  
  type Market,
} from "@/utils/markets";

import "maplibre-gl/dist/maplibre-gl.css";

/* MapLibre derives its worker URL from import.meta.url and returns an empty
   string unless that is an http(s) URL. No bundler gives it one: webpack
   substitutes a file:// path, Turbopack a bare module path. An empty URL
   resolves to the current page, so the browser tries to load the HTML
   document as a module worker, fails the MIME check, and the map ends up
   with no worker and no tiles. Naming the URL is the supported way out.
   scripts/sync-maplibre-worker.mjs keeps the served copy in step with the
   installed version. */
setWorkerUrl("/maplibre-gl-worker.js");

/* Public by necessity. The browser is what talks to MapTiler, so this is
   compiled into the page and anyone can read it out of the source. The
   protection is the domain restriction on the key in MapTiler's dashboard,
   not secrecy. */
const MAPTILER_KEY = process.env.NEXT_PUBLIC_MAPTILER_KEY;

const styleUrl = (key: string) =>
  `https://api.maptiler.com/maps/dataviz-dark/style.json?key=${key}`;

/* MapTiler's Countries tileset, on the same key as the tiles, so the country
   highlight adds no third party. Its TileJSON carries its own attribution
   string and MapLibre folds that into the attribution control. */
const countriesUrl = (key: string) =>
  `https://api.maptiler.com/tiles/countries/tiles.json?key=${key}`;

/* The zoom at which city pins hand over to individual cinemas. */
const PIN_HANDOVER = 8;

/* Where a cinema click lands: close enough to see the building's street, not
   so close that there is no sense of where in the city it is. */
const STREET_ZOOM = 16;

/* Below this, MapTiler's own labels do not exist. See the sweep in style.load. */
const BASEMAP_LABELS_FROM = 6;

/* One pulse of the city glow, in milliseconds. */
const PULSE_MS = 2600;

/* MapLibre paints into a canvas and cannot resolve CSS variables, so the theme
   is read out of the stylesheet once instead of hardcoded. It parses the
   common colour syntaxes but not every modern function, so anything it would
   choke on is swapped for a literal rather than failing at paint time. */
const colour = (value: string, fallback: string) =>
  /^(#|rgb|hsl)/.test(value.trim()) ? value.trim() : fallback;

/* A null market means no country has been chosen, which is the opening state
   and shows everything. One predicate so that rule is stated once. */
const inMarket = (cinema: Cinema, market: Market | null) =>
  market === null || cinema.country === market;

/* The board draws each city label as a dark chip with a one pixel border.
   MapLibre has no "background" property for a label, but it can fit an icon
   to the text, and an icon can be a nine patch: name the slice of it that is
   allowed to stretch and the corners keep their radius at any width.

   Returns null rather than throwing if the canvas is unavailable. A missing
   icon makes MapLibre skip the icon and draw the text anyway, so the worst
   case is a plain label. */
function chipImage(fill: string, stroke: string): ImageData | null {
  const size = 48; /* 24 CSS pixels at pixelRatio 2 */
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;

  const ctx = canvas.getContext("2d");
  if (!ctx || typeof ctx.roundRect !== "function") return null;

  ctx.beginPath();
  ctx.roundRect(1, 1, size - 2, size - 2, 12);
  ctx.fillStyle = fill;
  ctx.fill();
  ctx.lineWidth = 2;
  ctx.strokeStyle = stroke;
  ctx.stroke();

  return ctx.getImageData(0, 0, size, size);
}

/* The market is filtered out here, in the data, rather than with a layer
   filter, so the sources only ever describe what is being looked at. */
function featuresFor(cinemas: Cinema[], market: Market | null): FeatureCollection<Point> {
  return {
    type: "FeatureCollection",
    features: cinemas
      .filter((cinema) => inMarket(cinema, market))
      .map((cinema) => ({
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
}

/* One point per city, at the mean of its cinemas.

   This replaces MapLibre's clustering, which grouped by pixel distance: a
   grouping that changes when the camera moves and means nothing on its own.
   A city is a grouping that exists in the world, it is what the list below is
   grouped by, and it is the thing a viewer wants to click.

   A mean of longitudes is wrong for a city straddling the antimeridian.
   Neither country comes near it. */
function citiesFor(cinemas: Cinema[], market: Market | null): FeatureCollection<Point> {
  const cities = new Map<string, { count: number; lon: number; lat: number }>();

  for (const cinema of cinemas) {
    if (!inMarket(cinema, market)) continue;
    const seen = cities.get(cinema.city);
    if (seen) {
      seen.count += 1;
      seen.lon += cinema.longitude;
      seen.lat += cinema.latitude;
    } else {
      cities.set(cinema.city, {
        count: 1,
        lon: cinema.longitude,
        lat: cinema.latitude,
      });
    }
  }

  return {
    type: "FeatureCollection",
    features: [...cities.entries()].map(([name, city]) => ({
      type: "Feature",
      geometry: {
        type: "Point",
        coordinates: [city.lon / city.count, city.lat / city.count],
      },
      properties: { name, count: city.count },
    })),
  };
}

/* The rows the camera should be able to see. */
function boundsOf(
  cinemas: Cinema[],
  market: Market | null,
  city: string | null,
): LngLatBounds {
  const bounds = new LngLatBounds();
  for (const cinema of cinemas) {
    if (!inMarket(cinema, market)) continue;
    if (city !== null && cinema.city !== city) continue;
    bounds.extend([cinema.longitude, cinema.latitude]);
  }
  return bounds;
}

/* The whole camera model, in one function, called from two places: once when
   the map first loads and once whenever the selection changes.

   Frame what was asked about. A country frames that country's cinemas, a city
   frames that city's. Both come out of the same rows the list below is built
   from, so the globe and the list cannot disagree about where anything is,
   and neither of them has a zoom number in it that someone tuned by eye.

   Nothing selected is the only fixed camera: a planet, turned on its axis,
   framing nothing in particular because nothing has been asked yet. */
function frameCamera(
  map: MapLibreMap,
  cinemas: Cinema[],
  market: Market | null,
  city: string | null,
  animate: boolean,
) {
  const bounds =
    market !== null || city !== null ? boundsOf(cinemas, market, city) : null;

  if (bounds && !bounds.isEmpty()) {
    map.fitBounds(bounds, {
      padding: FRAME_PADDING,
      /* A city with one cinema has bounds of zero size, which fits at
         infinite zoom. A country never wants to be closer than this. */
      maxZoom: city !== null ? 12 : 7,
      bearing: GROUND_BEARING,
      pitch: GROUND_PITCH,
      duration: animate ? 2600 : 0,
      animate,
    });
    return;
  }

  if (animate) {
    map.flyTo({ ...PLANET, duration: 2400 });
  } else {
    map.jumpTo(PLANET);
  }
}

export function CinemaMap({
  cinemas,
  market,
  city,
  onPickCity,
}: {
  cinemas: Cinema[];
  market: Market | null;
  city: string | null;
  onPickCity: (city: string) => void;
}) {
  const router = useRouter();
  const holder = useRef<HTMLDivElement>(null);
  const mapRef = useRef<MapLibreMap | null>(null);
  const readyRef = useRef(false);
  const marketRef = useRef(market);
  const cityRef = useRef(city);
  const pickRef = useRef(onPickCity);

  /* Declared first so it runs first: effects fire in declaration order, so
     these are current before the map is built.

     Held in refs as well as props because the effect that builds the map must
     not list them as dependencies. A dependency there means a new canvas, new
     tiles and a new everything on every click, to move a camera. The callback
     needs the same treatment for the opposite reason: handlers registered
     once would otherwise close over the first render's copy of it. */
  useEffect(() => {
    marketRef.current = market;
    cityRef.current = city;
    pickRef.current = onPickCity;
  });

  useEffect(() => {
    if (!MAPTILER_KEY || !holder.current || cinemas.length === 0) return;

    const css = getComputedStyle(document.documentElement);
    const accent = colour(css.getPropertyValue("--color-accent"), "#d64545");
    const glow = colour(
      css.getPropertyValue("--color-accent-tint"),
      "rgba(255,122,89,0.14)",
    );
    const surface = colour(css.getPropertyValue("--color-surface"), "#ffffff");
    const ink = colour(css.getPropertyValue("--color-ink"), "#f2eee6");
    /* Space being the page background is what stops the canvas reading as a
       box. The horizon is the only light in the picture. */
    const space = colour(css.getPropertyValue("--color-bg"), "#0d0e12");
    const horizon = colour(css.getPropertyValue("--color-n400"), "#2c2f3a");
    /* The country lift is grey, not accent. Two oranges on one screen means
       the country competes with its own cinemas, and the data wins that
       argument every time: the pins are what you are looking for, the country
       is only where they are. The accent survives on the outline alone. */
    const lift = colour(css.getPropertyValue("--color-n500"), "#7e8391");

    const countries = countriesUrl(MAPTILER_KEY);

    const map = new MapLibreMap({
      container: holder.current,
      style: styleUrl(MAPTILER_KEY),
      ...PLANET,
      attributionControl: { compact: true },
      /* The map fills a hero the page scrolls past, so the wheel belongs to
         the page: zoom needs ctrl, and touch needs two fingers. Dragging still
         spins the planet, which is the one gesture this hero wants. */
      cooperativeGestures: true,
      doubleClickZoom: false,
    });

    mapRef.current = map;

    map.on("style.load", () => {
      map.setProjection({ type: "globe" });

      map.setSky({
        "sky-color": space,
        "horizon-color": horizon,
        "fog-color": space,
        "sky-horizon-blend": 0.6,
        "horizon-fog-blend": 0.5,
        "fog-ground-blend": 0.5,
        "atmosphere-blend": ["interpolate", ["linear"], ["zoom"], 1, 0.9, 6, 0],
      });

      /* Every label in MapTiler's style, pushed out of the planet view.

         The country mask covers the land under them but a label is drawn on
         top of the fill, so ARCTIC OCEAN and ASIA came through at a tenth
         strength and smudged the sphere. Raising the mask to full opacity
         would have hidden the continents with them and flattened the globe
         into a disc.

         Done by type rather than by name: this is MapTiler's style, their
         layer ids are theirs to rename, and "every symbol layer" is a claim
         that stays true across their releases. It runs in style.load, so the
         only layers in the style are theirs. */
      for (const layer of map.getStyle().layers) {
        if (layer.type !== "symbol") continue;
        map.setLayerZoomRange(
          layer.id,
          Math.max(BASEMAP_LABELS_FROM, layer.minzoom ?? 0),
          layer.maxzoom ?? 24,
        );
      }

      map.addSource("countries", { type: "vector", url: countries });

      /* The highlight is not drawn, it is revealed. Everything that is not one
         of the two markets is covered by a wash of the page background, and
         the wash fades out on the way down.

         level 0 is the country itself. Without that filter this paints every
         state and district on top of its own country. */
      map.addLayer({
        id: "country-mask",
        type: "fill",
        source: "countries",
        "source-layer": "administrative",
        maxzoom: 8,
        filter: [
          "all",
          ["==", ["get", "level"], 0],
          ["match", ["get", "iso_a2"], ["GB", "IN"], false, true],
        ],
        paint: {
          "fill-color": space,
          "fill-opacity": ["interpolate", ["linear"], ["zoom"], 3.2, 0.88, 6.5, 0],
        },
      });

      map.addLayer({
        id: "country-fill",
        type: "fill",
        source: "countries",
        "source-layer": "administrative",
        maxzoom: 8,
        filter: [
          "all",
          ["==", ["get", "level"], 0],
          ["match", ["get", "iso_a2"], ["GB", "IN"], true, false],
        ],
        paint: {
          "fill-color": lift,
          "fill-opacity": ["interpolate", ["linear"], ["zoom"], 3.2, 0.2, 7, 0],
        },
      });

      /* The outline is what makes Britain legible, and now it is the only
         accent on the country: at planet scale the island is a few pixels
         across and a grey fill of it is nothing, so the stroke is what says
         there is a country there. */
      map.addLayer({
        id: "country-outline",
        type: "line",
        source: "countries",
        "source-layer": "administrative",
        maxzoom: 8,
        filter: [
          "all",
          ["==", ["get", "level"], 0],
          ["match", ["get", "iso_a2"], ["GB", "IN"], true, false],
        ],
        paint: {
          "line-color": accent,
          "line-width": ["interpolate", ["linear"], ["zoom"], 1, 1, 6, 1.8],
          "line-opacity": ["interpolate", ["linear"], ["zoom"], 3.2, 0.9, 7, 0],
        },
      });

      /* A wash of the page background that arrives as the basemap brightens.

         dataviz-dark is nearly black at planet scale and pale grey at street
         scale, which is correct for a map and wrong for a hero. This is the
         inverse of the country mask: that one fades out on the way down, this
         one fades in, so the hero stays in the site's palette at every
         altitude. A background layer covers the viewport rather than any
         geometry, and it sits above MapTiler's layers and below the pins that
         load adds afterwards. */
      map.addLayer({
        id: "basemap-dim",
        type: "background",
        paint: {
          "background-color": space,
          "background-opacity": ["interpolate", ["linear"], ["zoom"], 5, 0, 9, 0.55],
        },
      });
    });

    /* The pulse, cancelled on teardown. Declared out here so the cleanup
       below can reach it whether or not load ever fired. */
    let pulseFrame = 0;

    map.on("load", () => {
      map.addSource("cities", {
        type: "geojson",
        data: citiesFor(cinemas, marketRef.current),
      });

      map.addSource("cinemas", {
        type: "geojson",
        data: featuresFor(cinemas, marketRef.current),
      });

      const chip = chipImage("rgba(13, 14, 18, 0.88)", horizon);
      if (chip && !map.hasImage("label-chip")) {
        map.addImage("label-chip", chip, {
          pixelRatio: 2,
          /* Only the middle eight pixels of each axis stretch, so the corner
             radius and the border are identical on Agra and on
             Visakhapatnam. content is the box the text is fitted into. */
          stretchX: [[20, 28]],
          stretchY: [[20, 28]],
          content: [4, 4, 44, 44],
        });
      }

      /* The breathing ring under each city pin. Its radius and opacity are
         driven from the animation loop below rather than from an expression,
         because the style spec has no notion of time. */
      map.addLayer({
        id: "city-glow",
        type: "circle",
        source: "cities",
        maxzoom: PIN_HANDOVER,
        paint: {
          "circle-color": accent,
          "circle-radius": 8,
          "circle-opacity": 0.3,
        },
      });

      map.addLayer({
        id: "city-dot",
        type: "circle",
        source: "cities",
        maxzoom: PIN_HANDOVER,
        paint: {
          "circle-color": accent,
          "circle-radius": ["step", ["get", "count"], 5, 4, 7, 12, 9],
          "circle-stroke-width": 5,
          "circle-stroke-color": glow,
        },
      });

      map.addLayer({
        id: "city-label",
        type: "symbol",
        source: "cities",
        maxzoom: PIN_HANDOVER,
        layout: {
          "text-field": ["get", "name"],
          /* Must name fonts the style actually ships glyphs for. MapTiler
             carries the Noto Sans family; a name it does not have fails
             quietly and the labels simply never appear. */
          "text-font": ["Noto Sans Bold", "Open Sans Bold", "Arial Unicode MS Bold"],
          "text-size": 12,
          "icon-image": "label-chip",
          "icon-text-fit": "both",
          "icon-text-fit-padding": [2, 7, 2, 7],
          /* MapLibre tries these anchors in order and drops a label where
             none fits rather than overlapping one. Labels that collide are
             the whole difference between thirty cities looking designed and
             looking broken. */
          "text-variable-anchor": ["left", "right", "top", "bottom"],
          "text-radial-offset": 1,
          "text-justify": "auto",
          "text-padding": 4,
        },
        paint: {
          "text-color": ink,
          "text-halo-color": space,
          "text-halo-width": 1,
        },
      });

      /* The same handover zoom from the other side, so there is exactly one
         answer at every zoom level and no band where both or neither show. */
      map.addLayer({
        id: "cinema",
        type: "circle",
        source: "cinemas",
        minzoom: PIN_HANDOVER,
        paint: {
          "circle-color": accent,
          "circle-radius": 6,
          "circle-stroke-width": 2,
          "circle-stroke-color": surface,
        },
      });

      /* Each frame sets two paint properties, which forces a repaint, which
         is the point. Two things keep that honest: it stops entirely once the
         city pins are not on screen, and it never starts for a viewer whose
         system asks for reduced motion. An animation nobody can see is just a
         drain on a laptop battery. */
      const stillMotion =
        typeof window !== "undefined" &&
        window.matchMedia("(prefers-reduced-motion: reduce)").matches;

      if (!stillMotion) {
        const pulse = () => {
          if (map.getLayer("city-glow") && map.getZoom() < PIN_HANDOVER) {
            const t = (performance.now() % PULSE_MS) / PULSE_MS;
            map.setPaintProperty("city-glow", "circle-radius", 9 + t * 18);
            map.setPaintProperty("city-glow", "circle-opacity", 0.32 * (1 - t));
          }
          pulseFrame = requestAnimationFrame(pulse);
        };
        pulseFrame = requestAnimationFrame(pulse);
      }

      /* Covers the race where the viewer picks a country before the style has
         finished loading. Not animated: the map has only just appeared and
         there is nothing to fly from. */
      frameCamera(map, cinemas, marketRef.current, cityRef.current, false);
      readyRef.current = true;
    });

    /* A pin reports the pick and moves nothing. The camera is driven by state
       in one place, so a city chosen on the globe and a city chosen from a
       chip take exactly the same path. Two code paths flying one camera is
       how you get a map that fights itself. */
    for (const layer of ["city-dot", "city-label"]) {
      map.on("click", layer, (event) => {
        const name = event.features?.[0]?.properties?.name;
        if (typeof name === "string") pickRef.current(name);
      });
    }

    /* A popup, not a navigation, and a camera move as well.

       The popup decision stands: on a map, a click that leaves the page feels
       like a trap, so the cinema gets named and the viewer decides. The flyTo
       completes the journey the wave set out to make, planet to street with
       no seam in it.

       flyTo first and popup second, deliberately: a popup is anchored to a
       coordinate rather than to the screen, so it rides the animation down
       and the thing you clicked stays named the whole way. */
    map.on("click", "cinema", (event) => {
      const feature = event.features?.[0];
      if (!feature) return;

      const at = (feature.geometry as Point).coordinates as [number, number];

      map.flyTo({
        center: at,
        /* Never zoom out. Clicking a dot you are already on top of should not
           throw you backwards. */
        zoom: Math.max(map.getZoom(), STREET_ZOOM),
        /* The target sits below the middle of the canvas so the popup, which
           opens upwards, has somewhere to be. */
        offset: [0, 70],
        duration: 2200,
      });

      new Popup({ offset: 12, closeButton: false })
        .setLngLat(at)
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

    for (const layer of ["city-dot", "city-label", "cinema"]) {
      map.on("mouseenter", layer, () => {
        map.getCanvas().style.cursor = "pointer";
      });
      map.on("mouseleave", layer, () => {
        map.getCanvas().style.cursor = "";
      });
    }

    return () => {
      cancelAnimationFrame(pulseFrame);
      readyRef.current = false;
      mapRef.current = null;
      map.remove();
    };
  }, [cinemas, router]);

  /* Props changed, so the live map is updated in place: new data into the
     existing sources, and one camera move. Nothing is rebuilt.

     No essential: true on any camera call. A viewer who has asked their
     system for reduced motion gets a jump instead of a journey, which
     MapLibre does on its own as long as the animation is not declared
     essential. */
  useEffect(() => {
    const map = mapRef.current;
    if (!map || !readyRef.current) return;

    (map.getSource("cities") as GeoJSONSource | undefined)?.setData(
      citiesFor(cinemas, market),
    );
    (map.getSource("cinemas") as GeoJSONSource | undefined)?.setData(
      featuresFor(cinemas, market),
    );

    frameCamera(map, cinemas, market, city, true);
  }, [cinemas, market, city]);

  /* No key, no map, and the page is untouched. Someone cloning this repo with
     no MapTiler account gets a working hero rather than a broken one. */
  if (!MAPTILER_KEY) return null;

  /* A layer, not a section. */
  return <div className={styles.map} ref={holder} />;
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