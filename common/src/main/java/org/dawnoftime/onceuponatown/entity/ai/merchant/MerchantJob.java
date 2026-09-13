package org.dawnoftime.onceuponatown.entity.ai.merchant;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.datapack.MerchantConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.item.CommerceContractItem;
import org.dawnoftime.onceuponatown.registry.ItemRegistry;
import org.dawnoftime.onceuponatown.town.ContractEntry;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;

public class MerchantJob extends AbstractNpcJob {

    private enum State { IDLE, AT_STAND, SLEEPING, ACTIVITY, EATING }

    private State current = State.IDLE;

    private final BuildingBlockController workController;
    private final BuildingBlockController.BlockScanner workScanner;

    public MerchantJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc, 2.0);
        this.workScanner = (level, building) -> findCraftingTable(level, building.bb);
    }

    @Override
    public String getJobId() { return "merchant"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        MerchantConfigDataHandler.Config cfg = MerchantConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)  current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER) enterSleep();

        if (current != State.SLEEPING && current != State.EATING && town.isMealTimeFor(level.getGameTime(), timing.eatStartOffset, 0)) {
            enterEating(town);
        }

        npc.setSuppressLookAtPlayer(current == State.AT_STAND);

        switch (current) {
            case EATING -> {
                if (!town.isMealTimeFor(level.getGameTime(), 0, timing.eatEndOffset)) exitEating();
                else { tryStartEatingAnimation(town); if (npc.isEating()) emitEatParticles(); }
            }
            case IDLE -> {
                BuildingBlockController.Result r = workController.tick(level, town, cfg, workScanner);
                if (r == BuildingBlockController.Result.PERFORMING) {
                    workController.reset();
                    current = State.AT_STAND;
                } else if (r == BuildingBlockController.Result.NOT_FOUND) {
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                }
            }
            case AT_STAND -> {}
            case SLEEPING -> tickSleeping(level, town, cfg);
            case ACTIVITY -> {
                SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
            }
        }
    }

    @Override
    public InteractionResult onPlayerInteract(Player player) {
        if (current == State.SLEEPING) return InteractionResult.PASS;
        if (!(npc.level() instanceof ServerLevel serverLevel)) return InteractionResult.PASS;

        Town town = findTown(serverLevel, npc);
        if (town == null) return InteractionResult.PASS;

        Runnable markDirty = () -> LevelTowns.get(serverLevel).markDirty();
        // null filter = no restriction, merchant trades all priced items in village stock
        MerchantOffers offers = buildMarketOffers(town, null);
        // Only the merchant sells commerce contracts
        List<ContractEntry> snapshot = snapshotProduction(town);
        if (!snapshot.isEmpty()) {
            ItemStack contractStack = new ItemStack(ItemRegistry.COMMERCE_CONTRACT);
            CommerceContractItem.writeEntries(contractStack, snapshot);
            offers.add(new MerchantOffer(
                new ItemStack(Items.EMERALD, CONTRACT_TRADE_PRICE),
                contractStack,
                Integer.MAX_VALUE,
                0,
                1.0f
            ));
        }
        SimpleTownMerchant merchant = new SimpleTownMerchant(offers, town, markDirty);
        merchant.setTradingPlayer(player);
        merchant.openTradingScreen(player,
            Component.translatable("entity.onceuponatown.merchant"), 1);
        return InteractionResult.SUCCESS;
    }

    private void enterEating(Town town) {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.freeHands();
        workController.reset();
        navigateToMealSpot(town);
        current = State.EATING;
    }

    private void exitEating() {
        mealNavigating = false;
        npc.setEating(false);
        npc.freeHands();
        current = State.IDLE;
    }

    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        workController.reset();
        sleepController.reset();
        current = State.SLEEPING;
    }

    private void tickSleeping(ServerLevel level, Town town, MerchantConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    private static BlockPos findCraftingTable(ServerLevel level, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return pos;
                }
            }
        }
        return null;
    }
}
