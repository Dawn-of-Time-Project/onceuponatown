package org.dawnoftime.onceuponatown.entity.ai.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.datapack.MinerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandingPositionFinder;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;

public class MinerJob extends AbstractNpcJob {

    private static final double MINING_REACH = 2.0;

    private enum State { IDLE, MINING, SLEEPING, ACTIVITY, EATING }

    private State current = State.IDLE;
    private int mineTickCounter = 0;

    private final BuildingBlockController workController;


    public MinerJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc, MINING_REACH);
    }

    @Override
    public String getJobId() { return "miner"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        MinerConfigDataHandler.Config cfg = MinerConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)   current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER)  enterSleep();

        if (current != State.SLEEPING && current != State.EATING && town.isMealTimeFor(level.getGameTime(), timing.eatStartOffset, 0)) {
            enterEating(town);
        }

        npc.setSuppressLookAtPlayer(current != State.IDLE && current != State.SLEEPING && current != State.EATING);

        switch (current) {
            case EATING -> {
                if (!town.isMealTimeFor(level.getGameTime(), 0, timing.eatEndOffset)) exitEating();
                else { tryStartEatingAnimation(town); if (npc.isEating()) emitEatParticles(); }
            }
            case IDLE -> {
                BuildingBlockController.BlockScanner scanner = (lvl, building) -> findNearestStone(lvl, building.bb, cfg);
                BuildingBlockController.Result r = workController.tick(level, town, cfg, scanner);
                if (r == BuildingBlockController.Result.PERFORMING && current != State.MINING) {
                    npc.holdInMainHand(new ItemStack(toolForTarget(level, cfg)));
                    mineTickCounter = 0;
                    current = State.MINING;
                } else if (r == BuildingBlockController.Result.NOT_FOUND) {
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                }
            }
            case MINING   -> tickMining(level, town, cfg);
            case SLEEPING -> tickSleeping(level, town, cfg);
            case ACTIVITY -> {
                SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
            }
        }
    }

    private void tickMining(ServerLevel level, Town town, MinerConfigDataHandler.Config cfg) {
        npc.getNavigation().stop();

        BlockPos targetPos = workController.getTargetBlockPos();

        // Look at the stone block every tick.
        if (targetPos != null) {
            npc.getLookControl().setLookAt(
                targetPos.getX() + 0.5,
                targetPos.getY() + 0.5,
                targetPos.getZ() + 0.5,
                10f, 10f
            );
        }

        // Swing pickaxe at the configured interval.
        if (mineTickCounter % cfg.mineDelayTicks == 0) {
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
        }
        mineTickCounter++;

        // After the session duration, move on to the next eligible building.
        if (mineTickCounter >= cfg.mineSessionTicks) {
            mineTickCounter = 0;
            workController.advanceCursor(town, cfg);
            current = State.IDLE;
        }
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

    // Interrupts whatever the NPC was doing and switches to the SLEEPING state.
    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        npc.freeHands();
        workController.reset();
        sleepController.reset();
        current = State.SLEEPING;
    }

    private void tickSleeping(ServerLevel level, Town town, MinerConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    private boolean isMineable(BlockState state, MinerConfigDataHandler.Config cfg) {
        String key = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return cfg.mineableBlocks.containsKey(key);
    }

    private Item toolForTarget(ServerLevel level, MinerConfigDataHandler.Config cfg) {
        BlockPos pos = workController.getTargetBlockPos();
        if (pos == null) return Items.WOODEN_PICKAXE;
        String key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
        Item tool = cfg.mineableBlocks.get(key);
        return tool != null ? tool : Items.WOODEN_PICKAXE;
    }

    // Finds a random reachable mineable block inside the given bounding box.
    // Collects all valid candidates, shuffles using the NPC's own RNG (per-entity seed),
    // so multiple miners in the same mine will pick different blocks each cycle.
    private BlockPos findNearestStone(ServerLevel level, BoundingBox bb, MinerConfigDataHandler.Config cfg) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (!isMineable(level.getBlockState(p), cfg)) continue;
                    if (StandingPositionFinder.find(level, p, MINING_REACH) == null) continue;
                    candidates.add(p);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(npc.getRandom().nextInt(candidates.size()));
    }
}
