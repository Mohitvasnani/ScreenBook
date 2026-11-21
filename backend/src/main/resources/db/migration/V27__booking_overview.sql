-- A readable view of bookings for looking at the data in psql or Neon. Not
-- used by the application. One row per booking: booking_seat fans out to one
-- row per seat, so the seats are folded back with string_agg and the total is
-- summed from the lines rather than stored.
--
-- app_user is a LEFT JOIN because a guest booking has no account. Grouping on
-- the primary keys is enough for Postgres to allow every other column of those
-- tables in the select list.
--
-- Carries email addresses. Nothing in the API reads it.
CREATE VIEW booking_overview AS
SELECT b.reference,
       b.booked_at,
       f.title                                   AS film,
       c.name                                    AS cinema,
       c.city,
       sc.name                                   AS screen,
       st.starts_at,
       st.starts_at AT TIME ZONE c.timezone      AS starts_local,
       string_agg(s.row_label || s.seat_number, ', '
                  ORDER BY s.row_label, s.seat_number) AS seats,
       count(*)                                  AS seat_count,
       (sum(bs.price_paid_minor) / 100.0)::numeric(12, 2) AS total,
       b.currency,
       COALESCE(u.display_name, '(guest)')       AS customer,
       COALESCE(u.email, b.email)                AS contact_email,
       b.user_id IS NOT NULL                     AS has_account
FROM booking b
JOIN booking_seat bs ON bs.booking_id = b.id
JOIN seat         s  ON s.id          = bs.seat_id
JOIN showtime     st ON st.id         = b.showtime_id
JOIN film         f  ON f.id          = st.film_id
JOIN screen       sc ON sc.id         = st.screen_id
JOIN cinema       c  ON c.id          = sc.cinema_id
LEFT JOIN app_user u ON u.id          = b.user_id
GROUP BY b.id, st.id, f.id, sc.id, c.id, u.id;