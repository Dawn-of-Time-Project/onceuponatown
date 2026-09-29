package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.dawnoftime.onceuponatown.building.schematic.BlockStep;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBounds;
import org.dawnoftime.onceuponatown.building.schematic.EntityStep;
import org.dawnoftime.onceuponatown.building.schematic.PlacementStep;
import org.dawnoftime.onceuponatown.datapack.BuilderConfigDataHandler;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.town.ActiveBuildState;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

// Unified NPC construction executor. Pluggable behavior is provided by a BuilderAction:
//   - NewBuildAction: places a full NBT template block-by-block
//   - UpgradeAction:  applies a visual diff between two NBT levels
// Future modes (e.g. demolition) follow the same interface.
//
// Phase flow:
//   MOVING   -- NPC walks to action.getTargetPos() (connection point or building origin)
//   BUILDING -- waits for any reading animation, then executes PlacementSteps one-by-one
//   DONE     -- task complete (success or failure)
//
// The PlacementStep list from prepareSteps() is ordered:
//   normal blocks including doors (Y-sorted snake) -> deferred blocks (water, lily pads) -> entities
// All steps go through the same burst rhythm, navigation, and arm-swing logic.
//
// For instant (terrain-matched) builds the BUILDING phase is skipped:
//   MOVING -> executeInstant() -> DONE
public class BuildGoal implements BuildTask {
    private enum Phase { MOVING, BUILDING, DONE }

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildGoal.class);

    private static float blockSpeedMin()      { return BuilderConfigDataHandler.get().blockSpeedMin; }
    private static float blockSpeedMax()      { return BuilderConfigDataHandler.get().blockSpeedMax; }
    private static double workReach()         { return BuilderConfigDataHandler.get().blockReachDistance; }
    private static float planReadChance()     { return BuilderConfigDataHandler.get().planReadChance; }
    private static float planReadPauseMin()   { return BuilderConfigDataHandler.get().planReadPauseMin; }
    private static float planReadPauseMax()   { return BuilderConfigDataHandler.get().planReadPauseMax; }
    private final Npc npc;
    private final BuilderAction action;
    private Phase phase = Phase.MOVING;
    private final GoToPosition goTo;

    // BUILDING state
    private List<PlacementStep> steps = null;
    private int buildProgress = 0;
    private int buildSpeedCooldown = 0;
    private GoToPosition buildGoTo = null;
    private BlockPos currentBuildTarget = null;

    // Stall counters for freeze diagnostics
    private int movingStallTicks = 0;
    private int buildStallTicks = 0;


    public BuildGoal(Npc npc, BuilderAction action) {
        this.npc = npc;
        this.action = action;
        this.goTo = new GoToPosition(npc, action.getTargetPos(), BuilderConfigDataHandler.get().walkSpeed);
    }

    @Override
    public boolean isFailed() { return action.isFailed(); }

    @Override
    public BlockPos getFinalPlacementPos() { return action.getOrigin(); }

    @Override
    public boolean tick() {
        return switch (phase) {
            case MOVING -> tickMoving();
            case BUILDING -> tickBuilding();
            case DONE -> true;
        };
    }

    private boolean tickMoving() {
        if (!goTo.tick()) {
            movingStallTicks++;
            if (movingStallTicks == 100 || movingStallTicks % 400 == 0) {
                LOGGER.warn("[OUAT-FREEZE] Builder {} stuck navigating to build site for {} ticks (target={}, action={})",
                    npc.getUUID(), movingStallTicks, action.getTargetPos(), action.getClass().getSimpleName());
            }
            return false;
        }
        movingStallTicks = 0;

        action.onArrived(npc);

        if (action.isInstant()) {
            if (!(npc.level() instanceof ServerLevel sl)) return false;
            if (action.executeInstant(sl, npc)) {
                action.onComplete(sl, npc);
                phase = Phase.DONE;
                return true;
            }
            // Placement failed; retry next tick.
            LOGGER.error("[OUAT-BUILD] Instant placement failed -- retrying next tick, origin={}",
                action.getOrigin());
            return false;
        }

        phase = Phase.BUILDING;
        return false;
    }

    private boolean tickBuilding() {
        if (!(npc.level() instanceof ServerLevel sl)) return false;

        // Wait for any pre-build animation (e.g. reading the plan) to finish.
        if (npc.isReading()) return false;

        // Load step list on first BUILDING tick.
        if (steps == null) {
            steps = action.prepareSteps(sl, npc);
        }

        // Empty list or action marked itself failed.
        if (steps.isEmpty() || action.isFailed()) {
            if (!action.isFailed()) action.onComplete(sl, npc);
            phase = Phase.DONE;
            return true;
        }

        if (buildProgress >= steps.size()) {
            action.onComplete(sl, npc);
            phase = Phase.DONE;
            return true;
        }

        // Navigate toward the current step and keep the NPC's head tracking it.
        BlockPos nextWorldPos = steps.get(buildProgress).targetPos();
        npc.getLookControl().setLookAt(nextWorldPos.getX() + 0.5, nextWorldPos.getY() + 0.5, nextWorldPos.getZ() + 0.5);

        if (!nextWorldPos.equals(currentBuildTarget)) {
            currentBuildTarget = nextWorldPos;
            buildGoTo = new GoToPosition(npc, nextWorldPos, BuilderConfigDataHandler.get().walkSpeed, workReach());
        }

        BuilderDebug.tick(sl, npc, currentBuildTarget);

        if (!buildGoTo.tick()) {
            buildStallTicks++;
            if (buildStallTicks == 100 || buildStallTicks % 400 == 0) {
                LOGGER.warn("[OUAT-FREEZE] Builder {} stuck navigating to block {} for {} ticks (progress={}/{})",
                    npc.getUUID(), currentBuildTarget, buildStallTicks,
                    buildProgress, steps != null ? steps.size() : -1);
            }
            return false;
        }
        buildStallTicks = 0;

        if (buildSpeedCooldown > 0) { buildSpeedCooldown--; return false; }

        // Execute the current step: block placement or entity spawn.
        PlacementStep step = steps.get(buildProgress);

        if (step instanceof BlockStep bs) {
            if (bs.state().isAir()) {
                // Removing a block: hold the tool suited for the block being destroyed.
                BlockState existing = sl.getBlockState(bs.worldPos());
                ItemStack tool = BlockToolSelector.getDestructionTool(existing);
                if (!tool.isEmpty()) npc.holdInMainHand(tool);
                else npc.freeHands();
            } else {
                // Placing a block: hold a water bucket for water, otherwise the block item.
                ItemStack handItem = BlockToolSelector.getPlacementItem(bs.state());
                if (!handItem.isEmpty()) npc.holdInMainHand(handItem);
                else npc.freeHands();
            }

            npc.getLookControl().setLookAt(bs.worldPos().getX() + 0.5, bs.worldPos().getY() + 0.5, bs.worldPos().getZ() + 0.5);
            sl.setBlock(bs.worldPos(), bs.state(), Block.UPDATE_ALL);

            SoundType sound = bs.state().getSoundType();
            sl.playSound(null, bs.worldPos(), sound.getPlaceSound(), SoundSource.BLOCKS,
                sound.getVolume(), sound.getPitch());

            if (bs.nbt() != null) {
                BlockEntity be = sl.getBlockEntity(bs.worldPos());
                if (be != null) be.load(bs.nbt().copy());
            }

            // Doors are 2-block-tall structures. Place the upper half immediately when placing the lower
            // half to keep the door complete and avoid blocking the NPC's pathfinder.
            if (bs.state().getBlock() instanceof DoorBlock &&
                    bs.state().getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                BlockPos upperPos = bs.worldPos().above();
                for (int k = buildProgress + 1; k < steps.size(); k++) {
                    if (steps.get(k) instanceof BlockStep upper && upper.worldPos().equals(upperPos)) {
                        sl.setBlock(upper.worldPos(), upper.state(), Block.UPDATE_ALL);
                        steps.remove(k);
                        break;
                    }
                }
            }

            // Beds are 2-block-wide structures. Both halves must be placed atomically to prevent
            // Minecraft's updateShape from popping the lone half off as an item drop.
            // FOOT first: place HEAD immediately after.
            // HEAD first: place FOOT first so the head has a valid partner when set.
            if (bs.state().getBlock() instanceof BedBlock) {
                if (bs.state().getValue(BedBlock.PART) == BedPart.FOOT) {
                    BlockPos headPos = bs.worldPos().relative(bs.state().getValue(BedBlock.FACING));
                    for (int k = buildProgress + 1; k < steps.size(); k++) {
                        if (steps.get(k) instanceof BlockStep head && head.worldPos().equals(headPos)) {
                            sl.setBlock(head.worldPos(), head.state(), Block.UPDATE_ALL);
                            steps.remove(k);
                            break;
                        }
                    }
                } else {
                    BlockPos footPos = bs.worldPos().relative(bs.state().getValue(BedBlock.FACING).getOpposite());
                    for (int k = buildProgress + 1; k < steps.size(); k++) {
                        if (steps.get(k) instanceof BlockStep foot && foot.worldPos().equals(footPos)) {
                            sl.setBlock(foot.worldPos(), foot.state(), Block.UPDATE_ALL);
                            steps.remove(k);
                            break;
                        }
                    }
                }
            }

        } else if (step instanceof EntityStep es) {
            // Full NBT preservation: load() restores name, stats, and all type-specific data.
            // New UUID on every spawn prevents Minecraft from treating template entities as duplicates.
            EntityType.by(es.entityNbt()).ifPresent(type -> {
                Entity entity = type.create(sl);
                if (entity != null) {
                    entity.load(es.entityNbt().copy());
                    entity.setUUID(UUID.randomUUID());
                    entity.moveTo(es.worldPos().x, es.worldPos().y, es.worldPos().z,
                                  entity.getYRot(), entity.getXRot());
                    sl.addFreshEntity(entity);
                }
            });
        }

        buildProgress++;
        npc.notifyBlockPlaced();
        npc.swing(InteractionHand.MAIN_HAND);

        float speed = blockSpeedMin() + npc.getRandom().nextFloat() * (blockSpeedMax() - blockSpeedMin());
        buildSpeedCooldown = Math.max(1, (int)(speed * 20));
        if (npc.getRandom().nextFloat() < planReadChance()) {
            float pause = planReadPauseMin() + npc.getRandom().nextFloat() * (planReadPauseMax() - planReadPauseMin());
            npc.startReading(Math.max(1, (int)(pause * 20)));
        }

        if (buildProgress >= steps.size()) {
            action.onComplete(sl, npc);
            phase = Phase.DONE;
            return true;
        }
        return false;
    }

    @Override
    public void saveTo(CompoundTag tag) {
        // State is persisted in Town.activeBuilds; no NPC NBT serialization needed.
    }

    // Reconstructs a BuildGoal from a Town.ActiveBuildState after a server restart or sleep.
    // fromLevel >= 0 indicates an Upgrade resume; fromLevel == -1 indicates a NewBuild resume.
    public static BuildGoal fromActiveBuildState(ActiveBuildState state, Npc npc, Town town, ServerLevel level) {
        BuildingDef def = BuildingDataHandler.get(state.defId()).orElse(null);
        if (def == null) return null;

        if (state.fromLevel() == -2) {
            // Repair resume.
            PlacedBuilding building = town.getBuildings().stream()
                .filter(b -> b.worldPos.equals(state.placementPos())).findFirst().orElse(null);
            if (building == null) return null;
            return new BuildGoal(npc, new RepairAction(building, def, town, level));
        }

        if (state.fromLevel() >= 0) {
            // Upgrade resume: find the placed building by worldPos and reconstruct with skipDiff.
            PlacedBuilding building = town.getBuildings().stream()
                .filter(b -> b.worldPos.equals(state.placementPos()))
                .findFirst().orElse(null);
            if (building == null) return null;
            UpgradeAction action = new UpgradeAction(building, def, state.fromLevel(), town, level);
            action.skipDiff = true;
            return new BuildGoal(npc, action);
        }

        // NewBuild resume: re-walk to the site, skip terrain prep and the reading animation.
        // addUnderConstruction is called here -- Town.fromNbt() is static with no ServerLevel.
        ConnectionPoint conn = new ConnectionPoint(
            state.connectionPos(), state.connectionDir(), state.connectionTarget(), 0L);
        NewBuildAction action = new NewBuildAction(
            def, conn, state.placementPos(), state.rotation(),
            state.entryConnectorPos(), state.cost(), town);
        action.skipTerrainPrep = true;
        action.skipInitialReading = true;
        SchematicBounds.computeBoundingBox(level, state.placementPos(), def.nbt, state.rotation())
            .ifPresent(bb -> town.addUnderConstruction(def.id, state.placementPos(), bb, state.rotation()));
        return new BuildGoal(npc, action);
    }
}
