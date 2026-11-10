import styles from "@/css/SiteFooter.module.css";

/* The footer is where this stops being a cinema site and says what it is: a
   backend exercise with its source and its API docs one click away. That is
   the point of the project, so it belongs on every page. */
const REPO_URL = "https://github.com/Mohitvasnani/ScreenBook";
const API_DOCS_URL = "https://cinebook-67pa.onrender.com/swagger-ui/index.html";
const OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright";

export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        <span className={styles.wordmark}>ScreenBook</span>
        <span className={styles.strapline}>
          A seat booking system in Java 21 and Spring Boot 4
        </span>

        <nav aria-label="Footer" className={styles.nav}>
          <a href={REPO_URL} className={styles.link}>
            <svg
              aria-hidden
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              <path d="M16 18l6-6-6-6M8 6l-6 6 6 6" />
            </svg>
            Repository
          </a>
          <a href={API_DOCS_URL} className={styles.link}>
            <svg
              aria-hidden
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              <path d="M4 4h12l4 4v12H4z" />
              <path d="M8 12h8M8 16h5" />
            </svg>
            API docs (OpenAPI)
          </a>
        </nav>

        {/* The cinema rows are OpenStreetMap data under ODbL, which requires
            this credit wherever they are shown. That is true on every page
            with a cinema name on it, not only the one with a map, which is why
            it lives here rather than beside the map. The basemap tiles carry
            their own separate credit inside the map itself.

            Reusing strapline rather than inventing a class: same role, same
            weight. Give it one of its own if it ever needs to look different. */}
        <span className={styles.strapline}>
          Cinema locations from{" "}
          <a href={OSM_COPYRIGHT_URL} className={styles.link}>
            OpenStreetMap contributors
          </a>
          , licensed ODbL. Screenings, seats and prices are generated.
        </span>
      </div>
    </footer>
  );
}