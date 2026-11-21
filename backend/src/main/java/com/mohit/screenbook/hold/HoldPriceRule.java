package com.mohit.screenbook.hold;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * One line of the breakdown for one tier, frozen onto a hold.
 *
 * @Embeddable for the same reason HoldSeat is: no identity of its own, cannot
 * exist without its hold, and the table's primary key is
 * (hold_id, tier, code).
 *
 * tier is part of the key because a hold can span tiers and every tier gets
 * its own copy of each rule: the percentage is the same, the money is not.
 *
 * label is stored rather than looked up, because it is a snapshot of the
 * explanation the customer was shown. Rewording a band later must not change
 * why an old booking cost what it did, which is the same rule that copies the
 * price onto the booking instead of referencing it.
 */
@Embeddable
public class HoldPriceRule {

    @Column(nullable = false, updatable = false)
    private String tier;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false, updatable = false)
    private String label;

    @Column(name = "basis_points", nullable = false, updatable = false)
    private int basisPoints;

    @Column(name = "adjustment_minor", nullable = false, updatable = false)
    private int adjustmentMinor;

    @Column(name = "sort_order", nullable = false, updatable = false)
    private int sortOrder;

    protected HoldPriceRule() {
        // Hibernate builds instances by reflection and needs a no-arg
        // constructor.
    }

    static HoldPriceRule of(String tier, String code, String label,
                            int basisPoints, int adjustmentMinor, int sortOrder) {
        HoldPriceRule rule = new HoldPriceRule();
        rule.tier = tier;
        rule.code = code;
        rule.label = label;
        rule.basisPoints = basisPoints;
        rule.adjustmentMinor = adjustmentMinor;
        rule.sortOrder = sortOrder;
        return rule;
    }

    public String getTier() { return tier; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public int getBasisPoints() { return basisPoints; }
    public int getAdjustmentMinor() { return adjustmentMinor; }
    public int getSortOrder() { return sortOrder; }

    /**
     * Identity is (tier, code), because the table's primary key is
     * (hold_id, tier, code). Leaving the tier out would make two tiers' lines
     * for the same rule equal, so the Set would silently drop one. Including
     * the amounts would let this Set hold two elements Postgres then rejects as
     * duplicates, turning a bug that fails fast in memory into a constraint
     * violation at flush time. Same reasoning as BookingSeat excluding its
     * price.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HoldPriceRule that)) return false;
        return Objects.equals(tier, that.tier) && Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier, code);
    }
}