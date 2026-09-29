package org.dawnoftime.onceuponatown.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.dawnoftime.onceuponatown.datapack.BuilderConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob.NpcBaseState;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuilderJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.ActivityInstance;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.UUID;

public class BuilderJobController extends AbstractJobController {

    public static void dispatchForNpc(BuilderJob job, Town town, ServerLevel level) {
        if (town == null) { job.enterWander(); return; }

        BlockPos anchorPos = job.getNpc().getTownAnchorPos();
        long gameTime = level.getGameTime();

        // Resume any saved active build state before attempting new assignment.
        BuildWorkManager.tryResume(town, level, anchorPos, gameTime);

        // Resume may have assigned work to this builder.
        if (!job.isAvailableForAssignment()) return;

        // Try immediate assignment from queue.
        if (BuildWorkManager.tryAssignOne(town, level, job.getNpc(), job, anchorPos, gameTime)) return;

        // Secondary activity.
        BuilderConfigDataHandler.Config cfg = BuilderConfigDataHandler.get();
        if (cfg != null) {
            for (ActivityInstance c : collectSecondaryBuildings(town, cfg.secondaryActivities)) {
                job.receiveSecondaryAssignment(c.def, c.targetBuilding, level, town);
                if (job.getBaseState() == NpcBaseState.SECONDARY) return;
            }
        }

        // Nothing available.
        job.enterWander();
    }

    public static void tick(Town town, ServerLevel level, long gameTime, long anchorKey) {
        if (gameTime % 200 != 0) return;

        BlockPos anchorPos = BlockPos.of(anchorKey);

        // Always run resume at this cadence to catch builders loaded from a save.
        BuildWorkManager.tryResume(town, level, anchorPos, gameTime);

        // Background scan: pull WANDER or SECONDARY builders into MAIN if work appeared.
        for (UUID uuid : town.getNpcsByJob("builder")) {
            Entity e = level.getEntity(uuid);
            if (!(e instanceof Npc npc)) continue;
            BuilderJob job = npc.getBuilderJob();
            if (job == null) continue;

            NpcBaseState state = job.getBaseState();

            if (state == NpcBaseState.SECONDARY) {
                if (BuildWorkManager.tryAssignOne(town, level, npc, job, anchorPos, gameTime)) break;
                continue;
            }

            if (!job.isAvailableForAssignment()) continue;

            if (BuildWorkManager.tryAssignOne(town, level, npc, job, anchorPos, gameTime)) {
                break;
            } else {
                BuilderConfigDataHandler.Config cfg = BuilderConfigDataHandler.get();
                if (cfg != null) {
                    for (ActivityInstance c : collectSecondaryBuildings(town, cfg.secondaryActivities)) {
                        job.receiveSecondaryAssignment(c.def, c.targetBuilding, level, town);
                        if (job.getBaseState() == NpcBaseState.SECONDARY) break;
                    }
                }
            }
        }
    }
}
