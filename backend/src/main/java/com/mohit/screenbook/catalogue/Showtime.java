package com.mohit.screenbook.catalogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Nothing reads this entity. It exists so that
 * spring.jpa.hibernate.ddl-auto=validate has something to check the showtime
 * table against at startup, which is exactly what caught the dropped column
 * when V12 ran. Every read goes through ShowtimeQuery in plain SQL, because
 * the list response is a join plus aggregates and matches no entity.
 *
 * No priceMinor since V12. A seat's price comes from hall_class_tier_price,
 * keyed on the screen's hall class and the seat's tier, so a price on the
 * screening as well would be a second place for the truth to live.
 */
@Entity
@Table(name = "showtime")
public class Showtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "film_id", nullable = false)
    private Long filmId;

    @Column(name = "screen_id", nullable = false)
    private Long screenId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(nullable = false)
    private String currency;

    protected Showtime() {
    }

    public Long getId() { return id; }
    public Long getFilmId() { return filmId; }
    public Long getScreenId() { return screenId; }
    public Instant getStartsAt() { return startsAt; }
    public String getCurrency() { return currency; }
}