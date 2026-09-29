package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.entity.Npc;

public class GoToPosition {

    private static final int STUCK_RETRY_TICKS = 40; // 2 seconds
    private static final double EXACT_NAV_ARRIVAL_SQ = 16.0; // 4 blocks — anything farther = path failed, retry

    private final Npc npc;
    private BlockPos target;
    private final double speed;
    private final double arrivalRadiusSq; // negative = exact-nav mode
    private int retryCountdown = 0;

    // Exact-nav: navigate to a specific position; arrived when isDone and within 2 blocks.
    public GoToPosition(Npc npc, BlockPos target, double speed) {
        this.npc = npc;
        this.target = target;
        this.speed = speed;
        this.arrivalRadiusSq = -1;
        issueMoveTo();
    }

    // Reach-nav: navigate within arrivalRadius of target (block placement reach).
    public GoToPosition(Npc npc, BlockPos target, double speed, double arrivalRadius) {
        this.npc = npc;
        this.target = target;
        this.speed = speed;
        this.arrivalRadiusSq = arrivalRadius * arrivalRadius;
        issueMoveTo();
    }

    public void updateTarget(BlockPos newTarget) {
        this.target = newTarget;
        this.retryCountdown = 0;
        issueMoveTo();
    }

    // Returns true when the NPC has reached the target.
    public boolean tick() {
        if (retryCountdown > 0) {
            retryCountdown--;
            return false;
        }

        if (arrivalRadiusSq < 0) {
            // Exact-nav: arrived when pathfinding completes AND NPC is close to target.
            if (npc.getNavigation().isDone()) {
                double distSq = npc.distanceToSqr(Vec3.atCenterOf(target));
                if (distSq > EXACT_NAV_ARRIVAL_SQ) {
                    retryCountdown = STUCK_RETRY_TICKS;
                    issueMoveTo();
                    return false;
                }
                npc.getNavigation().stop();
                return true;
            }
            return false;
        }

        // Reach-nav: arrived when within radius.
        double distSq = npc.distanceToSqr(Vec3.atCenterOf(target));
        if (distSq <= arrivalRadiusSq) {
            npc.getNavigation().stop();
            return true;
        }
        // Path ended but NPC is out of reach — stuck; retry after delay.
        if (npc.getNavigation().isDone()) {
            retryCountdown = STUCK_RETRY_TICKS;
            issueMoveTo();
        }
        return false;
    }

    private void issueMoveTo() {
        npc.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
    }
}
