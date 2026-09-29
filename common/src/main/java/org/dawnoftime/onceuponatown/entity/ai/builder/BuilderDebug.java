package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.joml.Vector3f;

// Temporary debug tool: visualizes builder path and targets as colored particles in-world.
// Set ENABLED = false (or delete this file + the call in BuildGoal) when done debugging.
public class BuilderDebug {

    public static final boolean ENABLED = true;

    private static final DustParticleOptions RED    = new DustParticleOptions(new Vector3f(1f, 0.1f, 0.1f), 2.0f);
    private static final DustParticleOptions YELLOW = new DustParticleOptions(new Vector3f(1f, 1f, 0f), 1.5f);

    // Call each BUILDING tick.
    public static void tick(ServerLevel level, Npc npc, BlockPos blockTarget) {
        if (!ENABLED || npc.tickCount % 5 != 0) return;

        // Red: block the NPC is trying to place
        sendDust(level, RED, blockTarget.getX() + 0.5, blockTarget.getY() + 1.3, blockTarget.getZ() + 0.5);

        // Yellow: actual vanilla A* path nodes
        Path path = npc.getNavigation().getPath();
        if (path != null) {
            for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
                Node node = path.getNode(i);
                sendDust(level, YELLOW, node.x + 0.5, node.y + 0.5, node.z + 0.5, 4);
            }
        }
    }

    private static void sendDust(ServerLevel level, DustParticleOptions opts, double x, double y, double z) {
        level.sendParticles(opts, x, y, z, 1, 0, 0, 0, 0);
    }

    private static void sendDust(ServerLevel level, DustParticleOptions opts, double x, double y, double z, int count) {
        level.sendParticles(opts, x, y, z, count, 0.1, 0.1, 0.1, 0);
    }
}
