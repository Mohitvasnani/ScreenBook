package com.mohit.screenbook.hold;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * One line of the breakdown, frozen onto a hold.
 *
 * @Embeddable for the same reason HoldSeat is: no identity of its own, cannot
 * exist without its hold, and the table's primary key is (hold_id, code).
 *
 * label is stored rather than looked up, because it is a snapshot of the
 * explanation the customer was shown. Rewording a band later must not change
 * why an old booking cost what it did, which is the same rule that copies the
 * price onto the booking instead of referencing it.
 */
@Embeddable
public class HoldPriceRule {

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

    static HoldPriceRule of(String code, String label, int basisPoints,
                            int adjustmentMinor, int sortOrder) {
        HoldPriceRule rule = new HoldPriceRule();
        rule.code = code;
        rule.label = label;
        rule.basisPoints = basisPoints;
        rule.adjustmentMinor = adjustmentMinor;
        rule.sortOrder = sortOrder;
        return rule;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public int getBasisPoints() { return basisPoints; }
    public int getAdjustmentMinor() { return adjustmentMinor; }
    public int getSortOrder() { return sortOrder; }

    /**
     * Identity is the code alone, because the table's primary key is
     * (hold_id, code). Including the amounts would let this Set hold two
     * elements Postgres then rejects as duplicates, turning a bug that fails
     * fast in memory into a constraint violation at flush time. Same reasoning
     * as BookingSeat excluding its price.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HoldPriceRule that)) return false;
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}