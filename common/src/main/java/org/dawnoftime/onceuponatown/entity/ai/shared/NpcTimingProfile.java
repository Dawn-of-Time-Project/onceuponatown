package org.dawnoftime.onceuponatown.entity.ai.shared;

import java.util.UUID;

// Deterministic per-NPC timing offsets derived from the entity UUID.
// All four offsets fall in [10%, 30%] of MEAL_DURATION_TICKS (1200 ticks = 6-18 seconds).
// Using the same reference for sleep and meal offsets keeps the system uniform:
// one constant, one percentage range, four independent per-NPC values.
public class NpcTimingProfile {

    private static final int REF_TICKS = 1200;
    private static final float MIN_PCT  = 0.10f;
    private static final float MAX_PCT  = 0.30f;

    /** Ticks after the official bedtime before this NPC goes to sleep. */
    public final int sleepOffset;
    /** Ticks after the official wakeup time before this NPC gets up. */
    public final int wakeOffset;
    /** Ticks into the meal window before this NPC starts eating. */
    public final int eatStartOffset;
    /** Ticks before the meal window closes when this NPC stops eating. */
    public final int eatEndOffset;

    public NpcTimingProfile(UUID uuid) {
        long b = uuid.getLeastSignificantBits();
        sleepOffset    = derive(b, 0);
        wakeOffset     = derive(b, 1);
        eatStartOffset = derive(b, 2);
        eatEndOffset   = derive(b, 3);
    }

    private static int derive(long bits, int seed) {
        long h = bits ^ ((long) seed * 0x9e3779b97f4a7c15L);
        float pct = MIN_PCT + (Math.abs(h) % 1000) / 1000f * (MAX_PCT - MIN_PCT);
        return (int)(pct * REF_TICKS);
    }
}
