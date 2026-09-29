package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AnimationType;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.StandSlot;
import org.dawnoftime.onceuponatown.town.Town;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.player.Player;

/**
 * Shared controller for secondary NPC activities.
 * Handles building selection, block scanning, navigation, animation, and cleanup.
 * Jobs call tryStart() when idle, tick() each frame, and cancel() on interruption.
 *
 * Two navigation modes:
 *  - Block scan: targetBlock != null -> scans building BB, navigates to found block.
 *  - Stand mode: standType != null  -> acquires a StandSlot, navigates directly to it.
 */
public class SecondaryActivityController {

    public enum Result { RUNNING, NOT_FOUND }

    private @Nullable ActivityInstance current = null;
    private int performTicks = 0;

    // Stand mode state (active when current.controller == null)
    private @Nullable BlockPos currentStandPos = null;
    private @Nullable BuildingEntryNav currentStandEntryNav = null;
    private @Nullable GoToPosition standNav = null;
    private boolean standArrived = false;
    private @Nullable Runnable standReleaser = null;

    // When the navigation target is offset to the front face of a directional block, this holds the actual block pos.
    private @Nullable BlockPos facingBlockPos = null;

    /**
     * Picks a random eligible activity from the pool and starts it.
     * Returns true if an activity was started; false if no eligible candidate exists.
     */
    public boolean tryStart(Town town, Npc npc, List<ActivityDef> pool) {
        if (pool.isEmpty()) return false;
        if (!(npc.level() instanceof ServerLevel level)) return false;

        List<ActivityDef> candidateDefs = new ArrayList<>();
        List<PlacedBuilding> candidateBuildings = new ArrayList<>();
        for (PlacedBuilding building : town.getBuildings()) {
            if (building.bb == null) continue;
            for (ActivityDef def : pool) {
                if (!def.requiredBuilding().equals(building.defId)) continue;
                // Stand-mode activities skip block scan validation; stand availability is checked at start time.
                if (def.standType() == null && def.targetBlock() != null) {
                    Block block = BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(def.targetBlock())).orElse(null);
                    if (block == null) continue;
                    boolean found = false;
                    BoundingBox bb = building.bb;
                    outer:
                    for (int bx = bb.minX(); bx <= bb.maxX(); bx++)
                        for (int by = bb.minY(); by <= bb.maxY(); by++)
                            for (int bz = bb.minZ(); bz <= bb.maxZ(); bz++) {
                                if (level.getBlockState(new BlockPos(bx, by, bz)).is(block)) {
                                    found = true;
                                    break outer;
                                }
                            }
                    if (!found) continue;
                }
                candidateDefs.add(def);
                candidateBuildings.add(building);
            }
        }
        if (candidateDefs.isEmpty()) return false;

        int idx = npc.getRandom().nextInt(candidateDefs.size());
        ActivityDef def = candidateDefs.get(idx);
        PlacedBuilding building = candidateBuildings.get(idx);

        facingBlockPos = null;

        // Stand mode: acquire a slot before committing to the activity.
        if (def.standType() != null) {
            StandSlot slot = town.acquireStand(building.defId, def.standType(), npc.getUUID());
            if (slot == null) return false;
            currentStandPos = slot.position;
            standNav = null;
            standArrived = false;
            standReleaser = () -> town.releaseStand(npc.getUUID());
            current = new ActivityInstance(def, building, null);
        } else {
            current = new ActivityInstance(def, building, new BuildingBlockController(npc));
        }

