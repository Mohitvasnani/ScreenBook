import Image from "next/image";
import Link from "next/link";

import styles from "@/css/SiteFooter.module.css";

/* The footer is where this stops being a cinema site and says what it is: a
   backend exercise, with its source, its API docs and its author one click
   away. That is the point of the project, so it belongs on every page.

   Three columns rather than one row. The old version was a single flex line
   with the links pushed right and the two licence credits wrapping underneath,
   so the legally required text and the navigation read as one undifferentiated
   paragraph. Separating them puts the credits in a bottom bar where fine print
   belongs, and gives the links somewhere to go that is not "the end of a
   sentence". */
const REPO_URL = "https://github.com/Mohitvasnani/ScreenBook";
const API_DOCS_URL = "https://cinebook-67pa.onrender.com/swagger-ui/index.html";
const GITHUB_URL = "https://github.com/mohitvasnani";
const EMAIL = "mohitvasnani47@gmail.com";
const OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright";
const TMDB_URL = "https://www.themoviedb.org/";
const ORS_URL = "https://openrouteservice.org/";

export function SiteFooter() {
  return (
    <footer className={styles.footer}>
      <div className={styles.inner}>
        {/* The lockup as an image rather than live text. The letterforms are
            the brand and are already outlined in the SVG, so a webfont that
            fails to load cannot change them. alt carries the name, so a screen
            reader and a search engine both still read "ScreenBook". */}
        <div className={styles.brand}>
          <Link href="/" className={styles.logoLink} aria-label="ScreenBook, home">
            <Image
              src="/logo.svg"
              alt="ScreenBook"
              width={612}
              height={84}
              style={{ height: "26px", width: "auto" }}
              className={styles.logo}
            />
          </Link>

          <p className={styles.strapline}>
            A seat booking system in Java 21 and Spring Boot 4. Two people, one
            seat, exactly one winner.
          </p>
        </div>

        <nav className={styles.column} aria-labelledby="footer-project">
          <h2 id="footer-project" className={styles.columnTitle}>
            Project
          </h2>
          <a href={REPO_URL} className={styles.link}>
            Repository
          </a>
          <a href={API_DOCS_URL} className={styles.link}>
            API docs (OpenAPI)
          </a>
          <Link href="/race" className={styles.link}>
            Race demo
          </Link>
        </nav>

        <div className={styles.column}>
          <h2 className={styles.columnTitle}>Contact</h2>
          <a href={`mailto:${EMAIL}`} className={styles.link}>
            {EMAIL}
          </a>
          <a href={GITHUB_URL} className={styles.link}>
            github.com/mohitvasnani
          </a>
        </div>
      </div>

      <div className={styles.bottom}>
        <div className={styles.bottomInner}>
          {/* The cinema rows are OpenStreetMap data under ODbL, which requires
              this credit wherever they are shown. That is every page with a
              cinema name on it, not only the one with a map, which is why it
              lives here rather than beside the map. The basemap tiles carry
              their own separate credit inside the map itself. */}
          <p className={styles.fine}>
            Cinema locations from{" "}
            <a href={OSM_COPYRIGHT_URL}>OpenStreetMap contributors</a>, licensed
            ODbL. Screenings, seats and prices are generated.
          </p>

          {/* TMDB asks for this wording, including the disclaimer, wherever
              their data is shown. Titles, runtimes, release dates and posters
              all come from them. Posters are hotlinked from their CDN and never
              re-hosted. */}
          <p className={styles.fine}>
            Film data from <a href={TMDB_URL}>TMDB</a>. This product uses the
            TMDB API but is not endorsed or certified by TMDB.
          </p>
          
          <p className={styles.fine}>
            Road routes by <a href={ORS_URL}>openrouteservice</a>.
          </p>
        </div>
      </div>
    </footer>
  );
}
