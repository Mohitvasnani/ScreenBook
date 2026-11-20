import styles from "@/css/RouteLegend.module.css";
import { ROUTE_COLOURS, ROUTE_LABELS, routeFigure, type Route } from "@/utils/routes";

export function RouteLegend({
  routes,
  loading,
  failed,
  onLoadRoads,
}: {
  routes: Route[];
  loading: boolean;
  failed: boolean;
  onLoadRoads: () => void;
}) {
  if (routes.length === 0) return null;

  const needsRoads = routes.some((route) => !route.road);

  return (
    <div className={styles.legend}>
      <ul className={styles.rows} aria-label="Routes">
        {routes.map((route) => (
          <li key={route.cinema.id} className={styles.row}>
            <span
              className={styles.swatch}
              style={{ background: ROUTE_COLOURS[route.kinds[0]] }}
              aria-hidden
            />
            <span className={styles.text}>
              <span className={styles.kind}>
                {route.kinds.map((kind) => ROUTE_LABELS[kind]).join(", ")}
              </span>
              <span className={styles.name}>{route.cinema.name}</span>
            </span>
            <span className={`${styles.figure} tnum`}>{routeFigure(route)}</span>
          </li>
        ))}
      </ul>

      {needsRoads && (
        <button
          type="button"
          className={styles.button}
          onClick={onLoadRoads}
          disabled={loading}
        >
          {loading ? "Finding roads..." : "Show road routes"}
        </button>
      )}

      {failed && (
        <p className={styles.note} role="status">
          Road routes are unavailable right now, so the lines are straight.
        </p>
      )}
    </div>
  );
}