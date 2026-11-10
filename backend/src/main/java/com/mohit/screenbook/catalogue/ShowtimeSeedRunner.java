package com.mohit.screenbook.catalogue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Calls the seeder once at startup.
 *
 * Separate from ShowtimeSeeder so that the seeder is always a bean and only
 * this is conditional. The tests switch screenbook.seed.enabled off, because
 * 6,700 screenings against 140 seats apiece is close to a million rows in
 * seat_availability and ConcurrencyTest finds its seats by scanning that view.
 * The seeder itself is still tested, by a test that autowires it and calls it.
 *
 * An exception here propagates and stops the application. That is deliberate.
 * On Render a deploy that fails to start leaves the previous instance serving,
 * so a broken seeder cannot take the demo down, whereas a logged-and-swallowed
 * failure means the site quietly runs out of screenings and nobody finds out
 * for a month.
 */
@Component
@ConditionalOnProperty(name = "screenbook.seed.enabled", havingValue = "true")
class ShowtimeSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShowtimeSeedRunner.class);

    private final ShowtimeSeeder seeder;

    ShowtimeSeedRunner(ShowtimeSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        ShowtimeSeeder.Result result = seeder.seed();
        log.info("Showtime seeder: {} screenings created, {} expired removed.",
                result.inserted(), result.pruned());
    }
}