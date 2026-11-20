"use client";

import { useRouter } from "next/navigation";
import { useEffect, useId, useMemo, useRef, useState } from "react";

import styles from "@/css/CinemaSearch.module.css";
import type { Cinema, CinemaCity } from "@/types/api";

/* Search over what is already here.

   No endpoint. The cinemas are in the browser because the globe draws them
   all, and the cities are 40 rows from /api/cinemas/cities, so a filter is
   the whole feature. Film search is the other thing wearing this word and it
   is a backend job: an ILIKE or trigram query over film, scoped by the
   cinema's country. It is deliberately not here.

   This replaces the city chips. A search box that also lists the five biggest
   cities is the same control built twice, and at 127 cinemas in London the
   chips had stopped being a shortcut anyway. */

/* Enough to choose from, few enough to read without scrolling the panel. The
   two groups are capped separately so a city can never be pushed off the list
   by eight cinemas that happen to match the same letters. */
const CITY_LIMIT = 5;
const CINEMA_LIMIT = 7;

type Result =
  | { kind: "city"; key: string; city: CinemaCity }
  | { kind: "cinema"; key: string; cinema: Cinema };

/* Matches on a prefix first, then anywhere. Typing "liv" should offer
   Liverpool before it offers a cinema with "Olive" in its name, and sorting
   by where the match landed is the cheapest way to say that. */
function rank(haystack: string, needle: string): number {
  const text = haystack.toLowerCase();
  const at = text.indexOf(needle);
  if (at < 0) return -1;
  return at === 0 ? 0 : 1;
}

export function CinemaSearch({
  cinemas,
  cities,
  selectedCity,
  onPickCity,
}: {
  cinemas: Cinema[];
  cities: CinemaCity[];
  selectedCity: string | null;
  onPickCity: (city: CinemaCity) => void;
}) {
  const router = useRouter();
  const listId = useId();
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const holder = useRef<HTMLDivElement>(null);

  const needle = query.trim().toLowerCase();

  const results = useMemo<Result[]>(() => {
    if (needle.length < 2) return [];

    const cityHits = cities
      .map((city) => ({ city, score: rank(city.city, needle) }))
      .filter(({ score }) => score >= 0)
      .sort((a, b) => a.score - b.score || b.city.cinemaCount - a.city.cinemaCount)
      .slice(0, CITY_LIMIT)
      .map<Result>(({ city }) => ({
        kind: "city",
        key: `city:${city.country}:${city.state}:${city.city}`,
        city,
      }));

    /* Cinemas match on their own name only. Matching the city name here too
       would return all 112 Hyderabad cinemas under a city row that already
       says Hyderabad, which is the same answer twice at a hundred times the
       length. */
    const cinemaHits = cinemas
      .map((cinema) => ({ cinema, score: rank(cinema.name, needle) }))
      .filter(({ score }) => score >= 0)
      .sort(
        (a, b) =>
          a.score - b.score ||
          a.cinema.name.localeCompare(b.cinema.name) ||
          a.cinema.city.localeCompare(b.cinema.city),
      )
      .slice(0, CINEMA_LIMIT)
      .map<Result>(({ cinema }) => ({
        kind: "cinema",
        key: `cinema:${cinema.id}`,
        cinema,
      }));

    return [...cityHits, ...cinemaHits];
  }, [cinemas, cities, needle]);

  /* The highlight goes back to the top whenever the list changes underneath
     it, or Enter fires whichever row happens to have inherited the index. */
  useEffect(() => {
    setActive(0);
  }, [needle]);

  /* Closing on an outside press rather than on blur. Blur fires before the
     click that caused it has been handled, so a result would unmount under
     the pointer and the pick would never land. */
  useEffect(() => {
    if (!open) return;

    function onDown(event: PointerEvent) {
      if (!holder.current?.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", onDown);
    return () => document.removeEventListener("pointerdown", onDown);
  }, [open]);

  function choose(result: Result) {
    setOpen(false);

    if (result.kind === "city") {
      /* The query is left in the box on purpose. The city is now the heading
         and the filter, so clearing the input would make the control forget
         what the page is showing. */
      setQuery(result.city.city);
      onPickCity(result.city);
      return;
    }

    /* A cinema navigates, which is the opposite of what clicking a dot on the
       map does. That is deliberate: the map opens a popup because a dot can
       be hit by accident and a click that leaves the page feels like a trap.
       A name you typed and then pressed is not an accident. */
    router.push(`/cinemas/${result.cinema.id}`);
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Escape") {
      setOpen(false);
      return;
    }
    if (results.length === 0) return;

    if (event.key === "ArrowDown") {
      event.preventDefault();
      setOpen(true);
      setActive((i) => (i + 1) % results.length);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setOpen(true);
      setActive((i) => (i - 1 + results.length) % results.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      const picked = results[active];
      if (picked) choose(picked);
    }
  }

  const expanded = open && results.length > 0;

  return (
    <div className={styles.holder} ref={holder}>
      {/* A combobox rather than an input with a list under it. The roles are
          what tell a screen reader that typing changes the options and that
          the highlighted row is the one Enter will take, neither of which is
          inferable from the markup on its own. */}
      <input
        type="text"
        className={styles.input}
        role="combobox"
        aria-expanded={expanded}
        aria-controls={listId}
        aria-autocomplete="list"
        aria-activedescendant={
          expanded ? `${listId}-${active}` : undefined
        }
        aria-label="Search cities and cinemas"
        placeholder={
          selectedCity ? `Showing ${selectedCity}. Search again` : "Search a city or cinema"
        }
        value={query}
        onChange={(event) => {
          setQuery(event.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
      />

      {/* Always rendered, so a screen reader has something to announce into
          rather than a region that appears and disappears. */}
      <div className={styles.status} role="status" aria-live="polite">
        {needle.length >= 2 && results.length === 0
          ? "Nothing matches that."
          : ""}
      </div>

      {expanded && (
        <ul className={styles.list} id={listId} role="listbox">
          {results.map((result, index) => (
            <li
              key={result.key}
              id={`${listId}-${index}`}
              role="option"
              aria-selected={index === active}
              className={
                index === active ? `${styles.option} ${styles.optionOn}` : styles.option
              }
              onPointerDown={(event) => {
                /* Keeps focus in the input, so the outside-press handler does
                   not race the pick. */
                event.preventDefault();
                choose(result);
              }}
              onPointerEnter={() => setActive(index)}
            >
              {result.kind === "city" ? (
                <>
                  <span className={styles.name}>{result.city.city}</span>
                  <span className={styles.meta}>
                    {result.city.cinemaCount}{" "}
                    {result.city.cinemaCount === 1 ? "cinema" : "cinemas"} ·{" "}
                    {result.city.state}
                  </span>
                </>
              ) : (
                <>
                  <span className={styles.name}>{result.cinema.name}</span>
                  <span className={styles.meta}>
                    {result.cinema.city}, {result.cinema.state}
                  </span>
                </>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
