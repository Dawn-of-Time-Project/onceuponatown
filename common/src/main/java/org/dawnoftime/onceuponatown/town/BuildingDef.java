package org.dawnoftime.onceuponatown.town;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.Collections;
import java.util.List;

public class BuildingDef {
    public final String id;
    public final String namespace;
    public final ResourceLocation nbt;
    // Pool name of this building's entry jigsaw. Must match the targetPool of the connection point.
    // Empty string means this building can be placed at any connection regardless of pool.
    public final String entryPool;
    public final List<ProductionEntry> production;
    public final List<ItemCost> constructionCost;
    // When true, placement snaps Y to ground level (for streets that must follow terrain).
    public final boolean terrainMatching;
    // Item resource location shown as icon in the map tooltip, e.g. "minecraft:yellow_bed".
    public final String iconItem;
    // Logical group for map display: "buildings" or "gardens". Roads are identified by terrainMatching.
    public final String category;
    // XZ footprint for map rendering: list of strings, one per Z row, each char is an X column.
    // '1' = road cell rendered on map, '0' = gap. Null means render bounding box as solid rectangle.
    public final List<String> footprint;
    // Ordered list of transformation recipes. Empty = not a transformer.
    public final List<TransformationRecipe> transformations;
    // Fraction of town stock taken as budget per input item at the start of each transform pass.
    public final float transformInputRatio;
    // Ticks between each transformation pass.
    public final int transformEveryTicks;
    // Additive bonus applied to village-wide production amounts (e.g. 0.03 = +3% per building).
    public final double productionBonus;
    // Extra capacity stacks added to every productive building in the village (e.g. 1 = +64 items per building).
    public final int stockBonus;
    // Number of resident slots this building adds to the village.
    public final int residents;
    // Number of animals this building holds. 0 = no herd logic.
    public final int herd;
    // Maximum number of animals (baby + adult) the Cowherd job targets. 0 = not managed.
    public final int maxHerds;
    // Ordered upgrade levels. Entry [0] = level 1, [1] = level 2, etc. Empty = not upgradable.
    public final List<UpgradeLevel> upgrades;
    // NBT files for each visual upgrade tier. Entry [0] = visual for level 1, [1] = visual for level 2, etc.
    // If absent for a given level, the upgrade is stat-only (no visual change).
    public final List<NbtLevel> nbtLevels;

    // Per-level NBT metadata. undergroundDepth = how many blocks deeper than the base template this level goes.
    // Used to shift the upgrade diff origin downward so underground galleries land at the correct Y.
    public record NbtLevel(ResourceLocation nbt, int undergroundDepth) {}

    // A physical stand position defined in the building NBT via a barrier block placed next to associatedBlock.
    // toolItem: optional item ID the NPC holds while occupying this stand (e.g. "minecraft:wooden_pickaxe").
    public record StandDef(String type, Block associatedBlock, int radius, @org.jetbrains.annotations.Nullable String toolItem) {}
    // Minimum total village residents required before this building can be constructed.
    public final int requiredResidents;
    // Specific buildings that must already exist in the village before this one can be constructed.
    public final List<BuildingRequirement> requiredBuildings;
    // Items injected into the town stock when this building is placed as the village starter. Empty for non-starters.
    public final List<ItemCost> initialStock;
    // Weight units this building consumes from the era cap when placed or queued.
    public final int weight;
    // Block IDs scanned when verifying a SITE_CLEARANCE quest. Empty = no clearance quest.
    public final List<String> obstacleBlocks;
    // Job id of the NPC spawned when this building is placed. Null = no NPC spawn.
    public final String spawnsNpcJob;
    // Items consumed from the player's inventory when queuing this building (refunded on dequeue).
    public final List<ItemCost> playerCost;
    // When true, this building contributes to the Recognition Medal progression widget.
    public final boolean signature;
    // When true, the NBT provides its own underground foundation (dirt surround + carved air tunnel).
    // Skips Pass B anchor fill so the mine shaft is not plugged with dirt. Air at localY < 0 actively carves terrain.
    public final boolean undergroundFoundation;
    // Physical market stand slots defined by this building. Empty for non-market buildings.
    public final List<StandDef> stands;

    // One upgrade step: cost + what it changes. All additive except maxHerdsTarget (absolute target, 0 = no change).
    public record UpgradeLevel(float cadenceMultiplier, int slotsAdd, int amountAdd,
                                int residentsAdd,
                                double productionBonusAdd,
                                int stockBonusAdd,
                                int maxHerdsTarget,
                                int tradeSlotsAdd,
                                float priceDiscountAdd,
                                float contractRatioAdd,
                                List<String> unlockedDisplay,
                                List<ItemCost> upgradeCost) {}

    // A single building prerequisite: N copies of a given defId must already be placed.
    public record BuildingRequirement(String defId, int count) {}

    // Effective stats for a building at a given upgrade level (cached by PlacedBuilding).
    public record ResolvedBuildingStats(List<ProductionEntry> production, double totalCadenceMultiplier,
                                         int resolvedResidents,
                                         int resolvedHerd,
                                         int resolvedMaxHerds,
                                         int resolvedTradeSlots,
                                         float resolvedPriceDiscount,
                                         float resolvedContractRatio) {}

