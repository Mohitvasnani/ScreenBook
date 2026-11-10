package com.mohit.screenbook.catalogue;

import com.mohit.screenbook.common.NotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Cinemas, for the map, the city picker and the nearest-N list.
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

    /* Every cinema, for the map and for the city picker to group.
       130 rows, no pagination, no filter by city: the client already holds the
       whole list to draw markers, so a city filter would be a second round trip
       for something it can do with a filter() call.

       LEFT JOIN, not JOIN. A cinema with no screens yet should still appear on
       the map as a building that exists, and an inner join would silently hide
       it. count(sc.id) counts matched rows, not NULLs, so that cinema reports
       0 rather than 1.

       GROUP BY ci.id alone, with four more ci columns in the SELECT. Postgres
       allows that when the grouping column is the table's primary key, because
       every other column of that row is then functionally dependent on it and
       there is provably one value per group. Most other databases make you
       list all five. */
    private static final String ALL = """
            SELECT ci.id                       AS id,
                   ci.name                     AS name,
                   ci.city                     AS city,
                   ci.state                    AS state,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                 AS currency,
                   count(sc.id)                AS screen_count
            FROM cinema ci
            LEFT JOIN screen sc ON sc.cinema_id = ci.id
            GROUP BY ci.id
            ORDER BY ci.city, ci.name
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
            SELECT ci.id                       AS id,
                   ci.name                     AS name,
                   ci.city                     AS city,
                   ci.state                    AS state,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                 AS currency,
                   count(sc.id)                AS screen_count,
                   ST_Distance(
                       ci.location,
                       ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                       false)                  AS distance_metres
            FROM cinema ci
            LEFT JOIN screen sc ON sc.cinema_id = ci.id
            GROUP BY ci.id
            ORDER BY ci.location <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography
            LIMIT :limit
            """;

    private static final String DETAIL = """
            SELECT ci.id                       AS id,
                   ci.name                     AS name,
                   ci.city                     AS city,
                   ci.state                    AS state,
                   ci.address                  AS address,
                   ST_Y(ci.location::geometry)  AS latitude,
                   ST_X(ci.location::geometry)  AS longitude,
                   ci.currency                 AS currency,
                   ci.timezone                 AS timezone,
                   count(sc.id)                AS screen_count
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

    @Transactional(readOnly = true)
    public List<NearestCinema> nearest(double lat, double lon, int limit) {
        return jdbc.sql(NEAREST)
                .param("lat", lat)
                .param("lon", lon)
                .param("limit", limit)
                .query((rs, rowNum) -> new NearestCinema(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("city"),
                        rs.getString("state"),
                        rs.getDouble("latitude"),
                        rs.getDouble("longitude"),
                        rs.getString("currency"),
                        rs.getInt("screen_count"),
                        (int) Math.round(rs.getDouble("distance_metres"))))
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
                rs.getDouble("latitude"),
                rs.getDouble("longitude"),
                rs.getString("currency"),
                rs.getInt("screen_count"));
    }
}