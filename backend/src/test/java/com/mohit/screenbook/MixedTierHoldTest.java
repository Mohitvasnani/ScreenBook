package com.mohit.screenbook;

import com.mohit.screenbook.booking.BookingResponse;
import com.mohit.screenbook.booking.BookingService;
import com.mohit.screenbook.hold.HeldSeatView;
import com.mohit.screenbook.hold.HoldDetailResponse;
import com.mohit.screenbook.hold.HoldResponse;
import com.mohit.screenbook.hold.HoldService;
import com.mohit.screenbook.pricing.PriceLineView;
import com.mohit.screenbook.pricing.PricingGroupView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a hold costs when its seats are in more than one tier.
 *
 * Before item 8 a hold covered one row, so one tier, so one base price. Once
 * that rule went, pricing the whole hold off the first seat would have charged
 * every seat at the first seat's tier. These tests pin the replacement: one
 * group per tier, each priced from its own listed price, each summing exactly.
 *
 * Not @Transactional, for the same reason HoldRulesTest is not: the services
 * open their own transactions, and a rollback at the end would hide whether
 * anything was written. Every test takes fresh seats from the availability
 * view, which already excludes what an earlier test held.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class MixedTierHoldTest {

    @Autowired
    HoldService holds;

    @Autowired
    BookingService bookings;

    @Autowired
    JdbcClient jdbc;

    @Test
    @DisplayName("each seat pays its own tier's price")
    void eachSeatPaysItsOwnTier() {
        HoldResponse hold = holdAcrossTwoTiers();

        assertEquals(2, hold.pricingGroups().size(), "one group per tier");

        /* The point of the whole change. Two tiers have two listed prices, so
           a hold that priced everything off the first seat would put the same
           base on both groups. */
        assertNotEquals(
                hold.pricingGroups().get(0).baseMinor(),
                hold.pricingGroups().get(1).baseMinor(),
                "different tiers must not share a base price");

        for (HeldSeatView seat : hold.seats()) {
            assertEquals(
                    groupOf(hold.pricingGroups(), seat.tier()).adjustedMinor(),
                    seat.priceMinor(),
                    "seat " + seat.rowLabel() + seat.seatNumber()
                            + " should carry its own tier's adjusted price");
        }

        assertEquals(
                hold.seats().stream().mapToInt(HeldSeatView::priceMinor).sum(),
                hold.totalMinor());
    }

    @Test
    @DisplayName("every group's base plus its lines equals its adjusted price")
    void everyGroupSumsExactly() {
        HoldResponse hold = holdAcrossTwoTiers();

        for (PricingGroupView group : hold.pricingGroups()) {
            int lines = group.rules().stream()
                    .mapToInt(PriceLineView::adjustmentMinor)
                    .sum();

            assertEquals(
                    group.adjustedMinor(), group.baseMinor() + lines,
                    "the " + group.tier() + " group does not add up");
        }
    }

    @Test
    @DisplayName("a reload reads the same groups that were quoted")
    void reloadReadsTheSameGroups() {
        HoldResponse hold = holdAcrossTwoTiers();

        HoldDetailResponse reloaded = holds.get(hold.holdId());

        assertEquals(hold.pricingGroups(), reloaded.pricingGroups());
        assertEquals(hold.totalMinor(), reloaded.totalMinor());
    }

    @Test
    @DisplayName("confirming charges each seat what was quoted for it")
    void confirmChargesTheQuote() {
        HoldResponse hold = holdAcrossTwoTiers();

        BookingResponse booking =
                bookings.confirm(hold.holdId(), null, "mixed.tier@example.com");

        assertEquals(hold.totalMinor(), booking.totalPaidMinor());

        for (HeldSeatView held : hold.seats()) {
            BookingResponse.BookedSeat paid = booking.seats().stream()
                    .filter(seat -> seat.rowLabel().equals(held.rowLabel())
                            && seat.seatNumber() == held.seatNumber())
                    .findFirst()
                    .orElseThrow();

            assertEquals(held.priceMinor(), paid.pricePaidMinor(),
                    "seat " + held.rowLabel() + held.seatNumber());
        }
    }

    /**
     * The composite foreign key (hold_id, tier) is what stops a rule line
     * naming a tier its hold does not have. Tested in SQL, not through the
     * service, because the service can only ever write consistent rows and the
     * constraint is for the day something else does not.
     */
    @Test
    @DisplayName("a rule line cannot name a tier its hold does not have")
    void ruleMustBelongToAGroupOnItsHold() {
        HoldResponse hold = holdAcrossTwoTiers();

        String absent = jdbc.sql("""
                        SELECT code
                        FROM seat_tier
                        WHERE code NOT IN (SELECT tier
                                           FROM hold_price_group
                                           WHERE hold_id = :holdId)
                        ORDER BY sort_order
                        LIMIT 1
                        """)
                .param("holdId", hold.holdId())
                .query(String.class)
                .single();

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.sql("""
                                INSERT INTO hold_price_rule
                                    (hold_id, tier, code, label,
                                     basis_points, adjustment_minor, sort_order)
                                VALUES (:holdId, :tier, 'STRAY', 'Stray', 0, 0, 99)
                                """)
                        .param("holdId", hold.holdId())
                        .param("tier", absent)
                        .update());
    }

    /* ------------------------------------------------------------- fixtures */

    private record TierSeat(long showtimeId, String tier, long seatId) {
    }

    private static PricingGroupView groupOf(List<PricingGroupView> groups, String tier) {
        return groups.stream()
                .filter(group -> group.tier().equals(tier))
                .findFirst()
                .orElseThrow();
    }

    private HoldResponse holdAcrossTwoTiers() {
        List<TierSeat> picked = oneSeatPerTier();
        assertTrue(picked.size() >= 2,
                "the seeded data should have a showtime with two tiers free");

        TierSeat first = picked.get(0);
        TierSeat second = picked.get(1);

        return holds.claim(
                first.showtimeId(), List.of(first.seatId(), second.seatId()), null);
    }

    /**
     * One free seat per tier, all in one upcoming showtime that has at least
     * two tiers with something free. The CTE picks the showtime; the outer
     * query takes the lowest free seat id in each of its tiers.
     *
     * Every test calls this fresh. Earlier tests hold seats and the view
     * excludes those, so each lands on different seats rather than fighting
     * over the same two.
     */
    private List<TierSeat> oneSeatPerTier() {
        return jdbc.sql("""
                        WITH pick AS (
                            SELECT sa.showtime_id
                            FROM seat_availability sa
                            JOIN showtime st ON st.id = sa.showtime_id
                            WHERE sa.status = 'AVAILABLE'
                              AND st.starts_at > now()
                            GROUP BY sa.showtime_id
                            HAVING count(DISTINCT sa.tier) >= 2
                            ORDER BY sa.showtime_id
                            LIMIT 1)
                        SELECT pick.showtime_id,
                               sa.tier,
                               min(sa.seat_id) AS seat_id
                        FROM seat_availability sa
                        JOIN pick ON pick.showtime_id = sa.showtime_id
                        WHERE sa.status = 'AVAILABLE'
                        GROUP BY pick.showtime_id, sa.tier
                        ORDER BY sa.tier
                        """)
                .query((rs, rowNum) -> new TierSeat(
                        rs.getLong("showtime_id"),
                        rs.getString("tier"),
                        rs.getLong("seat_id")))
                .list();
    }
}