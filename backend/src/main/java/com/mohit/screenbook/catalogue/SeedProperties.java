package com.mohit.screenbook.catalogue;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;
import java.util.List;

/**
 * The seeding schedule. In config rather than in the SQL string, for the same
 * reason the pricing bands are: these are numbers a human tunes for a demo,
 * and a number you have to recompile to change is a number nobody changes.
 *
 * slots binds as LocalTime rather than String so a typo fails at startup with
 * a parse error naming the property, instead of reaching Postgres as an
 * invalid cast inside a 20 line statement.
 *
 * The checks below run whenever the record is constructed, which is at
 * startup, enabled or not. Zero days or an empty slot list would not throw
 * anywhere later, it would quietly seed nothing, and a seeder that silently
 * does nothing is the exact failure this whole class exists to prevent.
 */
@ConfigurationProperties(prefix = "screenbook.seed")
public record SeedProperties(
        boolean enabled,
        int days,
        List<LocalTime> slots,
        int keepPastDays
) {
    public SeedProperties {
        if (days < 1) {
            throw new IllegalArgumentException(
                    "screenbook.seed.days must be at least 1, was " + days);
        }
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException(
                    "screenbook.seed.slots must list at least one time of day");
        }
        if (keepPastDays < 0) {
            throw new IllegalArgumentException(
                    "screenbook.seed.keep-past-days cannot be negative, was " + keepPastDays);
        }
    }
}