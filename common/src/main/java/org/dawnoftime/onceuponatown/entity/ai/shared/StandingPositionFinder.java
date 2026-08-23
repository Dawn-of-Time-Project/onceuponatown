package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.TrapDoorBlock;

public final class StandingPositionFinder {
    private StandingPositionFinder() {}

    public static BlockPos find(ServerLevel level, BlockPos target, double reachDist) {
        int radius = (int) Math.floor(reachDist);
        double reachSq = reachDist * reachDist;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // NPC feet are at cy; target center is at ty+0.5, so effective vertical offset is dy-0.5
                    double effectiveDy = dy - 0.5;
                    if (dx * dx + effectiveDy * effectiveDy + dz * dz > reachSq) continue;

                    BlockPos c = target.offset(dx, dy, dz);
                    BlockPos below = c.below();

                    if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
                    if (level.getBlockState(c).isSolid()) continue;
                    if (level.getBlockState(c.above()).isSolid()) continue;
                    // Trapdoors are excluded: they can close while the NPC stands in them.
                    if (level.getBlockState(c).getBlock() instanceof TrapDoorBlock) continue;
                    if (level.getBlockState(c.above()).getBlock() instanceof TrapDoorBlock) continue;

                    // Penalize positions at or above the target block. The penalty (100)
                    // exceeds the maximum possible distSq within the reach sphere (~49),
                    // so any ground-level candidate always beats any elevated candidate.
                    // An elevated position is only chosen when no ground position exists.
                    double heightPenalty = dy >= 0 ? 100.0 : 0.0;
                    double d = c.distSqr(target) + heightPenalty;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        best = c;
                    }
                }
            }
        }
        return best;
    }
}
