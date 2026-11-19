package com.mohit.screenbook.catalogue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Builds missing rooms at startup, before the showtime seeder runs.
 *
 * Not conditional on screenbook.seed.enabled, unlike the showtime runner, and
 * the distinction is the point. The tests switch screenings off because 6,700
 * of them against 140 seats apiece is close to a million rows in
 * seat_availability. Rooms are structure rather than volume: a cinema with no
 * screens is not a cheaper cinema, it is a broken one, and a test database
 * full of unbookable buildings would hide the bugs the tests exist to find.
 *
 * The @Order is load-bearing. Spring does not order ApplicationRunners unless
 * it is told to, and a screening cannot be scheduled on a screen that does not
 * exist yet. Stating it here is cheaper than discovering it on the one boot
 * where the beans initialise the other way round.
 */
@Component
@Order(1)
class RoomSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoomSeedRunner.class);

    private final RoomSeeder seeder;

    RoomSeedRunner(RoomSeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        RoomSeeder.Result result = seeder.seed();
        log.info("Room seeder: {} screens created, {} seats created.",
                result.screens(), result.seats());
    }
}