        if (!def.heldItem().equals("minecraft:air")) {
            BuiltInRegistries.ITEM.getOptional(new ResourceLocation(def.heldItem()))
                .ifPresent(item -> npc.holdInMainHand(new ItemStack(item)));
        }
        performTicks = 0;
        return true;
    }

    /**
     * Drives the active activity for one tick.
     * Cancels automatically and returns NOT_FOUND when the building disappears or the target block is gone.
     */
    public Result tick(ServerLevel level, Town town, Npc npc, double walkSpeed, @Nullable Player cachedLookPlayer) {
        if (current == null) return Result.NOT_FOUND;

        // Stand mode: navigate to building entry first, then to the stand position.
        if (current.controller == null) {
            if (!standArrived) {
                if (currentStandEntryNav == null && standNav == null) {
                    currentStandEntryNav = new BuildingEntryNav(
                        npc,
                        BuildingEntryNav.resolveEntryPos(level, current.targetBuilding),
                        walkSpeed
                    );
                }
                if (currentStandEntryNav != null) {
                    if (!currentStandEntryNav.tick()) return Result.RUNNING;
                    currentStandEntryNav = null;
                    standNav = new GoToPosition(npc, currentStandPos, walkSpeed);
                }
                if (standNav != null) {
                    if (!standNav.tick()) return Result.RUNNING;
                    standArrived = true;
                    standNav = null;
                }
                return Result.RUNNING;
            }
            tickPerforming(npc, level, cachedLookPlayer);
            return Result.RUNNING;
        }

        // Block-scan mode.
        BuildingBlockController.BlockScanner scanner = (lvl, building) -> {
            String blockId = current.def.targetBlock();
            if (blockId == null) {
                BoundingBox bb = building.bb;
                return new BlockPos((bb.minX() + bb.maxX()) / 2, bb.minY(), (bb.minZ() + bb.maxZ()) / 2);
            }
            Block block = BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(blockId)).orElse(null);
            if (block == null) return null;
            BoundingBox bb = building.bb;
            BlockPos found = null;
            double bestDist = Double.MAX_VALUE;
            for (int bx = bb.minX(); bx <= bb.maxX(); bx++)
                for (int by = bb.minY(); by <= bb.maxY(); by++)
                    for (int bz = bb.minZ(); bz <= bb.maxZ(); bz++) {
                        BlockPos p = new BlockPos(bx, by, bz);
                        if (lvl.getBlockState(p).is(block)) {
                            double d = npc.distanceToSqr(Vec3.atCenterOf(p));
                            if (d < bestDist) { bestDist = d; found = p; }
                        }
                    }
            if (found != null) {
                BlockPos front = getFacingFrontPos(lvl, found);
                if (front != null) {
                    facingBlockPos = found;
                    return front;
                }
            }
            facingBlockPos = null;
            return found;
        };

        BuildingBlockController.Result r = current.controller.tick(
            level, town, List.of(current.def.requiredBuilding()), walkSpeed, current.def.workReach(), scanner);
        if (r == BuildingBlockController.Result.PERFORMING) tickPerforming(npc, level, cachedLookPlayer);
        if (r == BuildingBlockController.Result.NOT_FOUND) { cancel(npc); return Result.NOT_FOUND; }
        return Result.RUNNING;
    }

    /**
     * Starts an activity for a pre-selected def and building, skipping random selection.
     * Supports both stand-mode and block-scan-mode activities.
     * Does nothing (leaves controller inactive) when the required block does not exist in the building.
     */
    public void startWith(Town town, Npc npc, ActivityDef def, PlacedBuilding building) {
        if (building.bb == null) return;
        facingBlockPos = null;
        if (def.standType() != null) {
            StandSlot slot = town.acquireStand(building.defId, def.standType(), npc.getUUID());
            if (slot == null) return;
            currentStandPos = slot.position;
            standNav = null;
            standArrived = false;
            standReleaser = () -> town.releaseStand(npc.getUUID());
            current = new ActivityInstance(def, building, null);
        } else {
            if (def.targetBlock() != null && npc.level() instanceof ServerLevel level) {
                Block block = BuiltInRegistries.BLOCK.getOptional(new ResourceLocation(def.targetBlock())).orElse(null);
                if (block == null) return;
                BoundingBox bb = building.bb;
                boolean found = false;
                outer:
                for (int bx = bb.minX(); bx <= bb.maxX(); bx++)
                    for (int by = bb.minY(); by <= bb.maxY(); by++)
                        for (int bz = bb.minZ(); bz <= bb.maxZ(); bz++) {
                            if (level.getBlockState(new BlockPos(bx, by, bz)).is(block)) {
                                found = true;
                                break outer;
                            }
                        }
                if (!found) return;
            }
            current = new ActivityInstance(def, building, new BuildingBlockController(npc));
        }
        if (!def.heldItem().equals("minecraft:air")) {
            net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getOptional(new net.minecraft.resources.ResourceLocation(def.heldItem()))
                .ifPresent(item -> npc.holdInMainHand(new net.minecraft.world.item.ItemStack(item)));
        }
        performTicks = 0;
    }

    /** Cancels the active activity, frees NPC hands, releases any stand, and stops navigation. */
    public void cancel(Npc npc) {
        if (current == null) return;
        if (standReleaser != null) {
            standReleaser.run();
            standReleaser = null;
        }
        currentStandPos = null;
        currentStandEntryNav = null;
        standNav = null;
        standArrived = false;
        if (current.controller != null) {
            if (current.def.animationType() == AnimationType.SMELT
                    && npc.level() instanceof ServerLevel level) {
                BlockPos furnacePos = facingBlockPos != null ? facingBlockPos : current.controller.getTargetBlockPos();
                if (furnacePos != null) setFurnaceLit(level, furnacePos, false);
            }
            current.controller.reset();
        }
        facingBlockPos = null;
        npc.freeHands();
        npc.getNavigation().stop();
        current = null;
        performTicks = 0;
    }

    public boolean isActive() { return current != null; }

    /** True once the NPC has arrived at its target and is in the performing phase. */
    public boolean isPerforming() { return current != null && performTicks > 0; }

    /** Returns the active ActivityDef, or null if no activity is running. */
    public ActivityDef getActiveDef() { return current != null ? current.def : null; }

    private void tickPerforming(Npc npc, ServerLevel level, @Nullable Player cachedLookPlayer) {
        npc.getNavigation().stop();
        if (current.def.animationType() != AnimationType.SELL && cachedLookPlayer == null) {
            BlockPos lookPos = facingBlockPos != null ? facingBlockPos
                : (current.controller != null ? current.controller.getTargetBlockPos() : null);
            if (lookPos != null) {
                npc.getLookControl().setLookAt(
                    lookPos.getX() + 0.5, lookPos.getY() + 0.5, lookPos.getZ() + 0.5,
                    10f, 10f
                );
            }
        }
        if (current.def.animationType() == AnimationType.SELL) {
            // No animation; player look is handled centrally by AbstractNpcJob.tickLookControl().
        } else if (current.def.animationType() == AnimationType.CRAFT) {
            if (performTicks % 25 == 0) npc.notifyBlockPlaced();
        } else if (current.def.animationType() == AnimationType.SMELT) {
            if (performTicks == 0 && current.controller != null) {
                BlockPos furnacePos = facingBlockPos != null ? facingBlockPos : current.controller.getTargetBlockPos();
                if (furnacePos != null) setFurnaceLit(level, furnacePos, true);
            }
            if (performTicks % 40 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
            }
        } else {
            if (performTicks % 25 == 0) {
                npc.swing(InteractionHand.MAIN_HAND);
                npc.notifyBlockPlaced();
            }
        }
        performTicks++;
    }

    /**
     * Returns the position in front of the block's open face (HORIZONTAL_FACING direction),
     * or null if the block has no facing property or the front position is not walkable.
     */
    @Nullable
    private static BlockPos getFacingFrontPos(ServerLevel level, BlockPos blockPos) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(blockPos);
        if (!state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))
            return null;
        net.minecraft.core.Direction facing = state.getValue(
            net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        BlockPos front = blockPos.relative(facing);
        net.minecraft.world.level.block.state.BlockState below = level.getBlockState(front.below());
        net.minecraft.world.level.block.state.BlockState at    = level.getBlockState(front);
        net.minecraft.world.level.block.state.BlockState above = level.getBlockState(front.above());
        if (below.isFaceSturdy(level, front.below(), net.minecraft.core.Direction.UP)
                && !at.isSolid()
                && !above.isSolid()) {
            return front;
        }
        return null;
    }

    // Toggles the LIT property on furnace, smoker, and blast_furnace blocks.
    private static void setFurnaceLit(ServerLevel level, BlockPos pos, boolean lit) {
        net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT)) {
            level.setBlock(pos, state.setValue(
                net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT, lit), 3);
        }
    }
}
