import styles from "@/css/Poster.module.css";

/* TMDB's CDN. The path the API sends is appended to a size segment, so the
   caller decides how big the file it downloads is: a listing showing twelve
   thumbnails should not pull twelve 500px posters.

   Sizes are TMDB's own fixed set, not arbitrary numbers, which is why this is
   a union rather than a width in pixels. */
const TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p";

type PosterSize = "w185" | "w342";

interface Props {
  posterPath: string | null;
  /* The film's title. Not rendered, but a poster with no alt text is an image
     a screen reader announces as nothing at all. */
  title: string;
  size: PosterSize;
}

export function Poster({ posterPath, title, size }: Props) {
  /* A placeholder element rather than nothing, so a film without a poster does
     not collapse the layout around it and shift everything beside it. */
  if (!posterPath) {
    return <div className={styles.placeholder} aria-hidden />;
  }

  return (
    /* A plain img, deliberately, not next/image. Next's optimiser fetches the
       remote file server-side and serves a transformed copy from this domain,
       which is re-hosting: exactly what the TMDB decision says not to do. An
       img tag is what actually hotlinks from their CDN.

       loading and decoding are the two things next/image would have given us
       that are worth having here, and both are one attribute. */
    <img
      className={styles.poster}
      src={`${TMDB_IMAGE_BASE}/${size}${posterPath}`}
      alt={`Poster for ${title}`}
      loading="lazy"
      decoding="async"
    />
  );
}