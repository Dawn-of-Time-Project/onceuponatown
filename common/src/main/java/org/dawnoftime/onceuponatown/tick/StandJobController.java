package org.dawnoftime.onceuponatown.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob.NpcBaseState;
import org.dawnoftime.onceuponatown.entity.ai.standbased.AbstractStandBasedJob;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.ActivityInstance;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.StandSlot;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.List;
import java.util.UUID;

public class StandJobController extends AbstractJobController {

    private static final List<String> STAND_JOB_IDS = List.of("miner", "merchant");

    public static void dispatchForNpc(AbstractStandBasedJob job, Town town, ServerLevel level) {
        StandJobConfig cfg = job.getConfig();
        if (cfg == null) { job.enterWander(); return; }

        List<String> workBuildings = cfg.getWorkBuildings();
        String standType = cfg.getWorkStandType();

        if (standType != null && !workBuildings.isEmpty()) {
            for (PlacedBuilding building : town.getBuildings()) {
                if (!workBuildings.contains(building.defId)) continue;
                StandSlot slot = town.acquireStand(building.defId, standType, job.getNpc().getUUID());
                if (slot != null) {
                    job.receiveMainAssignment(building, slot, level, town);
                    return;
                }
            }
        }

        for (ActivityInstance c : collectSecondaryBuildings(town, cfg.getSecondaryActivities())) {
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
        for (String jobId : STAND_JOB_IDS) {
            for (UUID id : town.getNpcsByJob(jobId)) {
                Entity e = level.getEntity(id);
                if (!(e instanceof Npc npc)) continue;
                AbstractStandBasedJob job = npc.getStandBasedJob();
                if (job == null) continue;
                NpcBaseState state = job.getBaseState();
                if (state != NpcBaseState.SECONDARY && state != NpcBaseState.WANDER) continue;

                if (state == NpcBaseState.SECONDARY) {
                    StandJobConfig cfg = job.getConfig();
                    if (cfg == null) continue;
                    List<String> workBuildings = cfg.getWorkBuildings();
                    String standType = cfg.getWorkStandType();
                    if (standType == null || workBuildings.isEmpty()) continue;
                    for (PlacedBuilding building : town.getBuildings()) {
                        if (!workBuildings.contains(building.defId)) continue;
                        StandSlot slot = town.acquireStand(building.defId, standType, npc.getUUID());
                        if (slot != null) {
                            job.receiveMainAssignment(building, slot, level, town);
                            break;
                        }
                    }
                } else {
                    dispatchForNpc(job, town, level);
                }
            }
        }
    }
}
