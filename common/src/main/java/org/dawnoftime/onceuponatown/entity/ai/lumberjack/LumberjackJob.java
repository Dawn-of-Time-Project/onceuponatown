package org.dawnoftime.onceuponatown.entity.ai.lumberjack;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.LumberjackConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.tick.LumberjackJobController;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class LumberjackJob extends AbstractNpcJob {

    private static final int SCAN_Y_EXTENSION = 16;
    private static final Set<Block> SAPLING_BLOCKS = Set.of(
        Blocks.OAK_SAPLING,    Blocks.SPRUCE_SAPLING, Blocks.BIRCH_SAPLING,
        Blocks.JUNGLE_SAPLING, Blocks.ACACIA_SAPLING, Blocks.DARK_OAK_SAPLING
    );

    private enum WorkState { CHOPPING, PLANTING }

    private WorkState workState = WorkState.CHOPPING;
    private PlacedBuilding assignedBuilding = null;
    private List<BlockPos> chopList = new ArrayList<>();
    private BlockPos currentChopTarget = null;
    private BlockPos currentPlantTarget = null;
    private int actionTickCounter = 0;
    private GoToPosition blockNav = null;
    private Map<BlockPos, BlockState> saplingMap = null;

    public LumberjackJob(Npc npc) {
        super(npc);
    }

    @Override
    public String getJobId() { return "lumberjack"; }

    @Override
    public java.util.List<String> getProductionBuildings() {
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        return cfg == null ? java.util.List.of() : cfg.productionBuildings;
    }

    @Override
    protected void dispatchFromController(ServerLevel level, Town town) {
        LumberjackJobController.dispatchForNpc(this, town, level);
    }

    @Override
    public long getAssignedBuildingId() {
        return assignedBuilding != null ? assignedBuilding.worldPos.asLong() : -1L;
    }

    @Override
    protected void onControllerAssignMain(PlacedBuilding building, ServerLevel level, Town town) {
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        assignedBuilding = building;
        workState = WorkState.CHOPPING;
        npc.holdInMainHand(new ItemStack(Items.WOODEN_AXE));
        chopList.clear();
        populateChopList(level, building);
        if (chopList.isEmpty()) {
            onMainWorkComplete(level, town);
            return;
        }
        currentChopTarget = chopList.get(0);
        double reach = cfg != null ? cfg.workReach : 6.0;
        double speed = cfg != null ? cfg.walkSpeed : 0.6;
        blockNav = new GoToPosition(npc, chopList.get(chopList.size() - 1), speed, reach);
    }

    @Override
    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        activityController.startWith(town, npc, def, building);
    }

    @Override
    protected void onResetWork() {
        assignedBuilding = null;
        currentChopTarget = null;
        currentPlantTarget = null;
        blockNav = null;
        actionTickCounter = 0;
        saplingMap = null;
        chopList.clear();
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        tickSharedPreamble(level, town, cfg);

        switch (baseState) {
            case SLEEPING  -> tickSleepingBase(level, town, cfg);
            case EATING    -> tickEatingBase(level, town);
            case SECONDARY -> tickSecondaryBase(level, town, cfg);
            case WANDER    -> maybeWander();
            case MAIN      -> tickMain(level, town);
        }
    }

    private void tickMain(ServerLevel level, Town town) {
        switch (workState) {
            case CHOPPING -> tickChopping(level, town);
            case PLANTING -> tickPlanting(level, town);
        }
    }

    private void tickChopping(ServerLevel level, Town town) {
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        if (cfg == null) return;

        if (blockNav != null) {
            if (blockNav.tick()) blockNav = null;
            return;
        }

        if (currentChopTarget != null) {
            npc.getLookControl().setLookAt(currentChopTarget.getX() + 0.5, currentChopTarget.getY() + 0.5, currentChopTarget.getZ() + 0.5, 10f, 10f);
        }

        if (actionTickCounter > 0) {
            actionTickCounter--;
            return;
        }

        while (!chopList.isEmpty() && !isChoppable(level.getBlockState(chopList.get(0)))) {
            chopList.remove(0);
        }

        if (!chopList.isEmpty()) {
            BlockPos target = chopList.remove(0);
            currentChopTarget = target;
            npc.getLookControl().setLookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 10f, 10f);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
            level.removeBlock(target, false);
            float speed = cfg.chopSpeedMin + npc.getRandom().nextFloat() * (cfg.chopSpeedMax - cfg.chopSpeedMin);
            actionTickCounter = speedToTicks(speed);
            return;
        }

        npc.freeHands();
        currentChopTarget = null;
        workState = WorkState.PLANTING;
    }

    private void tickPlanting(ServerLevel level, Town town) {
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        if (cfg == null) return;

        if (currentPlantTarget != null) {
            npc.getLookControl().setLookAt(currentPlantTarget.getX() + 0.5, currentPlantTarget.getY() + 0.5, currentPlantTarget.getZ() + 0.5, 10f, 10f);
        }

        if (saplingMap == null) {
            saplingMap = new HashMap<>();
            if (assignedBuilding != null) {
                Optional<BuildingDef> defOpt = BuildingDataHandler.get(assignedBuilding.defId);
                if (defOpt.isPresent()) {
                    Optional<StructureTemplate> templateOpt = level.getStructureManager().get(defOpt.get().nbt);
                    if (templateOpt.isPresent()) {
                        List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), assignedBuilding.rotation).blocks();
                        for (SchematicBlock b : blocks) {
                            if (SAPLING_BLOCKS.contains(b.state().getBlock())) {
                                saplingMap.put(assignedBuilding.worldPos.offset(b.localPos()), b.state());
                            }
                        }
                    }
                }
            }
            if (!saplingMap.isEmpty()) {
                npc.holdInMainHand(new ItemStack(saplingMap.values().iterator().next().getBlock().asItem()));
            }
        }

        if (blockNav != null) {
            if (blockNav.tick()) blockNav = null;
            return;
        }

        if (actionTickCounter > 0) {
            actionTickCounter--;
            return;
        }

        double workReachSq = cfg.workReach * cfg.workReach;

        BlockPos inReach = null;
        for (BlockPos pos : saplingMap.keySet()) {
            if (npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= workReachSq) {
                inReach = pos;
                break;
            }
        }

        if (inReach != null) {
            currentPlantTarget = inReach;
            BlockState state = saplingMap.remove(inReach);
            level.setBlock(inReach, state, Block.UPDATE_ALL);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
            float speed = cfg.plantSpeedMin + npc.getRandom().nextFloat() * (cfg.plantSpeedMax - cfg.plantSpeedMin);
            actionTickCounter = speedToTicks(speed);
            return;
        }

        if (!saplingMap.isEmpty()) {
            BlockPos nearest = saplingMap.keySet().stream()
                .min(Comparator.comparingDouble(p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(saplingMap.keySet().iterator().next());
            currentPlantTarget = nearest;
            blockNav = new GoToPosition(npc, nearest, cfg.walkSpeed, cfg.workReach);
            return;
        }

        npc.freeHands();
        currentPlantTarget = null;
        onMainWorkComplete(level, town);
    }

    private void onMainWorkComplete(ServerLevel level, Town town) {
        assignedBuilding = null;
        chopList.clear();
        saplingMap = null;
        currentChopTarget = null;
        currentPlantTarget = null;
        blockNav = null;
        LumberjackJobController.dispatchForNpc(this, town, level);
    }

    public static boolean hasMatureLogs(ServerLevel level, PlacedBuilding building) {
        BoundingBox bb = building.bb;
        if (bb == null) return false;
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            if (level.getBlockState(pos).is(BlockTags.LOGS)) return true;
        }
        return false;
    }

    static boolean hasLogInBounds(ServerLevel level, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                for (int y = bb.minY(); y <= bb.maxY() + SCAN_Y_EXTENSION; y++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(BlockTags.LOGS)) return true;
                }
            }
        }
        return false;
    }

    private void populateChopList(ServerLevel level, PlacedBuilding building) {
        if (building.bb == null) return;
        List<BlockPos> logs = scanLogs(level, building.bb);
        chopList.addAll(logs);
    }

    private static boolean isChoppable(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(Blocks.BEE_NEST);
    }

    private static List<BlockPos> scanLogs(ServerLevel level, BoundingBox bb) {
        List<BlockPos> logs = new ArrayList<>();
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                for (int y = bb.minY(); y <= bb.maxY() + SCAN_Y_EXTENSION; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (isChoppable(level.getBlockState(pos))) {
                        logs.add(pos);
                    }
                }
            }
        }
        logs.sort((a, b) -> b.getY() - a.getY());
        return logs;
    }
}
