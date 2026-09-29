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
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.tick.BeekeeperJobController;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

public class BeekeeperJob extends AbstractNpcJob {

    private static final int MAX_HONEY_LEVEL = 5;

    private enum WorkState { HARVESTING }

    private WorkState workState = WorkState.HARVESTING;
    private PlacedBuilding assignedBuilding = null;
    private int harvestTickCounter = 0;
    private int harvestTickTarget = 0;

    private final BuildingBlockController workController;

    public BeekeeperJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc);
    }

    @Override
    public String getJobId() { return "beekeeper"; }

    @Override
    public java.util.List<String> getProductionBuildings() {
        BeekeeperConfigDataHandler.Config cfg = BeekeeperConfigDataHandler.get();
        return cfg == null ? java.util.List.of() : cfg.productionBuildings;
    }

    @Override
    protected void dispatchFromController(ServerLevel level, Town town) {
        BeekeeperJobController.dispatchForNpc(this, town, level);
    }

    @Override
    public long getAssignedBuildingId() {
        return assignedBuilding != null ? assignedBuilding.worldPos.asLong() : -1L;
    }

    @Override
    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        activityController.startWith(town, npc, def, building);
    }

    @Override
    protected void onControllerAssignMain(PlacedBuilding building, ServerLevel level, Town town) {
        assignedBuilding = building;
        BlockPos firstHive = findFirstRipeHive(level, building);
        if (firstHive == null) {
            onMainWorkComplete(level, town);
            return;
        }
        BeekeeperConfigDataHandler.Config cfg = BeekeeperConfigDataHandler.get();
        if (cfg != null) {
            workController.startFor(building, level, cfg.walkSpeed, firstHive);
        } else {
            workController.startFor(building, firstHive);
        }
        workState = WorkState.HARVESTING;
    }

    @Override
    protected void onResetWork() {
        assignedBuilding = null;
        harvestTickCounter = 0;
        harvestTickTarget = 0;
        workController.reset();
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        BeekeeperConfigDataHandler.Config cfg = BeekeeperConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        tickSharedPreamble(level, town, cfg);

        switch (baseState) {
            case SLEEPING  -> tickSleepingBase(level, town, cfg);
            case EATING    -> tickEatingBase(level, town);
            case SECONDARY -> tickSecondaryBase(level, town, cfg);
            case WANDER    -> maybeWander();
            case MAIN      -> tickMain(level, town, cfg);
        }
    }

    private void tickMain(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        BuildingBlockController.Result result = workController.tick(level, town, cfg, (lvl, b) -> null);
        switch (result) {
            case SEARCHING  -> {}
            case PERFORMING -> tickHarvesting(level, town, cfg);
            case NOT_FOUND  -> onMainWorkComplete(level, town);
        }
    }

    private void tickHarvesting(ServerLevel level, Town town, BeekeeperConfigDataHandler.Config cfg) {
        BlockPos hivePos = workController.getTargetBlockPos();
        if (hivePos != null) {
            npc.getLookControl().setLookAt(hivePos.getX() + 0.5, hivePos.getY() + 0.5, hivePos.getZ() + 0.5, 10f, 10f);
        }

        if (harvestTickTarget == 0) {
            npc.holdInMainHand(new ItemStack(Items.GLASS_BOTTLE));
            harvestTickTarget = computeHarvestTarget(cfg);
            return;
        }

        if (++harvestTickCounter < harvestTickTarget) return;
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

        workController.advanceCursor();
        BlockPos next = findFirstRipeHive(level, assignedBuilding);
        if (next != null) {
            workController.startFor(assignedBuilding, next);
            harvestTickTarget = 0;
        } else {
            onMainWorkComplete(level, town);
        }
    }

    private int computeHarvestTarget(BeekeeperConfigDataHandler.Config cfg) {
        float speed = cfg.harvestSpeedMin + npc.getRandom().nextFloat() * (cfg.harvestSpeedMax - cfg.harvestSpeedMin);
        return speedToTicks(speed);
    }

    private void onMainWorkComplete(ServerLevel level, Town town) {
        assignedBuilding = null;
        harvestTickCounter = 0;
        workController.reset();
        BeekeeperJobController.dispatchForNpc(this, town, level);
    }

    public static boolean hasRipeHives(ServerLevel level, PlacedBuilding building) {
        BoundingBox bb = building.bb;
        if (bb == null) return false;
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findFirstRipeHive(ServerLevel level, PlacedBuilding building) {
        if (building == null) return null;
        BoundingBox bb = building.bb;
        if (bb == null) return null;
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof BeehiveBlock && state.getValue(BeehiveBlock.HONEY_LEVEL) >= MAX_HONEY_LEVEL) {
                return pos.immutable();
            }
        }
        return null;
    }
}
