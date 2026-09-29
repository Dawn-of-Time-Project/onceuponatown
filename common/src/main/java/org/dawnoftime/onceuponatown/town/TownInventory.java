package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;

import java.util.List;
import java.util.Map;

public class TownInventory {
    private final List<PlacedBuilding> buildings;
    private final Map<Item, Integer> reserve;
    private final Town town;

    public TownInventory(List<PlacedBuilding> buildings, Map<Item, Integer> reserve, Town town) {
        this.buildings = buildings;
        this.reserve = reserve;
        this.town = town;
    }

    public int getStock(Item item) {
        return buildings.stream().mapToInt(b -> b.getStock(item)).sum()
            + reserve.getOrDefault(item, 0)
            + town.getContractStock().getOrDefault(item, 0);
    }

    // Sum of slots * slotSize for this item across all placed buildings.
    // Covers both direct production entries and transformation outputs.
    // Each extra slot from granaries/barns is scaled by the item's own slotSize.
    public int getMaxStock(Item item) {
        int townStockBonus = buildings.stream()
            .mapToInt(b -> {
                BuildingDef def = BuildingDataHandler.get(b.getDefId()).orElse(null);
                return def == null ? 0 : b.resolvedStockBonus(def);
            })
            .sum();
        int max = 0;
        int itemSlotSize = 64;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.getDefId()).orElse(null);
            if (def == null) continue;
            for (ProductionEntry p : def.resolveAtLevel(b.getUpgradeLevel()).production()) {
                if (p.item() == item) {
                    max += p.capacity();
                    itemSlotSize = p.slotSize();
                }
            }
            for (TransformationRecipe t : def.transformations) {
                if (t.outputItem() == item) {
                    max += t.outputCapacity();
                    itemSlotSize = t.outputSlotSize();
                }
            }
        }
        if (max > 0) max += townStockBonus * itemSlotSize;
        List<ContractEntry> contract = town.getActiveContract();
        if (contract != null) {
            for (ContractEntry e : contract) {
                if (e.item() == item) {
                    max += item.getMaxStackSize();
                    break;
                }
            }
        }
        return max;
    }

    public void addStock(List<ItemCost> costs) {
        for (ItemCost cost : costs) {
            reserve.merge(cost.item(), cost.amount(), Integer::sum);
        }
    }

    public boolean hasStock(List<ItemCost> costs) {
        return costs.stream().allMatch(cost -> getStock(cost.item()) >= cost.amount());
    }

    // Drains from buildings first (fullest first), then from reserve, then from contract stock
    public void removeStock(List<ItemCost> costs) {
        for (ItemCost cost : costs) {
            int remaining = cost.amount();
            List<PlacedBuilding> sorted = buildings.stream()
                .sorted((a, b) -> b.getStock(cost.item()) - a.getStock(cost.item()))
                .toList();
            for (PlacedBuilding building : sorted) {
                if (remaining <= 0) break;
                remaining -= building.drain(cost.item(), remaining);
            }
            if (remaining > 0 && reserve.containsKey(cost.item())) {
                int inReserve = reserve.getOrDefault(cost.item(), 0);
                int taken = Math.min(inReserve, remaining);
                reserve.put(cost.item(), inReserve - taken);
                remaining -= taken;
            }
            if (remaining > 0) {
                int inContract = town.getContractStock().getOrDefault(cost.item(), 0);
                int take = Math.min(remaining, inContract);
                if (take > 0) town.removeContractStock(cost.item(), take);
            }
        }
    }
}
