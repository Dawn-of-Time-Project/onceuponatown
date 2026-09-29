package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;

public record ProductionEntry(Item item, int amount, int everyTicks, int slots, int slotSize, int unlockAtLevel) {
    // unlockAtLevel: -1 = always active; N = requires building upgrade level >= N.
    public int capacity() { return slots * slotSize; }
}
