-- Reseed the week's screenings at Indian local times.
--
-- V4 seeded wall-clock times in Europe/London while the pricing rules read
-- Asia/Kolkata, so an 18:40 screening is 00:10 local and neither the evening
-- nor the weekend rule ever fires on seed data. The rules were not broken; the
-- data was in the wrong timezone.
--
-- V4 also used current_date, which is the session's date rather than India's.
-- That is the same class of mistake: a date or a time without an explicit zone
-- means whatever the server happens to be set to. Everything here says
-- Asia/Kolkata out loud.
--
-- This does not fix staleness. Like V4 it runs once and seeds seven days from
-- that moment, so the demo runs dry a week later. The real answer is a
-- development seeder that runs at startup rather than a migration, and that is
-- not this migration's job.

-- --------------------------------------------------- clear what can be cleared

-- Only screenings nothing refers to. A screening with a booking or a hold
-- against it is somebody's record, and a migration that deletes those to tidy
-- up the seed data would be trading real data for neatness. Those survive as
-- London-timed leftovers, all of them in the past by now, so nothing lists
-- them.
DELETE FROM showtime st
WHERE NOT EXISTS (SELECT 1 FROM booking   b  WHERE b.showtime_id  = st.id)
  AND NOT EXISTS (SELECT 1 FROM seat_hold h  WHERE h.showtime_id  = st.id)
  AND NOT EXISTS (SELECT 1 FROM hold_seat hs WHERE hs.showtime_id = st.id);

-- ------------------------------------------------------------- seed the week

-- Ten slots a day for seven days, chosen so every rule is reachable:
-- daytime weekday screenings fire nothing, the evening ones fire the evening
-- band, Friday and Saturday fire the weekend band on top, and today's later
-- slots fire the lead-time band. Screen 4 is the Luxury hall, so its evening
-- screening is the dearest seat in the system.
--
-- A CTE rather than a CROSS JOIN against a bare VALUES list, because the
-- timestamp expression is needed twice, once to insert and once to filter, and
-- writing it out twice is how the two drift apart.
WITH slot (film_title, screen_name, time_of_day) AS (VALUES
        ('The Long Quiet', 'Screen 1', time '10:00'),
        ('The Long Quiet', 'Screen 1', time '13:20'),
        ('The Long Quiet', 'Screen 1', time '19:10'),
        ('Saltwater',      'Screen 2', time '12:10'),
        ('Saltwater',      'Screen 2', time '18:20'),
        ('Saltwater',      'Screen 2', time '21:40'),
        ('Paper Moons',    'Screen 3', time '11:20'),
        ('Paper Moons',    'Screen 3', time '17:10'),
        ('Paper Moons',    'Screen 4', time '14:40'),
        ('The Long Quiet', 'Screen 4', time '20:20')
     ),
candidate AS (
        -- (now() AT TIME ZONE 'Asia/Kolkata')::date  -> today's date in India
        -- date + time                                -> a wall clock reading
        -- ... AT TIME ZONE 'Asia/Kolkata'            -> a real instant
        SELECT s.film_title,
               s.screen_name,
               (((now() AT TIME ZONE 'Asia/Kolkata')::date + d) + s.time_of_day)
                   AT TIME ZONE 'Asia/Kolkata' AS starts_at
        FROM generate_series(0, 6) AS d
        CROSS JOIN slot s
     )
INSERT INTO showtime (film_id, screen_id, starts_at, currency)
SELECT f.id, sc.id, c.starts_at, 'INR'
FROM candidate c
JOIN film   f  ON f.title = c.film_title
JOIN screen sc ON sc.name = c.screen_name
-- Only future screenings. A seed that includes this morning's slots leaves
-- rows nobody can book, and it makes the tests nondeterministic: they discover
-- a screening from the availability view, and a past one is now rejected.
WHERE c.starts_at > now()
-- A surviving London-timed screening could in principle sit on the same screen
-- at the same instant as a new one. None of the chosen times collide today, so
-- this skips nothing; it is here so the migration cannot fail on a database
-- whose bookings I cannot see.
ON CONFLICT (screen_id, starts_at) DO NOTHING;