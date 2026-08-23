package org.dawnoftime.onceuponatown.entity.ai.beekeeper;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.datapack.BeekeeperConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.NpcSleepController;
import org.dawnoftime.onceuponatown.entity.ai.shared.SecondaryActivityController;
import org.dawnoftime.onceuponatown.town.Town;

public class BeekeeperJob extends AbstractNpcJob {

    private static final int MAX_HONEY_LEVEL = 5;

    private enum State { IDLE, HARVESTING, SLEEPING, ACTIVITY }

    private State current = State.IDLE;
    private int harvestTickCounter = 0;

    private final BuildingBlockController workController;
    private final BuildingBlockController.BlockScanner workScanner;
    private final SecondaryActivityController activityController = new SecondaryActivityController();

    public BeekeeperJob(Npc npc) {
        super(npc);
        // arrivalRadius 1.0: NPC walks to the block directly adjacent to the hive.
        this.workController = new BuildingBlockController(npc, 1.0);
        // Scanner returns the first ripe hive found in the building; BBC navigates there.
        this.workScanner = (level, building) -> {
            BlockPos hive = scanFirstHive(level, building.bb);
            if (hive == null) return null;
            npc.holdInMainHand(new ItemStack(Items.GLASS_BOTTLE));
            return hive;
        };
    }

    @Override
    public String getJobId() { return "beekeeper"; }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        BeekeeperConfigDataHandler.Config cfg = BeekeeperConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;

        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)  current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER) enterSleep();

        npc.setSuppressLookAtPlayer(current != State.IDLE && current != State.SLEEPING);

        switch (current) {
            case IDLE -> {
                if (!hasAvailableHives(level, town, cfg)) {
                    workController.reset();
                    if (activityController.tryStart(town, npc, cfg.secondaryActivities)) {
                        current = State.ACTIVITY;
                    } else {
                        maybeWander();
                    }
                    break;
                }
                BuildingBlockController.Result r = workController.tick(level, town, cfg, workScanner);
                if (r == BuildingBlockController.Result.PERFORMING) {
                    harvestTickCounter = 0;
                    current = State.HARVESTING;
                }
                if (r == BuildingBlockController.Result.NOT_FOUND) maybeWander();
            }
            case HARVESTING -> tickHarvesting(level, town, cfg);
            case SLEEPING   -> tickSleeping(level, town, cfg);
            case ACTIVITY -> {
                if (hasAvailableHives(level, town, cfg)) {
                    activityController.cancel(npc);
                    current = State.IDLE;
                    break;
                }
                SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.walkSpeed);
                if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
            }
        }
    }

    private void tickHarvesting(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        BlockPos hivePos = workController.getTargetBlockPos();
        if (hivePos != null) {
            npc.getLookControl().setLookAt(hivePos.getX() + 0.5, hivePos.getY() + 0.5, hivePos.getZ() + 0.5, 10f, 10f);
        }
        harvestTickCounter++;
        if (harvestTickCounter < cfg.harvestDelayTicks) return;
        harvestTickCounter = 0;

        if (hivePos != null) {
            BlockState state = level.getBlockState(hivePos);
            if (state.getBlock() instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
                level.setBlock(hivePos, state.setValue(BeehiveBlock.HONEY_LEVEL, 0), Block.UPDATE_ALL);
            }
        }
        npc.freeHands();
        workController.advanceCursor(town, cfg);
        current = State.IDLE;
    }

    private boolean hasAvailableHives(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.beeBuildings.contains(b.defId))
            .anyMatch(b -> b.bb != null && scanFirstHive(level, b.bb) != null);
    }

    private void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        npc.freeHands();
        workController.reset();
        sleepController.reset();
        current = State.SLEEPING;
    }

    private void tickSleeping(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        if (!sleepController.tick(level, town, cfg)) {
            current = State.IDLE;
        }
    }

    // Returns the first ripe hive in the bounding box, or null if none.
    private static BlockPos scanFirstHive(ServerLevel level, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BeehiveBlock
                        && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
