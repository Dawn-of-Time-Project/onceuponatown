package org.dawnoftime.onceuponatown.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
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
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.datapack.TradePriceDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.merchant.SimpleTownMerchant;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingEntryNav;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
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
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractNpcJob implements NpcJob {

    public enum NpcBaseState { SLEEPING, EATING, MAIN, SECONDARY, WANDER }
    public enum TradeScope { VILLAGE, BUILDING }

    protected static final double MEAL_ARRIVAL_RADIUS = 2.0;

    private static final int    WANDER_RADIUS = 10;
    private static final int    WANDER_HEIGHT = 7;
    private static final double WANDER_SPEED  = 0.5;

    protected static final double BASE_CONTRACT_RATIO = 0.03;
    protected static final int  CONTRACT_TRADE_PRICE    = 10;

    public static final double NEARBY_PLAYER_RADIUS = 8.0;

    public static Player findNearestPlayer(Npc npc, double radius) {
        return npc.level().getEntitiesOfClass(
                Player.class, npc.getBoundingBox().inflate(radius))
            .stream()
            .min(Comparator.comparingDouble(p -> p.distanceToSqr(npc)))
            .orElse(null);
    }

    private static final Set<String> MEAL_SPOT_DEF_IDS = Set.of(
        "fountain_place", "lone_garden", "lone_place", "kitchen", "lake", "oven", "market"
    );

    protected final Npc npc;
    protected final NpcTimingProfile timing;
    protected final NpcSleepController sleepController;
    protected final SecondaryActivityController activityController = new SecondaryActivityController();
    protected GoToPosition mealGoTo = null;
    private BlockPos mealTargetPos = null;
    protected NpcBaseState baseState = NpcBaseState.WANDER;

    protected Player cachedLookPlayer = null;
    private int lookScanCooldown = 0;

    public NpcBaseState getBaseState() { return baseState; }

    public Npc getNpc() { return npc; }

    // Called by each concrete job to invoke its controller's dispatchForNpc().
    // Invoked at every dispatch point: wake from sleep, exit eating, main work complete.
    protected abstract void dispatchFromController(ServerLevel level, Town town);

    protected AbstractNpcJob(Npc npc) {
        this.npc = npc;
        this.timing = new NpcTimingProfile(npc.getUUID());
        this.sleepController = new NpcSleepController(npc, timing);
    }

    // --- Controller assignment API ---

    public final void enterWander() {
        baseState = NpcBaseState.WANDER;
    }

    public final void dispatchOnInit(ServerLevel level, Town town) {
        dispatchFromController(level, town);
    }

    public final boolean isAvailableForAssignment() {
        return baseState == NpcBaseState.WANDER;
    }

    public long getAssignedBuildingId() { return -1L; }

    protected void onControllerAssignMain(PlacedBuilding building, ServerLevel level, Town town) {}

    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building) {}

    public final void receiveMainAssignment(PlacedBuilding building, ServerLevel level, Town town) {
        if (baseState == NpcBaseState.SECONDARY) activityController.cancel(npc);
        onControllerAssignMain(building, level, town);
        baseState = NpcBaseState.MAIN;
    }

    public final void receiveSecondaryAssignment(ActivityDef def, PlacedBuilding building) {
        onControllerAssignSecondary(def, building);
        baseState = activityController.isActive() ? NpcBaseState.SECONDARY : NpcBaseState.WANDER;
    }

    public final void receiveSecondaryAssignment(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        if (baseState == NpcBaseState.SECONDARY) activityController.cancel(npc);
        onControllerAssignSecondary(def, building, level, town);
        baseState = activityController.isActive() ? NpcBaseState.SECONDARY : NpcBaseState.WANDER;
    }

    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {}

    // --- Production interaction ---

    public List<String> getProductionBuildings() { return List.of(); }

    protected boolean canTradeNow() {
        return (baseState == NpcBaseState.SECONDARY || baseState == NpcBaseState.WANDER)
            && !npc.getNavigation().isInProgress();
    }

    protected TradeScope getTradeScope() { return TradeScope.BUILDING; }

    protected boolean shouldLookAtPlayer() {
        return baseState == NpcBaseState.SECONDARY || baseState == NpcBaseState.WANDER;
    }

    protected Component getTradeTitle() {
        return Component.translatable("entity.onceuponatown." + getJobId());
    }

    private void tickLookControl() {
        if (!shouldLookAtPlayer()) {
            npc.setSuppressLookAtPlayer(true);
            cachedLookPlayer = null;
            lookScanCooldown = 0;
            return;
        }
        npc.setSuppressLookAtPlayer(false);
        if (--lookScanCooldown <= 0) {
            lookScanCooldown = 10;
            cachedLookPlayer = findNearestPlayer(npc, NEARBY_PLAYER_RADIUS);
        }
        if (cachedLookPlayer != null) {
            npc.getLookControl().setLookAt(cachedLookPlayer, 30f, 30f);
        }
    }

    @Override
    public InteractionResult onPlayerInteract(Player player) {
        if (!canTradeNow()) return InteractionResult.PASS;
        if (!(npc.level() instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        Town town = findTown(serverLevel, npc);
        if (town == null) return InteractionResult.PASS;

        Set<Item> filter = null;
        if (getTradeScope() == TradeScope.BUILDING) {
            List<String> productionBuildingIds = getProductionBuildings();
            if (productionBuildingIds.isEmpty()) return InteractionResult.PASS;
            filter = collectProductionItems(town, productionBuildingIds);
            if (filter.isEmpty()) return InteractionResult.PASS;
        }

        Runnable markDirty = () -> LevelTowns.get(serverLevel).markDirty();
        SimpleTownMerchant merchant = new SimpleTownMerchant(buildMarketOffers(town, filter), town, markDirty);
        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player, getTradeTitle(), 1);
        return InteractionResult.SUCCESS;
    }

    private Set<Item> collectProductionItems(Town town, List<String> buildingDefIds) {
        Set<Item> items = new HashSet<>();
        for (PlacedBuilding placed : town.getBuildings()) {
            if (!buildingDefIds.contains(placed.defId)) continue;
            BuildingDef def = BuildingDataHandler.get(placed.defId).orElse(null);
            if (def == null) continue;
            int level = placed.getUpgradeLevel();
            for (ProductionEntry entry : def.resolveAtLevel(level).production()) {
                items.add(entry.item());
            }
            if (def.isTransformer()) {
                for (TransformationRecipe recipe : def.transformations) {
                    if (recipe.isActive(level)) items.add(recipe.outputItem());
                }
            }
        }
        return items;
    }

    protected MerchantOffers buildMarketOffers(Town town, Set<Item> filter) {
        int tradeSlots = 3;
        float priceDiscount = 0f;
        PlacedBuilding bestMarket = null;
        int bestMarketLevel = -1;
        for (PlacedBuilding b : town.getBuildings()) {
            if (!"market".equals(b.defId)) continue;
            int lvl = b.getUpgradeLevel();
            if (lvl > bestMarketLevel) { bestMarketLevel = lvl; bestMarket = b; }
        }
        if (bestMarket != null) {
            BuildingDef def = BuildingDataHandler.get(bestMarket.defId).orElse(null);
            if (def != null) {
                BuildingDef.ResolvedBuildingStats stats = def.resolveAtLevel(bestMarket.getUpgradeLevel());
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

    // --- Lifecycle hooks ---

    protected void onEnterSleep() {}

    protected void onResetWork() {}

    // Called when builder completes a build and needs to notify BuildWorkManager.
    protected void onWorkReadyResumed() {}

    protected GoToPosition buildMealNavigation(Town town, double walkSpeed, double arrivalRadius) { return null; }

    protected void onEnterEating(Town town) {}

    protected void onResync(@org.jetbrains.annotations.Nullable Town town) {}

    // --- Lifecycle ---

    public void onRemoved() {
        if (baseState == NpcBaseState.SECONDARY) activityController.cancel(npc);
    }

    // --- State transitions ---

    protected final void enterSleep() {
        if (baseState == NpcBaseState.SECONDARY) activityController.cancel(npc);
        onEnterSleep();
        npc.getNavigation().stop();
        npc.freeHands();
        onResetWork();
        sleepController.reset();
        baseState = NpcBaseState.SLEEPING;
    }

    protected final void enterEating(Town town, SleepConfig cfg) {
        if (baseState == NpcBaseState.SECONDARY) activityController.cancel(npc);
        npc.freeHands();
        onEnterEating(town);
        GoToPosition override = buildMealNavigation(town, cfg.getWalkSpeed(), MEAL_ARRIVAL_RADIUS);
        onResetWork();
        if (override != null) {
            mealGoTo = override;
        } else {
            navigateToMealSpot(town, cfg.getWalkSpeed(), MEAL_ARRIVAL_RADIUS);
        }
        baseState = NpcBaseState.EATING;
    }

    protected final void exitEating(ServerLevel level, Town town) {
        mealGoTo = null;
        mealTargetPos = null;
        npc.setEating(false);
        npc.freeHands();
        npc.getNavigation().stop();
        baseState = NpcBaseState.WANDER;
        dispatchFromController(level, town);
    }

    protected final void tickSharedPreamble(ServerLevel level, Town town, SleepConfig cfg) {
        long dayTime = level.getDayTime() % DAY_TICKS;
        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, baseState == NpcBaseState.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC) { onResync(town); baseState = NpcBaseState.SLEEPING; }
        if (sc == NpcSleepController.SleepCheck.TRIGGER) enterSleep();
        if (town != null && baseState != NpcBaseState.SLEEPING && baseState != NpcBaseState.EATING
                && town.isMealTimeFor(level.getGameTime(), timing.eatStartOffset, 0)) {
            enterEating(town, cfg);
        }
        tickLookControl();
    }

    protected final void tickSleepingBase(ServerLevel level, Town town, SleepConfig cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            baseState = NpcBaseState.WANDER;
            dispatchFromController(level, town);
        }
    }

    protected final void tickEatingBase(ServerLevel level, Town town) {
        if (town == null || !town.isMealTimeFor(level.getGameTime(), 0, timing.eatEndOffset)) exitEating(level, town);
        else { tryStartEatingAnimation(town); if (npc.isEating()) emitEatParticles(); }
    }

    protected final void tickSecondaryBase(ServerLevel level, Town town, SleepConfig cfg) {
        SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.getWalkSpeed(), cachedLookPlayer);
        if (r == SecondaryActivityController.Result.NOT_FOUND) {
            dispatchFromController(level, town);
        }
    }

    public static final int DAY_TICKS = 24000;

    protected static List<ContractEntry> snapshotProduction(Town town, double contractRatio) {
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
            int contractAmount = (int) Math.floor(e.getValue() * contractRatio);
            if (contractAmount <= 0) continue;
            result.add(new ContractEntry(e.getKey(), contractAmount, DAY_TICKS));
        }
        return result;
    }

    public static int speedToTicks(float speed) {
        return Math.max(1, (int)(speed * 20));
    }

    protected final void maybeWander() {
        if (!npc.getNavigation().isDone()) return;
        Vec3 target = DefaultRandomPos.getPos(npc, WANDER_RADIUS, WANDER_HEIGHT);
        if (target != null) npc.getNavigation().moveTo(target.x, target.y, target.z, WANDER_SPEED);
    }

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

    protected Item selectMealItem(Town town) {
        for (Map.Entry<Item, Integer> entry : FoodRegistry.residentEntriesInOrder()) {
            if (town.getTownInventory().getStock(entry.getKey()) > 0) return entry.getKey();
        }
        return null;
    }

    protected void tryStartEatingAnimation(Town town) {
        if (mealGoTo == null) return;
        if (!mealGoTo.tick()) return;
        mealGoTo = null;
        Item food = selectMealItem(town);
        if (food != null) npc.holdInMainHand(new ItemStack(food));
        npc.setEating(true);
    }

    protected void navigateToMealSpot(Town town, double walkSpeed, double arrivalRadius) {
        if (!(npc.level() instanceof ServerLevel level)) return;
        if (mealTargetPos == null) {
            PlacedBuilding best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (PlacedBuilding b : town.getBuildings()) {
                if (!MEAL_SPOT_DEF_IDS.contains(b.defId)) continue;
                double d = npc.blockPosition().distSqr(b.worldPos);
                if (d < bestDistSq) { bestDistSq = d; best = b; }
            }
            if (best == null) { npc.getNavigation().stop(); return; }
            mealTargetPos = BuildingEntryNav.resolveEntryPos(level, best);
        }
        mealGoTo = new GoToPosition(npc, mealTargetPos, walkSpeed, arrivalRadius);
    }
}
