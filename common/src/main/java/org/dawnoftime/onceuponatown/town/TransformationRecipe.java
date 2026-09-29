package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;

import java.util.List;

public record TransformationRecipe(
    List<ItemCost> inputs,
    Item outputItem,
    int outputAmount,
    int outputSlots,
    int outputSlotSize,
    int unlockAtLevel
) {
    public int outputCapacity() { return outputSlots * outputSlotSize; }
    public boolean isActive(int buildingLevel) { return unlockAtLevel < 0 || buildingLevel >= unlockAtLevel; }
}
