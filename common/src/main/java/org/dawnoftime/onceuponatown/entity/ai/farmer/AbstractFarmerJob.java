package org.dawnoftime.onceuponatown.entity.ai.farmer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.FarmerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingBlockController;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.tick.FarmerJobController;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public abstract class AbstractFarmerJob extends AbstractNpcJob {

    enum WorkState { HARVESTING, REPLANTING }

    private WorkState workState = WorkState.HARVESTING;
    private PlacedBuilding assignedBuilding = null;
    private List<BlockPos> cropList = new ArrayList<>();
    private Map<BlockPos, Block> harvestedCrops = new HashMap<>();
    private List<BlockPos> emptyFarmlandList = null;
    private List<BlockPos> brokenFarmlandList = null;
    private BlockPos currentActionTarget = null;
    private int actionTickCounter = 0;
    private GoToPosition blockNav = null;

    private final BuildingBlockController workController;

    protected AbstractFarmerJob(Npc npc) {
        super(npc);
        this.workController = new BuildingBlockController(npc);
    }

    protected abstract FarmerConfigDataHandler.Config getConfig();

    protected abstract Block getCropBlock();

    protected abstract Item getSeedItem();

    @Override
    protected void dispatchFromController(ServerLevel level, Town town) {
        FarmerJobController.dispatchForNpc(this, town, level);
    }

    @Override
    public long getAssignedBuildingId() {
        return assignedBuilding != null ? assignedBuilding.worldPos.asLong() : -1L;
    }

    @Override
    protected void onControllerAssignMain(PlacedBuilding building, ServerLevel level, Town town) {
        assignedBuilding = building;
        workState = WorkState.HARVESTING;
        cropList = new ArrayList<>();
        harvestedCrops = new HashMap<>();
        emptyFarmlandList = null;
        brokenFarmlandList = null;
        currentActionTarget = null;
        blockNav = null;
        actionTickCounter = 0;
        workController.startFor(building, null);
    }

    @Override
    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        activityController.startWith(town, npc, def, building);
    }

    @Override
    protected void onResetWork() {
        assignedBuilding = null;
        workController.reset();
        harvestedCrops.clear();
        emptyFarmlandList = null;
        brokenFarmlandList = null;
        currentActionTarget = null;
        blockNav = null;
        actionTickCounter = 0;
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        FarmerConfigDataHandler.Config cfg = getConfig();
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

    private void tickMain(ServerLevel level, Town town, FarmerConfigDataHandler.Config cfg) {
        switch (workState) {
            case HARVESTING -> tickHarvesting(level, town, cfg);
            case REPLANTING -> tickReplanting(level, town, cfg);
        }
    }

    private void tickHarvesting(ServerLevel level, Town town, FarmerConfigDataHandler.Config cfg) {
        // Lazy crop scan on first tick of HARVESTING
        if (cropList.isEmpty() && assignedBuilding != null && assignedBuilding.bb != null) {
            cropList = new ArrayList<>(scanReadyCrops(level, assignedBuilding.bb));
            cropList.sort(Comparator.comparingDouble(
                p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)
            ));
            harvestedCrops = new HashMap<>();
            blockNav = null;
            actionTickCounter = 0;
            if (!cropList.isEmpty()) npc.holdInMainHand(new ItemStack(Items.WOODEN_HOE));
        }

        if (currentActionTarget != null) {
            npc.getLookControl().setLookAt(currentActionTarget.getX() + 0.5, currentActionTarget.getY() + 0.5, currentActionTarget.getZ() + 0.5, 10f, 10f);
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
        int inReachIndex = -1;
        for (int i = 0; i < cropList.size(); i++) {
            BlockPos pos = cropList.get(i);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state))) {
                cropList.remove(i);
                i--;
                continue;
            }
            if (npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= workReachSq) {
                inReach = pos;
                inReachIndex = i;
                break;
            }
        }

        if (inReach != null) {
            currentActionTarget = inReach;
            cropList.remove(inReachIndex);
            BlockState state = level.getBlockState(inReach);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                harvestedCrops.put(inReach, crop);
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
                level.removeBlock(inReach, false);
            }
            float speed = cfg.harvestSpeedMin + npc.getRandom().nextFloat() * (cfg.harvestSpeedMax - cfg.harvestSpeedMin);
            actionTickCounter = speedToTicks(speed);
            return;
        }

        if (!cropList.isEmpty()) {
            BlockPos nearest = cropList.stream()
                .min(Comparator.comparingDouble(p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(cropList.get(0));
            currentActionTarget = nearest;
            blockNav = new GoToPosition(npc, nearest, cfg.walkSpeed, cfg.workReach);
            return;
        }

        npc.holdInMainHand(new ItemStack(getSeedItem()));
        currentActionTarget = null;
        workState = WorkState.REPLANTING;
    }

    private void tickReplanting(ServerLevel level, Town town, FarmerConfigDataHandler.Config cfg) {
        if (currentActionTarget != null) {
            npc.getLookControl().setLookAt(currentActionTarget.getX() + 0.5, currentActionTarget.getY() + 0.5, currentActionTarget.getZ() + 0.5, 10f, 10f);
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

        if (!harvestedCrops.isEmpty()) {
            BlockPos inReach = null;
            for (BlockPos pos : harvestedCrops.keySet()) {
                if (npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= workReachSq) {
                    inReach = pos;
                    break;
                }
            }

            if (inReach != null) {
                currentActionTarget = inReach;
                Block cropBlock = harvestedCrops.remove(inReach);
                if (level.getBlockState(inReach).isAir()
                        && level.getBlockState(inReach.below()).is(Blocks.FARMLAND)) {
                    level.setBlock(inReach, cropBlock.defaultBlockState(), Block.UPDATE_ALL);
                }
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
                float speed = cfg.plantSpeedMin + npc.getRandom().nextFloat() * (cfg.plantSpeedMax - cfg.plantSpeedMin);
                actionTickCounter = speedToTicks(speed);
                return;
            }

            BlockPos nearest = harvestedCrops.keySet().stream()
                .min(Comparator.comparingDouble(p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                .orElse(harvestedCrops.keySet().iterator().next());
            currentActionTarget = nearest;
            blockNav = new GoToPosition(npc, nearest, cfg.walkSpeed, cfg.workReach);
            return;
        }

        if (emptyFarmlandList == null) {
            if (assignedBuilding != null && assignedBuilding.bb != null) {
                emptyFarmlandList = scanEmptyFarmlands(level, assignedBuilding.bb);
                emptyFarmlandList.sort(Comparator.comparingDouble(
                    p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)
                ));
            } else {
                emptyFarmlandList = new ArrayList<>();
            }
        }

        if (!emptyFarmlandList.isEmpty()) {
            BlockPos inReach = null;
            for (int i = 0; i < emptyFarmlandList.size(); i++) {
                BlockPos pos = emptyFarmlandList.get(i);
                if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
                    emptyFarmlandList.remove(i);
                    i--;
                    continue;
                }
                if (npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= workReachSq) {
                    inReach = pos;
                    break;
                }
            }

            if (inReach != null) {
                currentActionTarget = inReach;
                emptyFarmlandList.remove(inReach);
                if (level.getBlockState(inReach).isAir()
                        && level.getBlockState(inReach.below()).is(Blocks.FARMLAND)) {
                    level.setBlock(inReach, getCropBlock().defaultBlockState(), Block.UPDATE_ALL);
                }
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
                float speed = cfg.plantSpeedMin + npc.getRandom().nextFloat() * (cfg.plantSpeedMax - cfg.plantSpeedMin);
                actionTickCounter = speedToTicks(speed);
                return;
            }

            if (!emptyFarmlandList.isEmpty()) {
                BlockPos nearest = emptyFarmlandList.stream()
                    .min(Comparator.comparingDouble(p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                    .orElse(emptyFarmlandList.get(0));
                currentActionTarget = nearest;
                blockNav = new GoToPosition(npc, nearest, cfg.walkSpeed, cfg.workReach);
                return;
            }
        }

        if (brokenFarmlandList == null) {
            if (assignedBuilding != null) {
                brokenFarmlandList = scanBrokenFarmlandsFromTemplate(level, assignedBuilding, 5);
                brokenFarmlandList.sort(Comparator.comparingDouble(
                    p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)
                ));
            } else {
                brokenFarmlandList = new ArrayList<>();
            }
            if (!brokenFarmlandList.isEmpty()) {
                npc.holdInMainHand(new ItemStack(Items.WOODEN_HOE));
            }
        }

        if (!brokenFarmlandList.isEmpty()) {
            BlockPos inReach = null;
            for (int i = 0; i < brokenFarmlandList.size(); i++) {
                BlockPos pos = brokenFarmlandList.get(i);
                BlockState ws = level.getBlockState(pos);
                if (!ws.is(Blocks.DIRT) && !ws.is(Blocks.GRASS_BLOCK)) {
                    brokenFarmlandList.remove(i);
                    i--;
                    continue;
                }
                if (npc.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= workReachSq) {
                    inReach = pos;
                    break;
                }
            }

            if (inReach != null) {
                currentActionTarget = inReach;
                brokenFarmlandList.remove(inReach);
                BlockState ws = level.getBlockState(inReach);
                if (ws.is(Blocks.DIRT) || ws.is(Blocks.GRASS_BLOCK)) {
                    level.setBlock(inReach, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                }
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
                float speed = cfg.plantSpeedMin + npc.getRandom().nextFloat() * (cfg.plantSpeedMax - cfg.plantSpeedMin);
                actionTickCounter = speedToTicks(speed);
                return;
            }

            if (!brokenFarmlandList.isEmpty()) {
                BlockPos nearest = brokenFarmlandList.stream()
                    .min(Comparator.comparingDouble(p -> npc.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)))
                    .orElse(brokenFarmlandList.get(0));
                currentActionTarget = nearest;
                blockNav = new GoToPosition(npc, nearest, cfg.walkSpeed, cfg.workReach);
                return;
            }
        }

        onMainWorkComplete(level, town);
    }

    private void onMainWorkComplete(ServerLevel level, Town town) {
        npc.freeHands();
        assignedBuilding = null;
        workController.reset();
        currentActionTarget = null;
        emptyFarmlandList = null;
        brokenFarmlandList = null;
        FarmerJobController.dispatchForNpc(this, town, level);
    }

    // Checks whether a building has any harvestable crops or empty farmland plots.
    public static boolean hasPendingFarmWork(ServerLevel level, PlacedBuilding building) {
        if (building.bb == null) return false;
        BoundingBox bb = building.bb;
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if ((block == Blocks.WHEAT || block == Blocks.POTATOES)
                    && state.getValue(CropBlock.AGE) >= 7) return true;
            if (state.is(Blocks.FARMLAND) && level.getBlockState(pos.above()).isAir()) return true;
        }
        return false;
    }

    boolean hasReadyCrops(ServerLevel level, Town town, FarmerConfigDataHandler.Config cfg) {
        for (PlacedBuilding building : town.getBuildings()) {
            if (!cfg.farmBuildings.contains(building.defId)) continue;
            if (building.bb == null) continue;
            if (hasReadyCropInBounds(level, building.bb)) return true;
        }
        return false;
    }

    boolean hasEmptyFarmlands(ServerLevel level, Town town, FarmerConfigDataHandler.Config cfg) {
        for (PlacedBuilding building : town.getBuildings()) {
            if (!cfg.farmBuildings.contains(building.defId)) continue;
            if (building.bb == null) continue;
            if (hasEmptyFarmlandInBounds(level, building.bb)) return true;
        }
        return false;
    }

    private static boolean hasReadyCropInBounds(ServerLevel level, BoundingBox bb) {
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) return true;
        }
        return false;
    }

    private static boolean hasEmptyFarmlandInBounds(ServerLevel level, BoundingBox bb) {
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> scanEmptyFarmlands(ServerLevel level, BoundingBox bb) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).is(Blocks.FARMLAND)) {
                result.add(pos.immutable());
            }
        }
        return result;
    }

    private static List<BlockPos> scanBrokenFarmlandsFromTemplate(
            ServerLevel level, PlacedBuilding building, int maxResults) {
        List<BlockPos> result = new ArrayList<>();
        Optional<BuildingDef> defOpt = BuildingDataHandler.get(building.defId);
        if (defOpt.isEmpty()) return result;
        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(defOpt.get().nbt);
        if (templateOpt.isEmpty()) return result;
        List<SchematicBlock> blocks = SchematicReader.readSortedBlocks(templateOpt.get(), building.rotation).blocks();
        for (SchematicBlock b : blocks) {
            if (!b.state().is(Blocks.FARMLAND)) continue;
            BlockPos worldPos = building.worldPos.offset(b.localPos());
            BlockState ws = level.getBlockState(worldPos);
            if (ws.is(Blocks.DIRT) || ws.is(Blocks.GRASS_BLOCK)) {
                result.add(worldPos);
                if (result.size() >= maxResults) break;
            }
        }
        return result;
    }

    private static List<BlockPos> scanReadyCrops(ServerLevel level, BoundingBox bb) {
        List<BlockPos> crops = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state)) {
                crops.add(pos.immutable());
            }
        }
        return crops;
    }
}
