package org.dawnoftime.onceuponatown.tick;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob.NpcBaseState;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.ActivityInstance;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public abstract class AbstractJobController {

    protected static List<ActivityInstance> collectSecondaryBuildings(
            Town town, List<ActivityDef> activities) {
        List<ActivityInstance> result = new ArrayList<>();
        for (PlacedBuilding building : town.getBuildings()) {
            if (building.bb == null) continue;
            for (ActivityDef def : activities) {
                if (!def.requiredBuilding().equals(building.defId)) continue;
                result.add(new ActivityInstance(def, building, null));
            }
        }
        return result;
    }

    protected static Set<Long> getOccupiedIds(
            Town town, ServerLevel level, String jobId,
            Function<Npc, ? extends AbstractNpcJob> jobGetter, AbstractNpcJob callingJob) {
        Set<Long> occupied = new HashSet<>();
        for (UUID id : town.getNpcsByJob(jobId)) {
            Entity e = level.getEntity(id);
            if (!(e instanceof Npc npc)) continue;
            AbstractNpcJob job = jobGetter.apply(npc);
            if (job == null || job == callingJob) continue;
            NpcBaseState state = job.getBaseState();
            if (state == NpcBaseState.WANDER || state == NpcBaseState.SLEEPING
                    || state == NpcBaseState.EATING) continue;
            long bid = job.getAssignedBuildingId();
            if (bid != -1L) occupied.add(bid);
        }
        return occupied;
    }
}
