-- A hold can now span tiers, so a price belongs to a (hold, tier) pair rather
-- than to the hold. The pricing rules are percentages of a base and the
-- percentages are the same for every tier, so what differs per tier is the
-- base and the rupee amount each rule worked out to.

-- One row per tier on a hold, carrying that tier's listed price.
CREATE TABLE hold_price_group (
    hold_id          uuid NOT NULL REFERENCES seat_hold(id) ON DELETE CASCADE,
    tier             text NOT NULL REFERENCES seat_tier(code),
    base_price_minor int  NOT NULL CHECK (base_price_minor > 0),

    PRIMARY KEY (hold_id, tier)
);

-- Every hold before this migration covered exactly one tier, so this produces
-- exactly one group per hold. The holds cannot be deleted instead:
-- booking.hold_id references them.
INSERT INTO hold_price_group (hold_id, tier, base_price_minor)
SELECT h.id, s.tier, h.base_price_minor
  FROM seat_hold h
  JOIN hold_seat hs ON hs.hold_id = h.id
  JOIN seat s       ON s.id = hs.seat_id
 GROUP BY h.id, s.tier, h.base_price_minor;

-- The rules move under the group. Added nullable, filled from the group the
-- hold's one tier produced, then locked down: a rule row that found no group
-- fails SET NOT NULL and fails the migration, rather than leaving a null
-- nobody looks at.
ALTER TABLE hold_price_rule ADD COLUMN tier text;

UPDATE hold_price_rule r
   SET tier = g.tier
  FROM hold_price_group g
 WHERE g.hold_id = r.hold_id;

ALTER TABLE hold_price_rule ALTER COLUMN tier SET NOT NULL;

ALTER TABLE hold_price_rule DROP CONSTRAINT hold_price_rule_pkey;
ALTER TABLE hold_price_rule ADD PRIMARY KEY (hold_id, tier, code);

-- The composite foreign key means a rule line cannot name a tier its hold does
-- not have. Same device hold_seat uses to stop its copy of showtime_id
-- disagreeing with the parent. It makes the old single-column key redundant.
ALTER TABLE hold_price_rule
    ADD FOREIGN KEY (hold_id, tier)
        REFERENCES hold_price_group (hold_id, tier) ON DELETE CASCADE;

ALTER TABLE hold_price_rule DROP CONSTRAINT hold_price_rule_hold_id_fkey;

-- The base price no longer lives on the hold. The availability views alias
-- p.price_minor as base_price_minor themselves, so nothing depends on this.
ALTER TABLE seat_hold DROP COLUMN base_price_minor;