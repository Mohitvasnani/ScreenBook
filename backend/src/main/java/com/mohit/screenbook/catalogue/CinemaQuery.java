package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Cinemas, for the map, the city picker, the city rollup and the nearest-N
 * list.
 *
 * location is geography(Point, 4326), so it comes back as PostGIS binary that
 * JDBC cannot read without a PostGIS driver on the classpath. Rather than add
 * one, every query here pulls the two numbers out in SQL: ST_Y and ST_X take a
 * geometry, so the column is cast first. The cast is free, it changes no
 * bytes, it only tells Postgres which family of functions applies.
 *
 * ST_Y is latitude and ST_X is longitude, which is worth saying out loud,
 * because the column was built by ST_MakePoint(lon, lat) and the two
 * conventions are the opposite way round.
 */
@Repository
public class CinemaQuery {

    /* Every cinema, or every cinema in one country, for the map and for the
       city picker to group.

       980 rows now, not the 130 V18 put there. That is about 170KB of JSON on
       a page load against 25KB, and that measured number is what made a filter
       worth adding rather than a guess that it might be needed one day.

       A country filter rather than a bounding box, and the globe is the reason.
       The opening state is the planet with both markets lit, so a bounding box
       there is the whole world and the payload is unchanged in exactly the
       state where it is largest. A box would also refetch on every camera move
       and would move a city's pin when it clipped that city in half, because
       the pin sits at the mean of the cinemas the client was given. The
       country toggle is already state the client holds, so a country is
       something it asks for once.

       Still no city filter. The client groups these rows into cities itself,
       so a city filter would be a round trip for a filter() call. And the
       planet state does not need these rows at all: CITIES answers it in 40.

       Two statements built from one template, rather than one statement with
       WHERE (:country IS NULL OR ci.country = :country). The template puts the
       predicate in the SQL text, so the planner sees a plain equality and not
       a condition it has to keep both branches of, and the SELECT list is
       still written once.

       No index on ci.country, deliberately. The column has two values and GB
       is 225 rows of 980, so a planner handed an index on a 23 percent match
       sequentially scans anyway, and at this size it is right to.

       LEFT JOIN, not JOIN. A cinema with no screens yet should still appear on
       the map as a building that exists, and an inner join would silently hide
       it. count(sc.id) counts matched rows, not NULLs, so that cinema reports
       0 rather than 1.

       GROUP BY ci.id alone, with four more ci columns in the SELECT. Postgres
       allows that when the grouping column is the table's primary key, because
       every other column of that row is then functionally dependent on it and
       there is provably one value per group. Most other databases make you
       list all five. */
    private static final String ALL_TEMPLATE = """
            SELECT ci.id                        AS id,
                   ci.name                      AS name,
                   ci.city                      AS city,
                   ci.state                     AS state,
                   ci.country                   AS country,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                  AS currency,
                   count(sc.id)                 AS screen_count
            FROM cinema ci
            LEFT JOIN screen sc ON sc.cinema_id = ci.id
            %s
            GROUP BY ci.id
            ORDER BY ci.city, ci.name
            """;

    private static final String ALL = ALL_TEMPLATE.formatted("");

    private static final String ALL_IN_COUNTRY =
            ALL_TEMPLATE.formatted("WHERE ci.country = :country");