    public BuildingDef(String id, String namespace, ResourceLocation nbt, String entryPool,
                       List<ProductionEntry> production, List<ItemCost> constructionCost,
                       boolean terrainMatching, String iconItem, String category,
                       List<String> footprint,
                       List<TransformationRecipe> transformations,
                       float transformInputRatio, int transformEveryTicks,
                       double productionBonus, int stockBonus, int residents,
                       List<UpgradeLevel> upgrades, List<NbtLevel> nbtLevels,
                       int requiredResidents, List<BuildingRequirement> requiredBuildings,
                       List<ItemCost> initialStock,
                       int herd, int maxHerds, int weight,
                       List<String> obstacleBlocks, String spawnsNpcJob,
                       List<ItemCost> playerCost, boolean signature, boolean undergroundFoundation,
                       List<StandDef> stands) {
        this.id = id;
        this.namespace = namespace;
        this.nbt = nbt;
        this.entryPool = entryPool;
        this.production = production;
        this.constructionCost = constructionCost;
        this.terrainMatching = terrainMatching;
        this.iconItem = iconItem;
        this.category = category;
        this.footprint = footprint;
        this.transformations = transformations;
        this.transformInputRatio = transformInputRatio;
        this.transformEveryTicks = transformEveryTicks;
        this.productionBonus = productionBonus;
        this.stockBonus = stockBonus;
        this.residents = residents;
        this.upgrades = upgrades;
        this.nbtLevels = nbtLevels;
        this.requiredResidents = requiredResidents;
        this.requiredBuildings = requiredBuildings;
        this.initialStock = initialStock;
        this.herd = herd;
        this.maxHerds = maxHerds;
        this.weight = weight;
        this.obstacleBlocks = obstacleBlocks;
        this.spawnsNpcJob = spawnsNpcJob;
        this.playerCost = playerCost;
        this.signature = signature;
        this.undergroundFoundation = undergroundFoundation;
        this.stands = stands != null ? List.copyOf(stands) : List.of();
    }

    // Returns effective production, cadence, residents, herd, maxHerds, trade slots, and price discount at a given upgrade level.
    // Level 0 = base stats with no upgrades applied.
    public ResolvedBuildingStats resolveAtLevel(int level) {
        List<ProductionEntry> activeProduction = production.stream()
            .filter(e -> e.unlockAtLevel() == -1 || e.unlockAtLevel() <= level)
            .toList();
        if (level <= 0 || upgrades.isEmpty()) {
            return new ResolvedBuildingStats(activeProduction, 0.0, residents, herd, maxHerds, 3, 0f, 0.03f);
        }
        int capped = Math.min(level, upgrades.size());
        double totalCadence = 0.0;
        int totalCapAdd = 0;
        int totalAmountAdd = 0;
        int totalResidentsAdd = 0;
        int resolvedMaxHerds = maxHerds;
        int totalTradeSlotsAdd = 0;
        float totalPriceDiscount = 0f;
        float totalContractRatioAdd = 0f;
        for (int i = 0; i < capped; i++) {
            totalCadence           += upgrades.get(i).cadenceMultiplier();
            totalCapAdd            += upgrades.get(i).slotsAdd();
            totalAmountAdd         += upgrades.get(i).amountAdd();
            totalResidentsAdd      += upgrades.get(i).residentsAdd();
            totalTradeSlotsAdd     += upgrades.get(i).tradeSlotsAdd();
            totalPriceDiscount     += upgrades.get(i).priceDiscountAdd();
            totalContractRatioAdd  += upgrades.get(i).contractRatioAdd();
            if (upgrades.get(i).maxHerdsTarget() != 0)
                resolvedMaxHerds = upgrades.get(i).maxHerdsTarget();
        }
        int resolvedResidents   = residents + totalResidentsAdd;
        int resolvedTradeSlots  = 3 + totalTradeSlotsAdd;
        float resolvedContractRatio = 0.03f + totalContractRatioAdd;
        if (totalCapAdd == 0 && totalAmountAdd == 0) {
            return new ResolvedBuildingStats(activeProduction, totalCadence, resolvedResidents, herd, resolvedMaxHerds, resolvedTradeSlots, totalPriceDiscount, resolvedContractRatio);
        }
        int finalCapAdd    = totalCapAdd;
        int finalAmountAdd = totalAmountAdd;
        List<ProductionEntry> adjusted = activeProduction.stream()
            .map(e -> new ProductionEntry(
                e.item(),
                e.amount() + finalAmountAdd,
                e.everyTicks(),
                e.slots() + finalCapAdd,
                e.slotSize(),
                e.unlockAtLevel()))
            .toList();
        return new ResolvedBuildingStats(adjusted, totalCadence, resolvedResidents, herd, resolvedMaxHerds, resolvedTradeSlots, totalPriceDiscount, resolvedContractRatio);
    }

    public boolean isTransformer() { return !transformations.isEmpty(); }
}
