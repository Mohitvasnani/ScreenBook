"use client";

import { Fragment, useEffect, useRef, useState } from "react";

import styles from "@/css/SeatMap.module.css";
import type { Seat as SeatType, Tier } from "@/types/api";
import { TIERS } from "@/utils/seatTiers";

import { Seat } from "./Seat";

/* Seats laid out 3 | 6 | 3 with two walking aisles, so the best seats sit in a
   six-wide centre block. The gaps are what make this read as a cinema rather
   than a spreadsheet, and they are the landmark people navigate by.

   Two cross-aisles separate the tiers. Where they fall is not decided here:
   the API gives every seat its tier, so an aisle appears wherever consecutive
   rows disagree. Reseating the house is a migration, not a frontend change.

   Two views. Fit scales 120 seats to the container, which at 375px leaves each
   one about 26px: fine for finding a seat, tight for tapping six of them.
   Zoom fixes the seat size and scrolls instead. Fit is the default because it
   answers "where is there space" and that is the first question. */
const BLOCKS = [
  [1, 2, 3],
  [4, 5, 6, 7, 8, 9],
  [10, 11, 12],
];

type View = "fit" | "zoom";

/* Where the scroller sits, as fractions of the full scrollable width. Null
   when there is nothing to scroll, which is also when a minimap would be
   lying about something. */
type Viewport = { left: number; width: number };

