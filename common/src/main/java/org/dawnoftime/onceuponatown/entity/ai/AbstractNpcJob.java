package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.TradePriceDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.merchant.SimpleTownMerchant;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcTimingProfile;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.item.CommerceContractItem;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ContractEntry;
import org.dawnoftime.onceuponatown.town.FoodRegistry;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.ProductionEntry;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TransformationRecipe;
import org.dawnoftime.onceuponatown.town.TownInventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractNpcJob implements NpcJob {

    private static final double CONTRACT_SNAPSHOT_RATIO = 0.05;
    protected static final int  CONTRACT_TRADE_PRICE    = 10;

    // Building defIds the NPC will walk to when eating a meal.
    private static final Set<String> MEAL_SPOT_DEF_IDS = Set.of(
        "fountain_place", "lone_garden", "lone_place", "kitchen", "lake", "oven", "market"
    );

    protected final Npc npc;
    protected final NpcTimingProfile timing;
    protected final NpcSleepController sleepController;
    // Shared secondary activity controller. All jobs reuse this instance.
    protected final SecondaryActivityController activityController = new SecondaryActivityController();
    // True while the NPC is walking to the meal spot; eating animation starts only after arrival.
    protected boolean mealNavigating = false;

    protected AbstractNpcJob(Npc npc) {
        this.npc = npc;
        this.timing = new NpcTimingProfile(npc.getUUID());
        this.sleepController = new NpcSleepController(npc, timing);
    }

    /**
     * Default interaction handler: opens the market trade UI when the NPC is performing a SELL
     * secondary activity. Jobs that need additional interaction logic should override this and
     * call super.onPlayerInteract(player) as a fallback.
     */
    @Override
    public InteractionResult onPlayerInteract(Player player) {
        if (!activityController.isPerforming()) return InteractionResult.PASS;
        ActivityDef def = activityController.getActiveDef();
        if (def == null || def.animationType() != AnimationType.SELL) return InteractionResult.PASS;
        if (def.productionBuildings().isEmpty()) return InteractionResult.PASS;
        if (!(npc.level() instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        Town town = findTown(serverLevel, npc);
        if (town == null) return InteractionResult.PASS;

        Set<Item> filter = collectProductionItems(town, def.productionBuildings());
        if (filter.isEmpty()) return InteractionResult.PASS;

        Runnable markDirty = () -> LevelTowns.get(serverLevel).markDirty();
        SimpleTownMerchant merchant = new SimpleTownMerchant(buildMarketOffers(town, filter), town, markDirty);
        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player, Component.translatable("entity.onceuponatown.merchant"), 1);
        return InteractionResult.SUCCESS;
    }

    /**
     * Collects all items currently produced by the listed buildings at their current upgrade level.
     * The union of all production lists is returned — if a building is absent from the town, it is
     * simply skipped (the NPC will sell whatever buildings are actually present).
     */
    private Set<Item> collectProductionItems(Town town, List<String> buildingDefIds) {
        Set<Item> items = new HashSet<>();
        for (PlacedBuilding placed : town.getBuildings()) {
            if (!buildingDefIds.contains(placed.defId)) continue;
            BuildingDef def = BuildingDataHandler.get(placed.defId).orElse(null);
            if (def == null) continue;
            for (ProductionEntry entry : def.resolveAtLevel(placed.getUpgradeLevel()).production()) {
                items.add(entry.item());
            }
        }
        return items;
    }

    /**
     * Builds market trade offers for an NPC at the market.
     * <p>
     * filter — set of items the NPC may sell/buy. Pass null to allow all priced items (merchant
     * primary behaviour). When non-null, only items present in the filter appear in offers.
     * Trade slot count and price discount are read from the market building the NPC is standing in.
     */
    protected MerchantOffers buildMarketOffers(Town town, Set<Item> filter) {
        int tradeSlots = 3;
        float priceDiscount = 0f;
        PlacedBuilding marketBuilding = town.getBuildings().stream()
            .filter(b -> "market".equals(b.defId) && b.bb != null && b.bb.isInside(npc.blockPosition()))
            .findFirst().orElse(null);
        if (marketBuilding != null) {
            BuildingDef def = BuildingDataHandler.get(marketBuilding.defId).orElse(null);
            if (def != null) {
                BuildingDef.ResolvedBuildingStats stats = def.resolveAtLevel(marketBuilding.getUpgradeLevel());
                tradeSlots = stats.resolvedTradeSlots();
                priceDiscount = stats.resolvedPriceDiscount();
            }
        }

        TownInventory inv = town.getTownInventory();
        Set<Item> pricedItems = TradePriceDataHandler.getAllPricedItems();

        List<Item> sellCandidates = new ArrayList<>();
        for (Item item : pricedItems) {
            if (filter != null && !filter.contains(item)) continue;
            int qty = Math.max(1, TradePriceDataHandler.getQuantity(item));
            if (inv.getStock(item) >= qty) sellCandidates.add(item);
        }
        Collections.shuffle(sellCandidates);

        Set<Item> selectedSell = new HashSet<>();
        MerchantOffers offers = new MerchantOffers();
        int n = 0;
        for (Item item : sellCandidates) {
            if (n >= tradeSlots) break;
            int rawPrice = TradePriceDataHandler.getBuyPrice(item);
            int buyPrice = Math.max(1, (int)(rawPrice * (1f - priceDiscount)));
            int qty = Math.max(1, TradePriceDataHandler.getQuantity(item));
            int maxUses = Math.min(inv.getStock(item) / qty, 999);
            if (maxUses <= 0) continue;
            offers.add(new MerchantOffer(
                new ItemStack(Items.EMERALD, buyPrice),
                new ItemStack(item, qty),
                maxUses, 0, 0f
            ));
            selectedSell.add(item);
            n++;
        }

        Set<Item> accepted = town.buildAcceptedItemSet();
        List<Item> buyCandidates = new ArrayList<>();
        for (Item item : pricedItems) {
            if (filter != null && !filter.contains(item)) continue;
            if (!accepted.contains(item)) continue;
            if (selectedSell.contains(item)) continue;
            int qty = Math.max(1, TradePriceDataHandler.getQuantity(item));
            int maxStock = inv.getMaxStock(item);
            int room = (maxStock == 0 ? 999 : maxStock) - inv.getStock(item);
            if (room >= qty) buyCandidates.add(item);
        }
        Collections.shuffle(buyCandidates);

        n = 0;
        for (Item item : buyCandidates) {
            if (n >= tradeSlots) break;
            int rawPrice = TradePriceDataHandler.getSellPrice(item);
            int sellPrice = Math.max(1, (int)(rawPrice * (1f + priceDiscount)));
            int qty = Math.max(1, TradePriceDataHandler.getQuantity(item));
            int maxStock = inv.getMaxStock(item);
            int room = (maxStock == 0 ? 999 : maxStock) - inv.getStock(item);
            int maxUses = Math.min(room / qty, 999);
            if (maxUses <= 0) continue;
            offers.add(new MerchantOffer(
                new ItemStack(item, qty),
                new ItemStack(Items.EMERALD, sellPrice),
                maxUses, 0, 0f
            ));
            n++;
        }

        return offers;
    }

    private static final int DAY_TICKS = 24000;

    // Aggregates village production per item as a per-day total, then takes CONTRACT_SNAPSHOT_RATIO.
    // All resulting ContractEntry values fire once per Minecraft day (everyTicks = DAY_TICKS).
    protected static List<ContractEntry> snapshotProduction(Town town) {
        Map<Item, Double> perDay = new java.util.HashMap<>();

        for (PlacedBuilding building : town.getBuildings()) {
            BuildingDef def = BuildingDataHandler.get(building.getDefId()).orElse(null);
            if (def == null) continue;
            BuildingDef.ResolvedBuildingStats stats = def.resolveAtLevel(building.getUpgradeLevel());

            for (ProductionEntry entry : stats.production()) {
                if (entry.everyTicks() <= 0) continue;
                int effectiveTicks = stats.totalCadenceMultiplier() > 0
                    ? (int) Math.max(1, Math.round(entry.everyTicks() / (1.0 + stats.totalCadenceMultiplier())))
                    : entry.everyTicks();
                perDay.merge(entry.item(), entry.amount() * (DAY_TICKS / (double) effectiveTicks), Double::sum);
            }

            if (def.isTransformer() && def.transformEveryTicks > 0) {
                for (TransformationRecipe recipe : def.transformations) {
                    if (!recipe.isActive(building.getUpgradeLevel())) continue;
                    perDay.merge(recipe.outputItem(), recipe.outputAmount() * (DAY_TICKS / (double) def.transformEveryTicks), Double::sum);
                }
            }
        }

        List<ContractEntry> result = new ArrayList<>();
        for (Map.Entry<Item, Double> e : perDay.entrySet()) {
            int contractAmount = (int) Math.floor(e.getValue() * CONTRACT_SNAPSHOT_RATIO);
            if (contractAmount <= 0) continue;
            result.add(new ContractEntry(e.getKey(), contractAmount, DAY_TICKS));
        }
        return result;
    }

    // Sends the NPC on a short random walk when it has nothing else to do.
    protected void maybeWander() {
        if (!npc.getNavigation().isDone()) return;
        Vec3 target = DefaultRandomPos.getPos(npc, 10, 7);
        if (target != null) npc.getNavigation().moveTo(target.x, target.y, target.z, 0.4);
    }

    // Emits food particles every 3 ticks while the arms are held up (phase 10-60 of the 200-tick cycle).
    // Phase is relative to when eating started so staggered NPC schedules don't drift out of sync.
    protected void emitEatParticles() {
        int phase = (npc.tickCount - npc.getEatStartTick()) % 200;
        if (phase >= 10 && phase < 60 && (phase - 10) % 12 == 0) {
            npc.playSound(net.minecraft.sounds.SoundEvents.GENERIC_EAT, 0.5f, 0.9f + npc.getRandom().nextFloat() * 0.2f);
        }
        if (phase >= 10 && phase < 60 && npc.tickCount % 3 == 0) {
            ItemStack held = npc.getMainHandItem();
            if (!held.isEmpty()) npc.triggerEatParticles(held);
        }
    }

    // Returns the best available food item from the village stock (strongest FUV first), or null if none.
    protected Item selectMealItem(Town town) {
        for (Map.Entry<Item, Integer> entry : FoodRegistry.residentEntriesInOrder()) {
            if (town.getTownInventory().getStock(entry.getKey()) > 0) return entry.getKey();
        }
        return null;
    }

    // Called each tick while EATING: starts the eating animation once the NPC has reached the spot.
    protected void tryStartEatingAnimation(Town town) {
        if (!mealNavigating || !npc.getNavigation().isDone()) return;
        mealNavigating = false;
        Item food = selectMealItem(town);
        if (food != null) npc.holdInMainHand(new ItemStack(food));
        npc.setEating(true);
    }

    // Navigates the NPC to the nearest meal-spot building (fountain, garden, kitchen...).
    // Falls back to stopping in place when no suitable building is found.
    protected void navigateToMealSpot(Town town) {
        BlockPos npcPos = npc.blockPosition();
        PlacedBuilding best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (PlacedBuilding b : town.getBuildings()) {
            if (!MEAL_SPOT_DEF_IDS.contains(b.defId)) continue;
            BlockPos target = mealSpotTarget(b);
            double d = npcPos.distSqr(target);
            if (d < bestDistSq) {
                bestDistSq = d;
                best = b;
            }
        }

        mealNavigating = true;
        if (best != null) {
            BlockPos target = mealSpotTarget(best);
            npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
        } else {
            npc.getNavigation().stop();
        }
    }

    private static BlockPos mealSpotTarget(PlacedBuilding b) {
        if (b.entryPos != null) return b.entryPos;
        if (b.bb != null) return new BlockPos(
            (b.bb.minX() + b.bb.maxX()) / 2,
            b.bb.minY(),
            (b.bb.minZ() + b.bb.maxZ()) / 2
        );
        return b.worldPos;
    }
}
