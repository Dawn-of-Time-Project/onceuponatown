package org.dawnoftime.onceuponatown.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.dawnoftime.onceuponatown.datapack.BreederConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob.NpcBaseState;
import org.dawnoftime.onceuponatown.entity.ai.herder.AbstractHerderJob;
import org.dawnoftime.onceuponatown.entity.ai.herder.BreederJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.ActivityInstance;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class HerderJobController extends AbstractJobController {

    private static final List<String> HERDER_JOB_IDS = List.of("cowherd", "shepherd", "swineherd");

    public static void dispatchForNpc(AbstractHerderJob job, Town town, ServerLevel level) {
        String jobId = job.getJobId();
        Set<Long> occupied = getOccupiedIds(town, level, jobId, Npc::getBreederJob, job);
        List<PlacedBuilding> main = findBuildingsWithAnimals(jobId, town, level, occupied);
        if (!main.isEmpty()) {
            job.receiveMainAssignment(main.get(0), level, town);
            return;
        }
        BreederConfigDataHandler.Config cfg = BreederConfigDataHandler.get(jobId);
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
        for (String jobId : HERDER_JOB_IDS) {
            for (UUID id : town.getNpcsByJob(jobId)) {
                Entity e = level.getEntity(id);
                if (!(e instanceof Npc npc)) continue;
                BreederJob job = npc.getBreederJob();
                if (job == null) continue;
                NpcBaseState state = job.getBaseState();
                if (state != NpcBaseState.SECONDARY && state != NpcBaseState.WANDER) continue;

                if (state == NpcBaseState.SECONDARY) {
                    Set<Long> occupied = getOccupiedIds(town, level, jobId, Npc::getBreederJob, job);
                    List<PlacedBuilding> main = findBuildingsWithAnimals(jobId, town, level, occupied);
                    if (!main.isEmpty()) job.receiveMainAssignment(main.get(0), level, town);
                } else {
                    dispatchForNpc(job, town, level);
                }
            }
        }
    }

    private static List<PlacedBuilding> findBuildingsWithAnimals(
            String jobId, Town town, ServerLevel level, Set<Long> occupied) {
        List<PlacedBuilding> result = new ArrayList<>();
        for (PlacedBuilding b : town.getBuildings()) {
            if (occupied.contains(b.worldPos.asLong())) continue;
            if (!isBuildingForJob(b, jobId)) continue;
            if (BreederJob.hasReadyAnimals(level, b, jobId)) result.add(b);
        }
        return result;
    }

    private static boolean isBuildingForJob(PlacedBuilding b, String jobId) {
        BreederConfigDataHandler.Config cfg = BreederConfigDataHandler.get(jobId);
        return cfg != null && cfg.workBuildings.contains(b.defId) && b.bb != null;
    }
}
