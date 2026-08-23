package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

// Unified goal for doors and fence gates.
// Rule: barrier is open while the NPC is within PROXIMITY_SQ; closed otherwise.
// No timer — the door stays open as long as the NPC is nearby (covers construction zones).
public class OpenDoorGoal extends Goal {

    private static final double PROXIMITY_SQ = 2.0 * 2.0;
    private static final int DETECT_RADIUS = 2;
    private static final int PATH_LOOKAHEAD = 4;

    private final Mob mob;
    // Lower half pos for DoorBlock, single pos for FenceGateBlock.
    private BlockPos barrierPos;

    public OpenDoorGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        barrierPos = findClosedBarrier();
        if (barrierPos == null) return false;
        return mob.horizontalCollision || isOnActivePath(barrierPos);
    }

    @Override
    public void start() {
        setOpen(true);
    }

    @Override
    public boolean canContinueToUse() {
        return isStillOpen();
    }

    @Override
    public void tick() {
        if (mob.distanceToSqr(Vec3.atCenterOf(barrierPos)) > PROXIMITY_SQ) {
            setOpen(false);
        }
    }

    @Override
    public void stop() {
        if (mob.distanceToSqr(Vec3.atCenterOf(barrierPos)) > PROXIMITY_SQ) {
            setOpen(false);
        }
    }

    private void setOpen(boolean open) {
        BlockState state = mob.level().getBlockState(barrierPos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.getValue(DoorBlock.OPEN) == open) return;
            mob.level().setBlock(barrierPos, state.setValue(DoorBlock.OPEN, open), Block.UPDATE_ALL);
            BlockPos upper = barrierPos.above();
            BlockState upperState = mob.level().getBlockState(upper);
            if (upperState.getBlock() instanceof DoorBlock) {
                mob.level().setBlock(upper, upperState.setValue(DoorBlock.OPEN, open), Block.UPDATE_ALL);
            }
        } else if (state.getBlock() instanceof FenceGateBlock) {
            if (state.getValue(FenceGateBlock.OPEN) == open) return;
            mob.level().setBlock(barrierPos, state.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_ALL);
        }
    }

    private boolean isStillOpen() {
        BlockState state = mob.level().getBlockState(barrierPos);
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.OPEN);
        if (state.getBlock() instanceof FenceGateBlock) return state.getValue(FenceGateBlock.OPEN);
        return false;
    }

    // Scans DETECT_RADIUS blocks ahead in each cardinal direction at NPC foot and head level.
    private BlockPos findClosedBarrier() {
        BlockPos npcPos = mob.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dist = 1; dist <= DETECT_RADIUS; dist++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos candidate = npcPos.relative(dir, dist).above(dy);
                    BlockPos result = closedBarrierPosAt(candidate);
                    if (result != null) return result;
                }
            }
        }
        return null;
    }

    // Returns canonical position (lower half for doors, gate pos for gates) if block is a closed barrier.
    private BlockPos closedBarrierPosAt(BlockPos pos) {
        BlockState state = mob.level().getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        }
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            return pos;
        }
        return null;
    }

    private boolean isOnActivePath(BlockPos barrier) {
        Path path = mob.getNavigation().getPath();
        if (path == null) return false;
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int to = Math.min(path.getNodeCount(), path.getNextNodeIndex() + PATH_LOOKAHEAD);
        for (int i = from; i < to; i++) {
            var node = path.getNode(i);
            if (Math.abs(node.x - barrier.getX()) <= 1
                    && Math.abs(node.y - barrier.getY()) <= 1
                    && Math.abs(node.z - barrier.getZ()) <= 1) {
                return true;
            }
        }
        return false;
    }
}
