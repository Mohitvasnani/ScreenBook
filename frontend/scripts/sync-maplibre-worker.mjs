/* MapLibre 6 works out its worker URL from import.meta.url and gives up,
   returning an empty string, unless that is an http(s) URL. No bundler hands
   it one: webpack substitutes a file:// path and Turbopack a bare module
   path, so the map silently loses its worker. CinemaMap calls setWorkerUrl
   to point at a copy served from public/ instead. This keeps that copy in
   step with whatever version of maplibre-gl is installed, so the worker can
   never drift out of sync with the library that talks to it. */
import { copyFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

const require = createRequire(import.meta.url);
const dist = join(dirname(require.resolve("maplibre-gl/package.json")), "dist");
const source = join(dist, "maplibre-gl-worker.mjs");

/* Served as .js, not .mjs: the file is loaded as a module worker either way,
   because MapLibre only picks a classic worker for a .cjs URL, and .js has a
   JavaScript content type everywhere without relying on host config. */
const target = new URL("../public/maplibre-gl-worker.js", import.meta.url);

await copyFile(source, target);
console.log("synced maplibre worker -> public/maplibre-gl-worker.js");
