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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

// Unified goal for doors and fence gates.
// Opens ALL closed barriers within detect radius so the NPC is never blocked
// by a wrong-barrier selection when door and gate are adjacent.
// Rule: barriers stay open while the NPC is within PROXIMITY_SQ; closed otherwise.
public class OpenDoorGoal extends Goal {

    private static final double PROXIMITY_SQ = 2.0 * 2.0;
    private static final int DETECT_RADIUS = 2;

    private final Mob mob;
    private final List<BlockPos> barrierPositions = new ArrayList<>();

    public OpenDoorGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        barrierPositions.clear();
        findAllClosedBarriers(barrierPositions);
        return !barrierPositions.isEmpty() && (mob.horizontalCollision || anyOnActivePath());
    }

    @Override
    public void start() {
        setAllOpen(true);
    }

    @Override
    public boolean canContinueToUse() {
        return !barrierPositions.isEmpty() && barrierPositions.stream().anyMatch(this::isStillOpen);
    }

    @Override
    public void tick() {
        barrierPositions.removeIf(pos -> {
            if (mob.distanceToSqr(Vec3.atCenterOf(pos)) > PROXIMITY_SQ) {
                setBarrierOpen(pos, false);
                return true;
            }
            return false;
        });
    }

    @Override
    public void stop() {
        for (BlockPos pos : barrierPositions) {
            if (mob.distanceToSqr(Vec3.atCenterOf(pos)) > PROXIMITY_SQ) {
                setBarrierOpen(pos, false);
            }
        }
        barrierPositions.clear();
    }

    private void setAllOpen(boolean open) {
        for (BlockPos pos : barrierPositions) {
            setBarrierOpen(pos, open);
        }
    }

    private void setBarrierOpen(BlockPos pos, boolean open) {
        BlockState state = mob.level().getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) {
            if (state.getValue(DoorBlock.OPEN) == open) return;
            mob.level().setBlock(pos, state.setValue(DoorBlock.OPEN, open), Block.UPDATE_ALL);
            BlockPos upper = pos.above();
            BlockState upperState = mob.level().getBlockState(upper);
            if (upperState.getBlock() instanceof DoorBlock) {
                mob.level().setBlock(upper, upperState.setValue(DoorBlock.OPEN, open), Block.UPDATE_ALL);
            }
        } else if (state.getBlock() instanceof FenceGateBlock) {
            if (state.getValue(FenceGateBlock.OPEN) == open) return;
            mob.level().setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), Block.UPDATE_ALL);
        }
    }

    private boolean isStillOpen(BlockPos pos) {
        BlockState state = mob.level().getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) return state.getValue(DoorBlock.OPEN);
        if (state.getBlock() instanceof FenceGateBlock) return state.getValue(FenceGateBlock.OPEN);
        return false;
    }

    // Collects all closed barriers within DETECT_RADIUS in each cardinal direction.
    private void findAllClosedBarriers(List<BlockPos> out) {
        BlockPos npcPos = mob.blockPosition();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dist = 1; dist <= DETECT_RADIUS; dist++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos candidate = npcPos.relative(dir, dist).above(dy);
                    BlockPos result = closedBarrierPosAt(candidate);
                    if (result != null && !out.contains(result)) {
                        out.add(result);
                    }
                }
            }
        }
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

    private boolean anyOnActivePath() {
        var path = mob.getNavigation().getPath();
        if (path == null) return false;
        int from = Math.max(0, path.getNextNodeIndex() - 1);
        int to = Math.min(path.getNodeCount(), path.getNextNodeIndex() + 4);
        for (BlockPos barrier : barrierPositions) {
            for (int i = from; i < to; i++) {
                var node = path.getNode(i);
                if (Math.abs(node.x - barrier.getX()) <= 1
                        && Math.abs(node.y - barrier.getY()) <= 1
                        && Math.abs(node.z - barrier.getZ()) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
