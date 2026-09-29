package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.datapack.BuilderConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.tick.BuilderJobController;
import org.dawnoftime.onceuponatown.town.ActiveBuildState;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.QueueEntry;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TownLogEntry;
import org.dawnoftime.onceuponatown.town.TownLogEntry.TownLogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuilderJob extends AbstractNpcJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuilderJob.class);

    private BuildTask activeBuild = null;
    private QueueEntry activeQueueEntry = null;

    public BuilderJob(Npc npc) {
        super(npc);
    }

    @Override
    public String getJobId() { return "builder"; }

    @Override
    protected void dispatchFromController(ServerLevel level, Town town) {
        BuilderJobController.dispatchForNpc(this, town, level);
    }

    @Override
    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        activityController.startWith(town, npc, def, building);
    }

    public void receiveAssignment(BuildTask goal, QueueEntry entry) {
        if (baseState == NpcBaseState.SECONDARY) {
            activityController.cancel(npc);
        }
        this.activeBuild = goal;
        this.activeQueueEntry = entry;
        baseState = NpcBaseState.MAIN;
    }

    @Override
    protected void onResync(@org.jetbrains.annotations.Nullable Town town) {
        if (activeBuild != null) {
            LOGGER.warn("[OUAT-FREEZE] Builder {} RESYNC to SLEEPING with activeBuild active (entry={}), activeBuild NOT cleared",
                npc.getUUID(), activeQueueEntry != null ? activeQueueEntry.getClass().getSimpleName() : "null");
        }
    }

    @Override
    protected void onEnterSleep() {
        if (activeBuild != null && npc.level() instanceof ServerLevel sl) {
            BlockPos lockPos = null;
            if (activeQueueEntry instanceof QueueEntry.Upgrade u) lockPos = u.buildingWorldPos();
            else if (activeQueueEntry instanceof QueueEntry.Repair r) lockPos = r.buildingWorldPos();
            if (lockPos != null) {
                Town t = findTown(sl, npc);
                if (t != null) {
                    t.removeUnderUpgrade(lockPos);
                    LevelTowns.get(sl).markDirty();
                }
            }
        }
    }

    @Override
    protected void onResetWork() {
        activeBuild = null;
        activeQueueEntry = null;
    }

    @Override
    protected GoToPosition buildMealNavigation(Town town, double walkSpeed, double arrivalRadius) {
        if (activeBuild != null) {
            int mySlot = town.getNpcSlot("builder", npc.getUUID());
            ActiveBuildState saved = mySlot >= 0 ? town.getActiveBuild(mySlot) : null;
            if (saved != null && !BlockPos.ZERO.equals(saved.entryConnectorPos())) {
                return new GoToPosition(npc, saved.entryConnectorPos().above(), walkSpeed, MEAL_ARRIVAL_RADIUS);
            }
        }
        return null;
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        BuilderConfigDataHandler.Config cfg = BuilderConfigDataHandler.get();
        if (cfg == null) return;

        Town town = findTown(level, npc);
        tickSharedPreamble(level, town, cfg);
        if (town == null) return;

        npc.setSuppressLookAtPlayer(baseState == NpcBaseState.SECONDARY || baseState == NpcBaseState.MAIN);

        switch (baseState) {
            case SLEEPING  -> tickSleepingBase(level, town, cfg);
            case EATING    -> tickEatingBase(level, town);
            case SECONDARY -> tickSecondaryBase(level, town, cfg);
            case MAIN      -> tickMain(level, town);
            case WANDER    -> maybeWander();
        }
    }

    public void onRemoved() {
        if (!(npc.level() instanceof ServerLevel sl)) return;
        if (!(activeQueueEntry instanceof QueueEntry.Upgrade u)) return;
        Town town = findTown(sl, npc);
        if (town == null) return;
        town.removeUnderUpgrade(u.buildingWorldPos());
        LevelTowns.get(sl).markDirty();
    }

    private void tickMain(ServerLevel level, Town town) {
        if (activeBuild == null) { enterWander(); return; }
        if (activeBuild.tick()) {
            BlockPos completedPos = activeBuild.getFinalPlacementPos();
            boolean failed = activeBuild.isFailed();
            QueueEntry completedEntry = activeQueueEntry;
            activeQueueEntry = null;
            activeBuild = null;

            int mySlot = town.getNpcSlot("builder", npc.getUUID());
            if (mySlot >= 0) town.clearActiveBuild(mySlot);

            if (!failed && completedEntry != null) {
                String placedDefId = completedEntry instanceof QueueEntry.NewBuild nb ? nb.defId() : null;
                TownLogType doneType = completedEntry instanceof QueueEntry.Upgrade ? TownLogType.UPGRADE_DONE : TownLogType.BUILD_DONE;
                String doneDefId = completedEntry instanceof QueueEntry.NewBuild nb2 ? nb2.defId()
                    : completedEntry instanceof QueueEntry.Upgrade u2 ? u2.defId() : "";
                town.consumeQueueEntry(completedEntry);
                if (placedDefId != null) town.onBuildingPlaced(placedDefId);
                TownLogEntry doneLog = new TownLogEntry(doneType, doneDefId, level.getGameTime());
                town.addLogEntry(doneLog);
                NetworkHelper.pushLogEntryToWatchers(level, town, npc.getTownAnchorPos(), doneLog);
                LevelTowns.get(level).markDirty();
            }

            town.removeUnderConstruction(completedPos);
            town.removeUnderUpgrade(completedPos);
            BlockPos anchor = npc.getTownAnchorPos();
            NetworkHelper.pushBuildingListToWatchers(level, town, anchor);
            NetworkHelper.pushStockToWatchers(level, town, anchor);
            if (completedEntry instanceof QueueEntry.NewBuild nb) {
                org.dawnoftime.onceuponatown.datapack.BuildingDataHandler.get(nb.defId()).ifPresent(def -> {
                    if (def.residents > 0 || def.spawnsNpcJob != null) {
                        NetworkHelper.pushCitizenUpdateToWatchers(level, town, anchor);
                    }
                });
            }

            // Synchronous re-dispatch instead of dirty-flag + wander.
            dispatchFromController(level, town);
        }
    }

}
