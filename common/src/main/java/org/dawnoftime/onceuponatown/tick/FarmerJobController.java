package org.dawnoftime.onceuponatown.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.dawnoftime.onceuponatown.datapack.FarmerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob.NpcBaseState;
import org.dawnoftime.onceuponatown.entity.ai.farmer.AbstractFarmerJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.ActivityInstance;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class FarmerJobController extends AbstractJobController {

    private static final List<String> FARMER_JOB_IDS = List.of("wheat_farmer", "potato_farmer");

    public static void dispatchForNpc(AbstractFarmerJob job, Town town, ServerLevel level) {
        Set<Long> occupied = getOccupiedIds(town, level, job.getJobId(), Npc::getFarmerJob, job);
        FarmerConfigDataHandler.Config cfg = FarmerConfigDataHandler.get(job.getJobId());
        List<String> farmBuildings = cfg != null ? cfg.farmBuildings : List.of();

        List<PlacedBuilding> main = findBuildingsWithWork(town, level, occupied, farmBuildings);
        if (!main.isEmpty()) {
            job.receiveMainAssignment(main.get(0), level, town);
            return;
        }

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
        for (String jobId : FARMER_JOB_IDS) {
            for (UUID id : town.getNpcsByJob(jobId)) {
                Entity e = level.getEntity(id);
                if (!(e instanceof Npc npc)) continue;
                AbstractFarmerJob job = npc.getFarmerJob();
                if (job == null) continue;
                NpcBaseState state = job.getBaseState();
                if (state != NpcBaseState.SECONDARY && state != NpcBaseState.WANDER) continue;

                if (state == NpcBaseState.SECONDARY) {
                    FarmerConfigDataHandler.Config cfg = FarmerConfigDataHandler.get(jobId);
                    List<String> farmBuildings = cfg != null ? cfg.farmBuildings : List.of();
                    Set<Long> occupied = getOccupiedIds(town, level, job.getJobId(), Npc::getFarmerJob, job);
                    List<PlacedBuilding> main = findBuildingsWithWork(town, level, occupied, farmBuildings);
                    if (!main.isEmpty()) job.receiveMainAssignment(main.get(0), level, town);
                } else {
                    dispatchForNpc(job, town, level);
                }
            }
        }
    }

    private static List<PlacedBuilding> findBuildingsWithWork(
            Town town, ServerLevel level, Set<Long> occupied, List<String> farmBuildings) {
        List<PlacedBuilding> result = new ArrayList<>();
        for (PlacedBuilding b : town.getBuildings()) {
            if (occupied.contains(b.worldPos.asLong())) continue;
            if (!farmBuildings.contains(b.defId)) continue;
            if (b.bb == null) continue;
            if (AbstractFarmerJob.hasPendingFarmWork(level, b)) result.add(b);
        }
        return result;
    }
}