    /* One row per city, for the globe's planet state and the city chips.

       40 rows and about 4KB against 980 rows and 170KB, answering the question
       the planet state actually asks: where are the cities and how much is in
       each one.

       No join to screen, and that absence is the decision in this query.
       Joining it would multiply every cinema by its screen count, so count(*)
       would count screens rather than cinemas and the centroid would weigh a
       ten screen multiplex ten times as heavily as a single screen. The
       duplicate-row bug that GROUP BY ci.id guards against in ALL would here
       be a pin in slightly the wrong place, which nobody would ever notice.

       ST_Collect is the aggregate: it gathers a group's points into one
       MultiPoint. ST_Centroid of a MultiPoint is the arithmetic mean of its
       points, so this is the same number the browser was computing by summing
       latitudes and dividing by the count, said once in geometry instead of
       once per render. The ::geometry cast is here because ST_Collect takes
       geometry and the column is geography, the same reason ST_Y and ST_X
       need it.

       GROUP BY country, state, city, not by city alone. City names are not
       unique, and grouping on the name would merge two different places that
       share one into a single pin halfway between them.

       count(*) rather than count(ci.id). With no join there is no difference
       between them, and count(*) says "rows in this group", which is what is
       meant. */
    private static final String CITIES = """
            SELECT ci.country  AS country,
                   ci.state    AS state,
                   ci.city     AS city,
                   count(*)    AS cinema_count,
                   ST_Y(ST_Centroid(ST_Collect(ci.location::geometry))) AS latitude,
                   ST_X(ST_Centroid(ST_Collect(ci.location::geometry))) AS longitude
            FROM cinema ci
            GROUP BY ci.country, ci.state, ci.city
            ORDER BY ci.country, ci.city
            """;

    /* The nearest N to a point.

       ORDER BY location <-> point is the KNN operator, and it is the whole
       reason V16 built a GiST index. It walks the index tree nearest-first and
       stops after LIMIT rows, so this reads five rows out of 130 rather than
       computing 130 distances and sorting them. At 130 rows that is a saving of
       nothing; at 130,000 it is the difference between an index scan and a sort.
       The index is here for the shape of the query, as V16 said.

       The origin expression is written out twice instead of once in a CTE.
       The KNN index scan needs the operand on the right of <-> to be constant
       for the whole scan, and Postgres only treats it that way when it is built
       from parameters rather than read from another relation. Hiding it in a
       CTE reads better and loses the index scan, which is the one thing this
       query exists to use.

       ST_Distance's third argument is use_spheroid and defaults to true. This
       passes false on purpose, so the reported distance uses the same sphere
       model as <->. With the default, the ordering and the numbers come from
       two slightly different shapes of the earth and a list can show a 2,411m
       cinema above a 2,409m one. The sphere is out by about 0.3%, so about
       seven metres at that range, which nobody is measuring.

       No WHERE on distance. "The nearest five" is always answerable and a
       radius is not: from Liverpool a 50km radius finds four cinemas and from
       a village it finds none, and an empty list is a worse answer than a long
       drive. The client shows the distance and lets the customer judge. */
    private static final String NEAREST = """
            SELECT ci.id                        AS id,
                   ci.name                      AS name,
                   ci.city                      AS city,
                   ci.state                     AS state,
                   ci.country                   AS country,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                  AS currency,
                   count(sc.id)                 AS screen_count,
                   ST_Distance(
                       ci.location,
                       ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                       false)                   AS distance_metres
            FROM cinema ci
            LEFT JOIN screen sc ON sc.cinema_id = ci.id
            GROUP BY ci.id
            ORDER BY ci.location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography
            LIMIT :limit
            """;

    /* Every cinema within a radius, nearest first.

       ST_DWithin rather than a WHERE on ST_Distance. The two return the same
       rows, but ST_DWithin is index-aware: it rewrites into a bounding box the
       GiST index can answer and checks the true distance only on what survives.
       A WHERE on ST_Distance computes a real distance for every cinema and
       filters afterwards, which is a sequential scan wearing a radius.

       The fourth argument is use_spheroid and false is deliberate. It defaults
       to true, and NEAREST already passes false to ST_Distance so its numbers
       agree with the <-> ordering. Left at the default here, the boundary would
       be drawn on a spheroid while the distance printed beside it was measured
       on a sphere. They differ by about 0.3 percent, so about five metres at
       1.5km, and a cinema sitting on the line would appear in the list with a
       distance larger than the radius it was found by.

       The origin is written out three times rather than once in a CTE, for the
       reason NEAREST gives: the KNN operand has to be built from parameters or
       the index scan is lost. */
    private static final String WITHIN = """
            SELECT ci.id                        AS id,
                   ci.name                      AS name,
                   ci.city                      AS city,
                   ci.state                     AS state,
                   ci.country                   AS country,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                  AS currency,
                   count(sc.id)                 AS screen_count,
                   ST_Distance(
                       ci.location,
                       ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                       false)                   AS distance_metres
            FROM cinema ci
            LEFT JOIN screen sc ON sc.cinema_id = ci.id
            WHERE ST_DWithin(
                      ci.location,
                      ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                      :metres,
                      false)
            GROUP BY ci.id
            ORDER BY ci.location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography
            """;

