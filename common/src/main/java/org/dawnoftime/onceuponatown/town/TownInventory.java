package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;

import java.util.List;
import java.util.Map;

public class TownInventory {
    private final List<PlacedBuilding> buildings;
    private final Map<Item, Integer> reserve;

    public TownInventory(List<PlacedBuilding> buildings, Map<Item, Integer> reserve) {
        this.buildings = buildings;
        this.reserve = reserve;
    }

    public int getStock(Item item) {
        return buildings.stream().mapToInt(b -> b.getStock(item)).sum()
            + reserve.getOrDefault(item, 0);
    }

    // Sum of capacity_stacks * 64 for this item across all placed buildings.
    // Covers both direct production entries and transformation outputs.
    // Each slot also receives the town-wide stock bonus (extra stacks from granaries etc.).
    public int getMaxStock(Item item) {
        int townStockBonus = buildings.stream()
            .mapToInt(b -> {
                BuildingDef def = BuildingDataHandler.get(b.getDefId()).orElse(null);
                return def == null ? 0 : b.resolvedStockBonus(def);
            })
            .sum();
        return buildings.stream()
            .mapToInt(b -> {
                BuildingDef def = BuildingDataHandler.get(b.getDefId()).orElse(null);
                if (def == null) return 0;
                int fromProduction = def.production.stream()
                    .filter(p -> p.item() == item)
                    .mapToInt(p -> p.capacityUnits() >= 0
                        ? p.capacityUnits()
                        : (p.capacityStacks() + townStockBonus) * 64)
                    .sum();
                int fromTransformations = def.transformations.stream()
                    .filter(t -> t.outputItem() == item)
                    .mapToInt(t -> t.outputCapacityUnits() >= 0
                        ? t.outputCapacityUnits()
                        : (t.outputCapacityStacks() + townStockBonus) * 64)
                    .sum();
                return fromProduction + fromTransformations;
            })
            .sum();
    }

    public void addStock(List<ItemCost> costs) {
        for (ItemCost cost : costs) {
            reserve.merge(cost.item(), cost.amount(), Integer::sum);
        }
    }

    public boolean hasStock(List<ItemCost> costs) {
        return costs.stream().allMatch(cost -> getStock(cost.item()) >= cost.amount());
    }

    // Drains from buildings first (fullest first), then from reserve
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
            }
        }
    }
}
