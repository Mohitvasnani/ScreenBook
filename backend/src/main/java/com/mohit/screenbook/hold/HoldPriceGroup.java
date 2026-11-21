package com.mohit.screenbook.hold;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * One tier on a hold, with that tier's listed price frozen at the moment of
 * the claim.
 *
 * @Embeddable for the same reason HoldSeat is: no identity of its own, cannot
 * exist without its hold, and the table's primary key is (hold_id, tier).
 *
 * A hold can span tiers, and the pricing rules are percentages of a base, so
 * the base has to be stored once per tier. The rules for that tier hang off
 * this row by the composite foreign key (hold_id, tier), which is what stops a
 * rule line naming a tier its hold does not have.
 */
@Embeddable
public class HoldPriceGroup {

    @Column(nullable = false, updatable = false)
    private String tier;

    @Column(name = "base_price_minor", nullable = false, updatable = false)
    private int basePriceMinor;

    protected HoldPriceGroup() {
        // Hibernate builds instances by reflection and needs a no-arg
        // constructor.
    }

    static HoldPriceGroup of(String tier, int basePriceMinor) {
        HoldPriceGroup group = new HoldPriceGroup();
        group.tier = tier;
        group.basePriceMinor = basePriceMinor;
        return group;
    }

    public String getTier() { return tier; }
    public int getBasePriceMinor() { return basePriceMinor; }

    /**
     * Identity is the tier alone, because the table's primary key is
     * (hold_id, tier). Including the price would let this Set hold two
     * elements Postgres then rejects as duplicates. Same reasoning as
     * HoldSeat excluding its price.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HoldPriceGroup that)) return false;
        return Objects.equals(tier, that.tier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier);
    }
}