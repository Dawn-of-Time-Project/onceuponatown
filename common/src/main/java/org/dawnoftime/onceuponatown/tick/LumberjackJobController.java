package org.dawnoftime.onceuponatown.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.dawnoftime.onceuponatown.datapack.LumberjackConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob.NpcBaseState;
import org.dawnoftime.onceuponatown.entity.ai.lumberjack.LumberjackJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.ActivityInstance;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class LumberjackJobController extends AbstractJobController {

    public static void dispatchForNpc(LumberjackJob job, Town town, ServerLevel level) {
        Set<Long> occupied = getOccupiedIds(town, level, "lumberjack", Npc::getLumberjackJob, job);
        List<PlacedBuilding> main = findBuildingsWithLogs(town, level, occupied);
        if (!main.isEmpty()) {
            job.receiveMainAssignment(main.get(0), level, town);
            return;
        }
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        List<ActivityDef> activities = cfg != null ? cfg.secondaryActivities : List.of();
        for (ActivityInstance c : collectSecondaryBuildings(town, activities)) {
            job.receiveSecondaryAssignment(c.def, c.targetBuilding, level, town);
            if (job.getBaseState() == NpcBaseState.SECONDARY) return;
        }
        job.enterWander();
    }

    public static void tick(Town town, ServerLevel level, long gameTime, long anchorKey) {
        if (gameTime % 200 != 0) return;
        backgroundScan(town, level);
    }

    private static void backgroundScan(Town town, ServerLevel level) {
        for (UUID id : town.getNpcsByJob("lumberjack")) {
            Entity e = level.getEntity(id);
            if (!(e instanceof Npc npc)) continue;
            LumberjackJob job = npc.getLumberjackJob();
            if (job == null) continue;
            NpcBaseState state = job.getBaseState();
            if (state != NpcBaseState.SECONDARY && state != NpcBaseState.WANDER) continue;

            if (state == NpcBaseState.SECONDARY) {
                Set<Long> occupied = getOccupiedIds(town, level, "lumberjack", Npc::getLumberjackJob, job);
                List<PlacedBuilding> main = findBuildingsWithLogs(town, level, occupied);
                if (!main.isEmpty()) job.receiveMainAssignment(main.get(0), level, town);
            } else {
                dispatchForNpc(job, town, level);
            }
        }
    }

    private static List<PlacedBuilding> findBuildingsWithLogs(Town town, ServerLevel level, Set<Long> occupied) {
        List<PlacedBuilding> result = new ArrayList<>();
        for (PlacedBuilding b : town.getBuildings()) {
            if (occupied.contains(b.worldPos.asLong())) continue;
            if (!isGroveBuilding(b)) continue;
            if (b.bb == null) continue;
            if (LumberjackJob.hasMatureLogs(level, b)) result.add(b);
        }
        return result;
    }

    private static boolean isGroveBuilding(PlacedBuilding b) {
        LumberjackConfigDataHandler.Config cfg = LumberjackConfigDataHandler.get();
        return cfg != null && cfg.woodSourceBuildings.contains(b.defId);
    }
}
