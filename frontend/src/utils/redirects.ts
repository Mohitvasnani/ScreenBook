/* Where a successful sign-in lands. Shared by both auth screens and, later, by
   the route guards, which is what makes it worth its own file. */

/**
 * A path on this site, or "/" if the given one is not.
 *
 * The check is not paranoia. ?next= arrives from the URL, so anyone who can
 * get a link in front of someone chooses where their login lands. A leading
 * slash alone is not enough: browsers read both "//evil.com" and "/\evil.com"
 * as absolute URLs, so either would send a freshly signed-in user off-site
 * with the referrer intact.
 */
export function safeNext(raw: string | null): string {
    if (!raw) return "/";
    if (!raw.startsWith("/")) return "/";
    if (raw.startsWith("//") || raw.startsWith("/\\")) return "/";
    return raw;
  }