    private static final String DETAIL = """
            SELECT ci.id                        AS id,
                   ci.name                      AS name,
                   ci.city                      AS city,
                   ci.state                     AS state,
                   ci.country                   AS country,
                   ci.address                   AS address,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                  AS currency,
                   ci.timezone                  AS timezone,
                   count(sc.id)                 AS screen_count
            FROM cinema ci
            LEFT JOIN screen sc ON sc.cinema_id = ci.id
            WHERE ci.id = :cinemaId
            GROUP BY ci.id
            """;

    private final JdbcClient jdbc;

    CinemaQuery(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CinemaSummary> all() {
        return jdbc.sql(ALL)
                .query((rs, rowNum) -> toSummary(rs))
                .list();
    }

    /**
     * Every cinema in one country.
     *
     * An unrecognised code returns an empty list rather than a 400.
     * cinema.country is a foreign key to the country table V22 added, so the
     * database already defines which codes exist, and re-checking that set in
     * Java would be the second place to edit when a third market arrives,
     * which is the argument V22 used against repeating a CHECK. "Cinemas in
     * ZZ" also has an honest answer, and it is none.
     */
    @Transactional(readOnly = true)
    public List<CinemaSummary> inCountry(String country) {
        return jdbc.sql(ALL_IN_COUNTRY)
                .param("country", country)
                .query((rs, rowNum) -> toSummary(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public List<CinemaCity> cities() {
        return jdbc.sql(CITIES)
                .query((rs, rowNum) -> new CinemaCity(
                        rs.getString("country"),
                        rs.getString("state"),
                        rs.getString("city"),
                        rs.getInt("cinema_count"),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude")))
                .list();
    }

    @Transactional(readOnly = true)
    public List<NearestCinema> nearest(double lat, double lon, int limit) {
        return jdbc.sql(NEAREST)
                .param("lat", lat)
                .param("lon", lon)
                .param("limit", limit)
                .query((rs, rowNum) -> toNearest(rs))
                .list();
    }

    @Transactional(readOnly = true)
    public List<NearestCinema> within(double lat, double lon, int metres) {
        return jdbc.sql(WITHIN)
                .param("lat", lat)
                .param("lon", lon)
                .param("metres", metres)
                .query((rs, rowNum) -> toNearest(rs))
                .list();
    }

    /**
     * A missing cinema is a 404 here, not an empty Optional for the controller
     * to unwrap. There is one caller and one right answer, and NotFoundException
     * already carries the problem+json shape the rest of the API uses.
     */
    @Transactional(readOnly = true)
    public CinemaDetail detail(long cinemaId) {
        return jdbc.sql(DETAIL)
                .param("cinemaId", cinemaId)
                .query((rs, rowNum) -> new CinemaDetail(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getString("state"),
                        rs.getString("country"),
                        rs.getString("address"),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        rs.getString("currency"),
                        rs.getString("timezone"),
                        rs.getInt("screen_count")))
                .optional()
                .orElseThrow(() -> new NotFoundException(
                        "No cinema with id %d.".formatted(cinemaId)));
    }

    private static CinemaSummary toSummary(ResultSet rs) throws SQLException {
        return new CinemaSummary(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("state"),
                rs.getString("country"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getString("currency"),
                rs.getInt("screen_count"));
    }

    private static NearestCinema toNearest(ResultSet rs) throws SQLException {
        return new NearestCinema(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("city"),
                rs.getString("state"),
                rs.getString("country"),
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getString("currency"),
                rs.getInt("screen_count"),
                (int) Math.round(rs.getDouble("distance_metres")),
                null);
    }
}