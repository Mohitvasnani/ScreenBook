/* The cinemas, for demo mode. Stands in for migration V18.

   Real buildings at real coordinates, taken from the same OpenStreetMap rows
   the migration uses, because the nearest-cinema list is the feature this data
   exists to demonstrate and made-up coordinates would demonstrate nothing. Ten
   rather than the server's 130: enough that Liverpool has a plausible cluster
   and the currency split is visible, few enough to read.

   Six are in England and four in India, so GBP and INR are both exercised and
   two different clocks decide what counts as an evening. */

   import type { Cinema, CinemaDetail, NearestCinema } from "@/types/api";

   export interface MockCinema {
     id: number;
     name: string;
     city: string;
     state: string;
     address: string;
     latitude: number;
     longitude: number;
     currency: string;
     timezone: string;
     /* How many of the four slot screens this cinema has. The mock keeps one room
        shape for every house, 10 rows of 12, because screen.aisle_after is not in
        the API contract yet. What varies is how many screens there are, which is
        what makes two cinemas list different numbers of showtimes. */
     screens: number;
   }
   
   export const CINEMAS: MockCinema[] = [
     {
       id: 1,
       name: "Everyman",
       city: "Liverpool",
       state: "England",
       address: "Whitechapel, Cavern Quarter, L1 6DA",
       latitude: 53.407184,
       longitude: -2.98644,
       currency: "GBP",
       timezone: "Europe/London",
       screens: 3,
     },
     {
       id: 2,
       name: "Odeon",
       city: "Liverpool",
       state: "England",
       address: "Paradise Street, Vauxhall, L1 8JF",
       latitude: 53.404529,
       longitude: -2.986714,
       currency: "GBP",
       timezone: "Europe/London",
       screens: 4,
     },
     {
       id: 3,
       name: "Picturehouse At FACT",
       city: "Liverpool",
       state: "England",
       address: "Wood Street, Vauxhall, L1 4DQ",
       latitude: 53.402236,
       longitude: -2.977651,
       currency: "GBP",
       timezone: "Europe/London",
       screens: 3,
     },
     {
       id: 4,
       name: "Cineworld",
       city: "Liverpool",
       state: "England",
       address: "Speke Road, Garston, L24 8QB",
       latitude: 53.349303,
       longitude: -2.880538,
       currency: "GBP",
       timezone: "Europe/London",
       screens: 4,
     },
     {
       id: 5,
       name: "Woolton Cinema",
       city: "Liverpool",
       state: "England",
       address: "Mason Street, Woolton, L25 5JH",
       latitude: 53.375578,
       longitude: -2.867719,
       currency: "GBP",
       timezone: "Europe/London",
       /* A genuine single screen, which is why the nearby list shows one cinema
          with three showtimes next to another with ten. */
       screens: 1,
     },
     {
       id: 6,
       name: "Everyman",
       city: "Birmingham",
       state: "England",
       address: "Commercial Street, Chinese Quarter, B1 1RF",
       latitude: 52.475549,
       longitude: -1.905646,
       currency: "GBP",
       timezone: "Europe/London",
       screens: 3,
     },
     {
       id: 7,
       name: "Cinepolis",
       city: "Jaipur",
       state: "Rajasthan",
       address: "Jhotwara Road, Amba Bari, 302001",
       latitude: 26.941102,
       longitude: 75.771365,
       currency: "INR",
       timezone: "Asia/Kolkata",
       /* Four, matching the server: V18 hands V3's four original screens to this
          cinema, which is why it is the one with a luxury hall. */
       screens: 4,
     },
     {
       id: 8,
       name: "Raj Mandir Cinemas",
       city: "Jaipur",
       state: "Rajasthan",
       address: "Bhagwandas Road, Adarsh Nagar, 302004",
       latitude: 26.915425,
       longitude: 75.810083,
       currency: "INR",
       timezone: "Asia/Kolkata",
       screens: 1,
     },
     {
       id: 9,
       name: "Galaxy Cinema",
       city: "Jaipur",
       state: "Rajasthan",
       address: "Shipra Path, 302029",
       latitude: 26.836132,
       longitude: 75.778243,
       currency: "INR",
       timezone: "Asia/Kolkata",
       screens: 2,
     },
     {
       id: 10,
       name: "DT Cinema",
       city: "Delhi",
       state: "Delhi",
       address: "Alaknanda, 110048",
       latitude: 28.541408,
       longitude: 77.240061,
       currency: "INR",
       timezone: "Asia/Kolkata",
       screens: 3,
     },
   ];
   
   export const cinemaById = (cinemaId: number): MockCinema | undefined =>
     CINEMAS.find((cinema) => cinema.id === cinemaId);
   
   /* ------------------------------------------------------------- distance */
   
   /* The mean earth radius PostGIS uses for sphere distance, so this and
      ST_Distance(..., false) agree to the metre. */
   const EARTH_RADIUS_M = 6_371_008.8;
   
   const radians = (degrees: number) => (degrees * Math.PI) / 180;
   
   /* Haversine on a sphere, which is the same model the server reports distances
      in. Not the spheroid: ST_Distance defaults to it, CinemaQuery passes false to
      turn it off, and the two differ by around 0.3 percent. Matching the server's
      choice matters more than being 0.3 percent closer to the truth, because a
      list whose ordering and whose numbers disagree is a visible bug. */
   export function distanceMetres(
     fromLat: number,
     fromLon: number,
     toLat: number,
     toLon: number,
   ): number {
     const dLat = radians(toLat - fromLat);
     const dLon = radians(toLon - fromLon);
   
     const a =
       Math.sin(dLat / 2) ** 2 +
       Math.cos(radians(fromLat)) * Math.cos(radians(toLat)) * Math.sin(dLon / 2) ** 2;
   
     return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
   }
   
   /* ---------------------------------------------------------------- shapes */
   
   export const toSummary = (cinema: MockCinema): Cinema => ({
     id: cinema.id,
     name: cinema.name,
     city: cinema.city,
     state: cinema.state,
     latitude: cinema.latitude,
     longitude: cinema.longitude,
     currency: cinema.currency,
     screenCount: cinema.screens,
   });
   
   export const toDetail = (cinema: MockCinema): CinemaDetail => ({
     ...toSummary(cinema),
     address: cinema.address,
     timezone: cinema.timezone,
   });
   
   /* Sorted by distance and sliced, which is what ORDER BY <-> LIMIT n does. Ten
      rows means no index to stand in for, and a comment saying so is more honest
      than a fake one. */
   export function nearest(lat: number, lon: number, limit: number): NearestCinema[] {
     return CINEMAS.map((cinema) => ({
       ...toSummary(cinema),
       distanceMetres: Math.round(
         distanceMetres(lat, lon, cinema.latitude, cinema.longitude),
       ),
     }))
       .sort((a, b) => a.distanceMetres - b.distanceMetres)
       .slice(0, limit);
   }