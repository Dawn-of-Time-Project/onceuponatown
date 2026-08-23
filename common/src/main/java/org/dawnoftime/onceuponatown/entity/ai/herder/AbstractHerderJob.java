package org.dawnoftime.onceuponatown.entity.ai.herder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.shared.*;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared base for animal-herder jobs (cowherd, shepherd, swineherd).
 * Follows the same uniform pattern as all other jobs:
 *   IDLE -> scan building for ready animals -> navigate to animal -> perform action.
 * Moving targets are handled by periodically refreshing the navigation destination.
 */
public abstract class AbstractHerderJob<C extends SleepConfig & WorkConfig & HerderConfig>
        extends AbstractNpcJob {

    protected enum State { IDLE, APPROACHING_ANIMAL, PERFORMING_ACTION, SLEEPING, ACTIVITY }

    protected State current = State.IDLE;

    protected final SecondaryActivityController activityController = new SecondaryActivityController();

    protected List<UUID> animalWorkList = new ArrayList<>();
    protected int animalCursor = 0;
    protected int actionDelayCounter = 0;
    protected UUID currentAnimalId = null;
    private GoToPosition animalNav = null;
    private int animalNavRefreshCooldown = 0;

    protected AbstractHerderJob(Npc npc) {
        super(npc);
    }

    // Returns the loaded config, or null if unavailable.
    protected abstract C getConfig();

    // Scans one building for ready animals. Also equips the appropriate item in hand.
    // Returns an empty list if no work is available here.
    protected abstract List<UUID> scanBuilding(ServerLevel level, PlacedBuilding building, C cfg);

    // Returns true if the entity is still eligible for the current action.
    protected abstract boolean isAnimalReady(@Nullable Entity entity);

    // Performs the primary action (shear, breed, etc.) on the target animal.
    protected abstract void performOnAnimal(Entity animal, C cfg, ServerLevel level);

    // Quick check: returns true if any work exists across all work buildings right now.
    protected abstract boolean hasAnyReadyAnimals(ServerLevel level, Town town, C cfg);

    // Returns the delay in ticks before performing the action. Override for mode-specific delays.
    protected int getActionDelay(C cfg) {
        return cfg.getActionDelayTicks();
    }

    protected static AABB buildingAabb(PlacedBuilding building) {
        return new AABB(
            building.bb.minX(), building.bb.minY(), building.bb.minZ(),
            building.bb.maxX() + 1, building.bb.maxY() + 1, building.bb.maxZ() + 1
        );
    }

    @Override
    public final void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        C cfg = getConfig();
        if (cfg == null) return;
        Town town = findTown(level, npc);
        if (town == null) return;

        long dayTime = level.getDayTime() % 24000;
        NpcSleepController.SleepCheck sc = sleepController.checkTick(dayTime, cfg, current == State.SLEEPING);
        if (sc == NpcSleepController.SleepCheck.RESYNC)  current = State.SLEEPING;
        if (sc == NpcSleepController.SleepCheck.TRIGGER) enterSleep();

        npc.setSuppressLookAtPlayer(current != State.IDLE && current != State.SLEEPING);

        switch (current) {
            case IDLE               -> tickIdle(level, town, cfg);
            case APPROACHING_ANIMAL -> tickApproachingAnimal(level, town, cfg);
            case PERFORMING_ACTION  -> tickPerformingAction(level, town, cfg);
            case SLEEPING           -> { if (!sleepController.tick(level, town, cfg)) current = State.IDLE; }
            case ACTIVITY           -> tickActivity(level, town, cfg);
        }
    }

    private void tickIdle(ServerLevel level, Town town, C cfg) {
        if (!hasAnyReadyAnimals(level, town, cfg)) {
            if (activityController.tryStart(town, npc, cfg.getSecondaryActivities())) {
                current = State.ACTIVITY;
            } else {
                maybeWander();
            }
            return;
        }
        for (PlacedBuilding building : town.getBuildings()) {
            if (!cfg.getWorkBuildings().contains(building.defId) || building.bb == null) continue;
            List<UUID> candidates = scanBuilding(level, building, cfg);
            if (candidates.isEmpty()) continue;
            animalWorkList = candidates;
            animalCursor = 0;
            startNextAnimal(level, town, cfg);
            return;
        }
        maybeWander();
    }

    private void tickActivity(ServerLevel level, Town town, C cfg) {
        if (hasAnyReadyAnimals(level, town, cfg)) {
            activityController.cancel(npc);
            current = State.IDLE;
            return;
        }
        SecondaryActivityController.Result r = activityController.tick(level, town, npc, cfg.getWalkSpeed());
        if (r == SecondaryActivityController.Result.NOT_FOUND) current = State.IDLE;
    }

    // Walks through animalWorkList from animalCursor, starts navigation toward the first ready animal.
    // Returns to IDLE when the list is exhausted (no cursor — IDLE rescans naturally next tick).
    protected void startNextAnimal(ServerLevel level, Town town, C cfg) {
        while (animalCursor < animalWorkList.size()) {
            UUID id = animalWorkList.get(animalCursor);
            Entity entity = level.getEntity(id);
            if (isAnimalReady(entity)) {
                currentAnimalId = id;
                BlockPos animalBlock = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
                BlockPos standingPos = StandingPositionFinder.find(level, animalBlock, 2.0);
                BlockPos navTarget = standingPos != null ? standingPos : animalBlock;
                animalNav = new GoToPosition(npc, navTarget, cfg.getWalkSpeed(), 1.0);
                animalNavRefreshCooldown = 0;
                current = State.APPROACHING_ANIMAL;
                return;
            }
            animalCursor++;
        }
        npc.freeHands();
        current = State.IDLE;
    }

    private void tickApproachingAnimal(ServerLevel level, Town town, C cfg) {
        Entity entity = level.getEntity(currentAnimalId);
        if (!isAnimalReady(entity)) {
            animalCursor++;
            startNextAnimal(level, town, cfg);
            return;
        }
        npc.getLookControl().setLookAt(entity.getX(), entity.getEyeY(), entity.getZ(), 10f, 10f);

        // Arrival check on the live entity position (not a static block).
        if (npc.distanceToSqr(entity.position()) <= 16.0) {
            npc.getNavigation().stop();
            animalNav = null;
            actionDelayCounter = 0;
            current = State.PERFORMING_ACTION;
            return;
        }

        // Refresh navigation target every 80 ticks because animals move.
        animalNavRefreshCooldown++;
        if (animalNav == null || animalNavRefreshCooldown >= 80) {
            animalNavRefreshCooldown = 0;
            BlockPos animalBlock = BlockPos.containing(entity.getX(), entity.getY(), entity.getZ());
            BlockPos standingPos = StandingPositionFinder.find(level, animalBlock, 2.0);
            BlockPos navTarget = standingPos != null ? standingPos : animalBlock;
            animalNav = new GoToPosition(npc, navTarget, cfg.getWalkSpeed(), 1.0);
        }

        animalNav.tick();
    }

    private void tickPerformingAction(ServerLevel level, Town town, C cfg) {
        Entity entity = level.getEntity(currentAnimalId);
        if (entity != null) {
            npc.getLookControl().setLookAt(entity.getX(), entity.getEyeY(), entity.getZ(), 10f, 10f);
        }
        actionDelayCounter++;
        if (actionDelayCounter < getActionDelay(cfg)) return;
        actionDelayCounter = 0;
        if (isAnimalReady(entity)) {
            performOnAnimal(entity, cfg, level);
        }
        animalCursor++;
        currentAnimalId = null;
        startNextAnimal(level, town, cfg);
    }

    protected void enterSleep() {
        if (current == State.ACTIVITY) activityController.cancel(npc);
        npc.getNavigation().stop();
        npc.freeHands();
        sleepController.reset();
        animalWorkList.clear();
        animalCursor = 0;
        currentAnimalId = null;
        animalNav = null;
        animalNavRefreshCooldown = 0;
        current = State.SLEEPING;
    }
}