export function SeatMap({
  seats,
  selectedIds,
  pending,
  onSelect,
}: {
  seats: SeatType[];
  /* A set, not an id: several seats can be selected at once, and a Set keeps
     the per-seat lookup O(1) across 120 seats. */
  selectedIds: ReadonlySet<number>;
  pending: boolean;
  onSelect: (seat: SeatType) => void;
}) {
  /* Not persisted. A returning visitor gets fit again, which is the right
     starting point even if they zoomed last time. */
  const [view, setView] = useState<View>("fit");

  const rows = new Map<string, SeatType[]>();
  for (const seat of seats) {
    rows.set(seat.rowLabel, [...(rows.get(seat.rowLabel) ?? []), seat]);
  }

  /* Sorted here rather than trusting the order the API happened to return in.
     The cross-aisles depend on the rows being in sequence. */
  const rowLabels = [...rows.keys()].sort();

  const gridRef = useRef<HTMLDivElement>(null);
  const scrollerRef = useRef<HTMLDivElement>(null);
  const [focusedId, setFocusedId] = useState<number | null>(null);
  const [viewport, setViewport] = useState<Viewport | null>(null);

  /* Measures the scroller rather than calculating from seat counts, so the
     minimap stays truthful whatever the CSS does to seat widths later.

     Cleared whenever there is no overflow: on a wide screen zoom can fit
     entirely, and a minimap whose viewport covers the whole width is a
     control that does nothing. */
  useEffect(() => {
    const el = scrollerRef.current;
    if (!el || view !== "zoom") {
      setViewport(null);
      return;
    }

    const measure = () => {
      if (el.scrollWidth - el.clientWidth <= 1) {
        setViewport(null);
        return;
      }
      setViewport({
        left: el.scrollLeft / el.scrollWidth,
        width: el.clientWidth / el.scrollWidth,
      });
    };

    measure();
    /* passive: this listener never calls preventDefault, so it cannot block
       scrolling. */
    el.addEventListener("scroll", measure, { passive: true });
    const observer = new ResizeObserver(measure);
    observer.observe(el);

    return () => {
      el.removeEventListener("scroll", measure);
      observer.disconnect();
    };
  }, [view]);

  /* Centres the scroller on the point touched, so the thing under your finger
     ends up in the middle of the map rather than at its left edge.

     behavior auto, not smooth: this is direct manipulation and an animation
     would lag behind a drag. */
  function seek(event: React.PointerEvent<HTMLDivElement>) {
    const el = scrollerRef.current;
    if (!el) return;

    const rect = event.currentTarget.getBoundingClientRect();
    const fraction = (event.clientX - rect.left) / rect.width;
    el.scrollTo({ left: fraction * el.scrollWidth - el.clientWidth / 2 });
  }

  /* Lookup by position, for arrow navigation. Built once per render rather
     than scanning 120 seats on every key press. */
  const byPosition = new Map<string, SeatType>();
  for (const seat of seats) {
    byPosition.set(`${seat.rowLabel}:${seat.seatNumber}`, seat);
  }
  const at = (row: string | undefined, number: number) =>
    row ? byPosition.get(`${row}:${number}`) : undefined;

  /* The one seat in the tab order. Falls back to the first seat so the grid is
     always reachable, including before anything has been focused. */
  const tabStopId = focusedId ?? seats[0]?.id ?? null;

  function moveFocus(event: React.KeyboardEvent<HTMLDivElement>) {
    const current = seats.find((s) => s.id === tabStopId);
    if (!current) return;

    const rowIndex = rowLabels.indexOf(current.rowLabel);
    const inRow = (rows.get(current.rowLabel) ?? [])
      .slice()
      .sort((a, b) => a.seatNumber - b.seatNumber);

    let target: SeatType | undefined;
    switch (event.key) {
      case "ArrowRight":
        target = at(current.rowLabel, current.seatNumber + 1);
        break;
      case "ArrowLeft":
        target = at(current.rowLabel, current.seatNumber - 1);
        break;
      case "ArrowDown":
        target = at(rowLabels[rowIndex + 1], current.seatNumber);
        break;
      case "ArrowUp":
        target = at(rowLabels[rowIndex - 1], current.seatNumber);
        break;
      case "Home":
        target = inRow[0];
        break;
      case "End":
        target = inRow.at(-1);
        break;
      default:
        return;
    }
    if (!target) return;

    /* Only once a move is actually possible, so Arrow at the edge of the grid
       still scrolls the page rather than doing nothing. */
    event.preventDefault();
    setFocusedId(target.id);
    gridRef.current
      ?.querySelector<HTMLButtonElement>(`[data-seat-id="${target.id}"]`)
      ?.focus();
  }

  /* Keeps the tab stop wherever focus actually landed, including after a click
     or a Tab from outside, so arrow keys continue from the right place. */
  function trackFocus(event: React.FocusEvent<HTMLDivElement>) {
    const id = (event.target as HTMLElement).dataset?.seatId;
    if (id) setFocusedId(Number(id));
  }

  /* Every seat in a row shares its tier, so the first one speaks for the row. */
  const tierOfRow = (label: string): Tier | null =>
    rows.get(label)?.[0]?.tier ?? null;

  return (
    <div className={`${styles.root} ${view === "zoom" ? styles.zoom : ""}`}>
      <div className={styles.toolbar} role="group" aria-label="Seat map size">
        <ViewButton current={view} value="fit" onPick={setView}>
          Fit
        </ViewButton>
        <ViewButton current={view} value="zoom" onPick={setView}>
          Zoom
        </ViewButton>
      </div>

      {viewport && (
        <Minimap
          rowLabels={rowLabels}
          rows={rows}
          selectedIds={selectedIds}
          viewport={viewport}
          onSeek={seek}
        />
      )}

      {/* Only this scrolls. The toolbar stays put so the way back to Fit does
          not scroll off the side with the seats. */}
      <div className={styles.scroller} ref={scrollerRef}>
        <div className={styles.stage}>
          <div className={styles.screenWrap} aria-hidden>
            <svg
              className={styles.curve}
              viewBox="0 0 560 50"
              preserveAspectRatio="none"
              fill="none"
            >
              <path d="M8 40 Q280 0 552 40 L552 50 L8 50 Z" fill="currentColor" fillOpacity="0.08" />
              <path d="M8 40 Q280 0 552 40" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
            </svg>
            <span className={styles.screen}>SCREEN</span>
          </div>

          <div
            className={styles.rows}
            ref={gridRef}
            role="grid"
            aria-label="Seat map"
            onKeyDown={moveFocus}
            onFocusCapture={trackFocus}
          >
            {rowLabels.map((rowLabel, i) => {
              const rowSeats = rows.get(rowLabel) ?? [];
              const tier = tierOfRow(rowLabel);
              /* A cross-aisle goes wherever the tier changes, the front row
                 included: that one labels the cheapest tier. */
              const tierStarts =
                tier !== null && (i === 0 || tierOfRow(rowLabels[i - 1]) !== tier);

              return (
                <Fragment key={rowLabel}>
                  {tierStarts && tier && (
                    <div
                      className={styles.crossAisle}
                      style={{ ["--tier-color" as string]: TIERS[tier].cssVar }}
                    >
                      <span className={styles.aisleRule} />
                      <span className={styles.aisleLabel}>
                        {(rowSeats[0]?.tierName ?? TIERS[tier].label).toUpperCase()}
                      </span>
                      <span className={styles.aisleRule} />
                    </div>
                  )}

                  <div className={styles.row}>
                    <span className={`${styles.rowLabel} tnum`}>{rowLabel}</span>

                    <div className={styles.blocks}>
                      {BLOCKS.map((block, blockIndex) => (
                        <div
                          key={blockIndex}
                          className={styles.block}
                          /* In fit, blocks grow in proportion to their seat
                             count so every seat ends up the same width. In
                             zoom the CSS overrides this with a fixed size. */
                          style={{ flexGrow: block.length }}
                        >
                          {block.map((number) => {
                            const seat = rowSeats.find((s) => s.seatNumber === number);
                            if (!seat) return <div key={number} className={styles.gap} />;
                            return (
                              <div key={seat.id} className={styles.slot}>
                                <Seat
                                  seat={seat}
                                  selected={selectedIds.has(seat.id)}
                                  pending={pending}
                                  onSelect={onSelect}
                                  tabIndex={seat.id === tabStopId ? 0 : -1}
                                />
                              </div>
                            );
                          })}
                        </div>
                      ))}
                    </div>

                    <span className={`${styles.rowLabelEnd} tnum`}>{rowLabel}</span>
                  </div>
                </Fragment>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * The whole house at a glance, with a box around the part currently on screen.
 * Only in zoom, and only when there is actually something off screen.
 *
 * aria-hidden, deliberately. Every seat here is already a real button in the
 * grid below, announced with its row, number, tier, price and state, and the
 * grid is fully arrow-navigable. A second focusable copy of 120 seats would
 * double the tab order to duplicate navigation a keyboard user already has.
 * This is a pointer convenience, so it is hidden from assistive tech rather
 * than half-exposed to it.
 *
 * That is also why colour alone carries the state here, which the seats
 * themselves never do: at four pixels a hatch or a mark is invisible, and
 * held versus booked is not a distinction this control exists to make. It
 * answers "where is there space and where am I", and the map below answers
 * everything else.
 */
function Minimap({
  rowLabels,
  rows,
  selectedIds,
  viewport,
  onSeek,
}: {
  rowLabels: string[];
  rows: Map<string, SeatType[]>;
  selectedIds: ReadonlySet<number>;
  viewport: Viewport;
  onSeek: (event: React.PointerEvent<HTMLDivElement>) => void;
}) {
  return (
    <div
      className={styles.minimap}
      aria-hidden
      /* Pointer events rather than mouse, so one handler covers touch, pen and
         mouse. Capture makes a drag keep tracking after the finger leaves the
         minimap's own box. */
      onPointerDown={(event) => {
        event.currentTarget.setPointerCapture(event.pointerId);
        onSeek(event);
      }}
      onPointerMove={(event) => {
        if (event.buttons !== 0) onSeek(event);
      }}
    >
      <div className={styles.miniRows}>
        {rowLabels.map((rowLabel) => {
          const rowSeats = rows.get(rowLabel) ?? [];
          return (
            <div key={rowLabel} className={styles.miniRow}>
              {BLOCKS.map((block, blockIndex) => (
                <div key={blockIndex} className={styles.miniBlock}>
                  {block.map((number) => {
                    const seat = rowSeats.find((s) => s.seatNumber === number);
                    if (!seat) return <span key={number} className={styles.miniGap} />;

                    if (selectedIds.has(seat.id)) {
                      return <span key={seat.id} className={styles.miniYours} />;
                    }
                    if (seat.status !== "AVAILABLE") {
                      return <span key={seat.id} className={styles.miniTaken} />;
                    }
                    return (
                      <span
                        key={seat.id}
                        className={styles.miniFree}
                        style={{ ["--mini-tint" as string]: TIERS[seat.tier].cssVar }}
                      />
                    );
                  })}
                </div>
              ))}
            </div>
          );
        })}
      </div>

      {/* Positioned from the scroller's own numbers. The row labels sit
          outside the seats in the real map and not here, so the box is off by
          their width, around three percent of the stage. Visible to nobody,
          and correcting it would mean the minimap duplicating the grid's
          layout maths. */}
      <div
        className={styles.miniViewport}
        style={{
          left: `${viewport.left * 100}%`,
          width: `${viewport.width * 100}%`,
        }}
      />
    </div>
  );
}

/* aria-pressed rather than a radio group: these are two buttons that change
   how the same thing is drawn, not a value being submitted. */
function ViewButton({
  current,
  value,
  onPick,
  children,
}: {
  current: View;
  value: View;
  onPick: (view: View) => void;
  children: React.ReactNode;
}) {
  const active = current === value;
  return (
    <button
      type="button"
      className={active ? styles.viewButtonActive : styles.viewButton}
      aria-pressed={active}
      onClick={() => onPick(value)}
    >
      {children}
    </button>
  );
}
