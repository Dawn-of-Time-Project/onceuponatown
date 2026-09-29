package org.dawnoftime.onceuponatown.entity.ai.herder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingEntryNav;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.entity.ai.shared.*;
import org.dawnoftime.onceuponatown.tick.HerderJobController;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class AbstractHerderJob<C extends SleepConfig & WorkConfig & HerderConfig>
        extends AbstractNpcJob {

    protected enum WorkState { ENTERING_BUILDING, APPROACHING_ANIMAL, PERFORMING_ACTION }

    protected WorkState workState = WorkState.ENTERING_BUILDING;

    private PlacedBuilding assignedBuilding = null;

    protected List<UUID> animalWorkList = new ArrayList<>();
    protected int animalCursor = 0;
    protected int actionDelayCounter = 0;
    private int actionDelayTarget = 0;
    protected UUID currentAnimalId = null;
    private PlacedBuilding currentBuilding = null;
    private BuildingEntryNav entryNav = null;
    private GoToPosition animalNav = null;

    protected AbstractHerderJob(Npc npc) {
        super(npc);
    }

    protected abstract C getConfig();

    protected abstract List<UUID> scanBuilding(ServerLevel level, PlacedBuilding building, C cfg);

    protected abstract boolean isAnimalReady(@Nullable Entity entity);

    protected abstract void performOnAnimal(Entity animal, C cfg, ServerLevel level);

    protected abstract int getActionDelay(C cfg);

    protected static AABB buildingAabb(PlacedBuilding building) {
        return new AABB(
            building.bb.minX(), building.bb.minY(), building.bb.minZ(),
            building.bb.maxX() + 1, building.bb.maxY() + 1, building.bb.maxZ() + 1
        );
    }

    @Override
    public long getAssignedBuildingId() {
        return assignedBuilding != null ? assignedBuilding.worldPos.asLong() : -1L;
    }

    @Override
    protected void dispatchFromController(ServerLevel level, Town town) {
        HerderJobController.dispatchForNpc(this, town, level);
    }

    @Override
    protected void onEnterSleep() {
        assignedBuilding = null;
    }

    @Override
    protected void onEnterEating(Town town) {
        assignedBuilding = null;
    }

    @Override
    protected void onResetWork() {
        animalWorkList.clear();
        animalCursor = 0;
        actionDelayTarget = 0;
        currentAnimalId = null;
        currentBuilding = null;
        entryNav = null;
        animalNav = null;
    }

    @Override
    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        activityController.startWith(town, npc, def, building);
    }

    @Override
    protected void onControllerAssignMain(PlacedBuilding building, ServerLevel level, Town town) {
        assignedBuilding = building;
        C cfg = getConfig();
        if (cfg == null) return;
        List<UUID> candidates = scanBuilding(level, building, cfg);
        if (candidates.isEmpty()) { onMainWorkComplete(level, town); return; }
        animalWorkList = candidates;
        animalCursor = 0;
        currentBuilding = building;
        entryNav = new BuildingEntryNav(npc, BuildingEntryNav.resolveEntryPos(level, building), cfg.getWalkSpeed());
        workState = WorkState.ENTERING_BUILDING;
    }

    @Override
    public final void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        C cfg = getConfig();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        tickSharedPreamble(level, town, cfg);

        switch (baseState) {
            case SLEEPING  -> tickSleepingBase(level, town, cfg);
            case EATING    -> tickEatingBase(level, town);
            case SECONDARY -> tickSecondaryBase(level, town, cfg);
            case WANDER    -> maybeWander();
            case MAIN      -> tickMain(level, town, cfg);
        }
    }

    private void tickMain(ServerLevel level, Town town, C cfg) {
        switch (workState) {
            case ENTERING_BUILDING  -> tickEnteringBuilding(level, town, cfg);
            case APPROACHING_ANIMAL -> tickApproachingAnimal(level, town, cfg);
            case PERFORMING_ACTION  -> tickPerformingAction(level, town, cfg);
        }
    }

    private void tickEnteringBuilding(ServerLevel level, Town town, C cfg) {
        if (!entryNav.tick()) return;
        entryNav = null;
        currentBuilding = null;
        startNextAnimal(level, town, cfg);
    }

    protected void startNextAnimal(ServerLevel level, Town town, C cfg) {
        while (animalCursor < animalWorkList.size()) {
            UUID id = animalWorkList.get(animalCursor);
            Entity entity = level.getEntity(id);
            if (isAnimalReady(entity)) {
                currentAnimalId = id;
                BlockPos animalBlock = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
                animalNav = new GoToPosition(npc, animalBlock, cfg.getWalkSpeed());
                workState = WorkState.APPROACHING_ANIMAL;
                return;
            }
            animalCursor++;
        }
        npc.freeHands();
        onMainWorkComplete(level, town);
    }

    private void onMainWorkComplete(ServerLevel level, Town town) {
        assignedBuilding = null;
        animalWorkList.clear();
        animalCursor = 0;
        currentAnimalId = null;
        currentBuilding = null;
        entryNav = null;
        animalNav = null;
        HerderJobController.dispatchForNpc(this, town, level);
    }

    private void tickApproachingAnimal(ServerLevel level, Town town, C cfg) {
        Entity entity = level.getEntity(currentAnimalId);
        if (!isAnimalReady(entity)) {
            animalCursor++;
            startNextAnimal(level, town, cfg);
            return;
        }
        npc.getLookControl().setLookAt(entity.getX(), entity.getEyeY(), entity.getZ(), 10f, 10f);

        double r = cfg.getWorkReach();
        if (npc.distanceToSqr(entity.position()) <= r * r) {
            npc.getNavigation().stop();
            animalNav = null;
            actionDelayCounter = 0;
            actionDelayTarget = getActionDelay(cfg);
            workState = WorkState.PERFORMING_ACTION;
            return;
        }

        if (animalNav == null) {
            animalNav = new GoToPosition(npc, BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()), cfg.getWalkSpeed());
        }

        boolean navDone = animalNav.tick();
        if (navDone) {
            animalNav.updateTarget(BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()));
        }
    }

    private void tickPerformingAction(ServerLevel level, Town town, C cfg) {
        Entity entity = level.getEntity(currentAnimalId);
        if (entity != null) {
            npc.getLookControl().setLookAt(entity.getX(), entity.getEyeY(), entity.getZ(), 10f, 10f);
        }
        actionDelayCounter++;
        if (actionDelayCounter < actionDelayTarget) return;
        actionDelayCounter = 0;
        if (isAnimalReady(entity)) {
            performOnAnimal(entity, cfg, level);
        }
        animalCursor++;
        currentAnimalId = null;
        startNextAnimal(level, town, cfg);
    }
}
