package org.dawnoftime.onceuponatown.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ItemCost;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.ProductionEntry;
import org.dawnoftime.onceuponatown.town.ContractEntry;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TownInventory;
import org.dawnoftime.onceuponatown.town.TransformationRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;

public class ProductionManager {

    // Minimum ticks between stock UI pushes per town to avoid flooding during production bursts.
    private static final int UI_PUSH_COOLDOWN = 60;
    private static final Map<Long, Long> lastUiPushTick = new HashMap<>();

    public static boolean tick(Town town, ServerLevel level, long gameTime, long anchorKey) {
        TownInventory inv = town.getTownInventory();
        boolean changed = false;

        int currentActiveResidents = town.getActiveResidents();
        double rawBonus = 0.0;
        int townStockBonus = 0;
        // Town-wide stock bonus: extra capacity stacks added to every productive building (granary mechanic).
        for (PlacedBuilding b : town.getBuildings()) {
            BuildingDef bDef = BuildingDataHandler.get(b.getDefId()).orElse(null);
            if (bDef == null) continue;
            double resolved = b.resolvedProductionBonus(bDef);
            if (resolved != 0.0 && (bDef.requiredResidents <= 0 || currentActiveResidents >= bDef.requiredResidents)) {
                rawBonus += resolved;
            }
            townStockBonus += b.resolvedStockBonus(bDef);
        }
        double bonusMultiplier = 1.0 + rawBonus;

        // Track which item types have already received the town stock bonus — applied once per item, not per building.
        Set<Item> bonusItemsConsumed = new HashSet<>();
        for (PlacedBuilding building : town.getBuildings()) {
            BuildingDef def = BuildingDataHandler.get(building.getDefId()).orElse(null);
            if (def == null) continue;
            BuildingDef.ResolvedBuildingStats stats = def.resolveAtLevel(building.getUpgradeLevel());
            // Herd buildings that were not fed produce nothing; transformations are never blocked.
            if (stats.resolvedHerd() > 0 && !building.isHerdFed()) {
                if (def.isTransformer() && def.transformEveryTicks > 0 && gameTime % def.transformEveryTicks == 0) {
                    changed |= tickTransformer(building, def, inv);
                }
                continue;
            }
            for (ProductionEntry entry : stats.production()) {
                if (entry.everyTicks() <= 0) continue;
                int effectiveTicks = stats.totalCadenceMultiplier() > 0
                    ? (int) Math.max(1, Math.round(entry.everyTicks() / (1.0 + stats.totalCadenceMultiplier())))
                    : entry.everyTicks();
                if (gameTime % effectiveTicks != 0) continue;
                double totalMultiplier = bonusMultiplier * building.getInstanceProductionMultiplier();
                int boostedAmount = (int) Math.round(entry.amount() * totalMultiplier);
                int bonusSlots = bonusItemsConsumed.add(entry.item()) ? townStockBonus : 0;
                int resolvedCapacity = entry.capacity() + bonusSlots * entry.slotSize();
                if (building.produce(entry.item(), boostedAmount, resolvedCapacity)) changed = true;
            }

            if (def.isTransformer() && def.transformEveryTicks > 0
                    && gameTime % def.transformEveryTicks == 0) {
                changed |= tickTransformer(building, def, inv);
            }
        }

        List<ContractEntry> contract = town.getActiveContract();
        if (contract != null) {
            for (ContractEntry entry : contract) {
                if (entry.everyTicks() <= 0) continue;
                if (gameTime % entry.everyTicks() != 0) continue;
                int cap     = entry.item().getMaxStackSize();
                int current = town.getContractStock().getOrDefault(entry.item(), 0);
                int toAdd   = Math.min(entry.amount(), cap - current);
                if (toAdd > 0) {
                    town.addContractStock(entry.item(), toAdd);
                    changed = true;
                }
            }
        }

        if (changed) {
            LevelTowns.get(level).markDirty();
            BlockPos anchorPos = BlockPos.of(anchorKey);
            long lastPush = lastUiPushTick.getOrDefault(anchorKey, 0L);
            if (gameTime - lastPush >= UI_PUSH_COOLDOWN) {
                NetworkHelper.pushStockToWatchers(level, town, anchorPos);
                lastUiPushTick.put(anchorKey, gameTime);
            }
        }

        return changed;
    }

    private static boolean tickTransformer(PlacedBuilding building, BuildingDef def, TownInventory inv) {
        int buildingLevel = building.getUpgradeLevel();
        Map<net.minecraft.world.item.Item, Integer> budget = new HashMap<>();
        for (TransformationRecipe recipe : def.transformations) {
            if (!recipe.isActive(buildingLevel)) continue;
            for (ItemCost input : recipe.inputs()) {
                budget.computeIfAbsent(input.item(), item -> (int)(inv.getStock(item) * def.transformInputRatio));
            }
        }

        Map<net.minecraft.world.item.Item, Integer> consumed = new HashMap<>();
        boolean anyProduced = false;
        boolean passProduced;
        do {
            passProduced = false;
            for (TransformationRecipe recipe : def.transformations) {
                if (!recipe.isActive(buildingLevel)) continue;
                boolean canAfford = recipe.inputs().stream()
                    .allMatch(input -> budget.getOrDefault(input.item(), 0) >= input.amount());
                int currentOutput = building.getStock(recipe.outputItem());
                if (!canAfford || currentOutput + recipe.outputAmount() > recipe.outputCapacity()) continue;
                for (ItemCost input : recipe.inputs()) {
                    budget.merge(input.item(), -input.amount(), Integer::sum);
                    consumed.merge(input.item(), input.amount(), Integer::sum);
                }
                building.forceAdd(recipe.outputItem(), recipe.outputAmount());
                passProduced = true;
                anyProduced = true;
            }
        } while (passProduced);

        if (!consumed.isEmpty()) {
            List<ItemCost> costs = new ArrayList<>();
            consumed.forEach((item, amount) -> costs.add(new ItemCost(item, amount)));
            inv.removeStock(costs);
        }

        return anyProduced;
    }
}
