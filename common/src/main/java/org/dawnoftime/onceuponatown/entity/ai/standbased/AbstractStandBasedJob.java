package org.dawnoftime.onceuponatown.entity.ai.standbased;

import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.BuildingEntryNav;
import org.dawnoftime.onceuponatown.entity.ai.shared.GoToPosition;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;
import org.dawnoftime.onceuponatown.tick.StandJobController;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.StandSlot;
import org.dawnoftime.onceuponatown.town.Town;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractStandBasedJob extends AbstractNpcJob {

    private static final double STAND_ARRIVAL_RADIUS = 1.5;

    protected @Nullable StandSlot heldSlot = null;
    protected @Nullable PlacedBuilding heldBuilding = null;
    private @Nullable BuildingEntryNav entryNav = null;
    private @Nullable GoToPosition standNav = null;
    private boolean atWork = false;

    protected AbstractStandBasedJob(Npc npc) { super(npc); }

    protected abstract @Nullable StandJobConfig resolveConfig();

    public @Nullable StandJobConfig getConfig() { return resolveConfig(); }

    protected void onArrivalAtStand() {}

    protected abstract void tickAtWork(Town town, StandJobConfig cfg);

    protected boolean retainStandDuringEat() { return false; }

    protected boolean releaseStandOnResync() { return true; }

    @Override
    public long getAssignedBuildingId() {
        return heldBuilding != null ? heldBuilding.worldPos.asLong() : -1L;
    }

    @Override
    protected void dispatchFromController(ServerLevel level, Town town) {
        StandJobController.dispatchForNpc(this, town, level);
    }

    public final void receiveMainAssignment(PlacedBuilding building, StandSlot slot, ServerLevel level, Town town) {
        if (baseState == NpcBaseState.SECONDARY) activityController.cancel(npc);
        this.heldSlot = slot;
        this.heldBuilding = building;
        onControllerAssignMain(building, level, town);
        baseState = NpcBaseState.MAIN;
    }

    @Override
    protected void onControllerAssignSecondary(ActivityDef def, PlacedBuilding building, ServerLevel level, Town town) {
        activityController.startWith(town, npc, def, building);
    }

    @Override
    protected void onControllerAssignMain(PlacedBuilding building, ServerLevel level, Town town) {
        StandJobConfig cfg = resolveConfig();
        double speed = cfg != null ? cfg.getWalkSpeed() : 0.6;
        entryNav = new BuildingEntryNav(npc, BuildingEntryNav.resolveEntryPos(level, building), speed);
        standNav = null;
        atWork = false;
    }

    @Override
    public void onRemoved() {
        if (npc.level() instanceof ServerLevel level) {
            Town town = findTown(level, npc);
            if (town != null) town.releaseStand(npc.getUUID());
        }
        heldSlot = null;
        heldBuilding = null;
        atWork = false;
        entryNav = null;
        standNav = null;
        super.onRemoved();
    }

    @Override
    public void tick() {
        if (!(npc.level() instanceof ServerLevel level)) return;
        StandJobConfig cfg = resolveConfig();
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

    @Override
    protected void onEnterSleep() {
        if (heldSlot == null) return;
        ServerLevel level = npc.level() instanceof ServerLevel sl ? sl : null;
        Town town = level != null ? findTown(level, npc) : null;
        releaseAndDispatch(level, town);
    }

    @Override
    protected void onEnterEating(Town town) {
        if (!retainStandDuringEat()) {
            ServerLevel level = npc.level() instanceof ServerLevel sl ? sl : null;
            releaseAndDispatch(level, town);
        } else {
            entryNav = null;
            standNav = null;
        }
    }

    @Override
    protected void onResync(@Nullable Town town) {
        if (releaseStandOnResync()) {
            ServerLevel level = npc.level() instanceof ServerLevel sl ? sl : null;
            releaseAndDispatch(level, town);
        } else {
            entryNav = null;
            standNav = null;
        }
        atWork = false;
    }

    @Override
    protected void onResetWork() {
        atWork = false;
        entryNav = null;
        standNav = null;
    }

    private void tickMain(ServerLevel level, Town town, StandJobConfig cfg) {
        if (heldSlot == null) { releaseAndDispatch(level, town); return; }
        if (entryNav != null) {
            if (!entryNav.tick()) return;
            entryNav = null;
            standNav = new GoToPosition(npc, heldSlot.position, cfg.getWalkSpeed(), STAND_ARRIVAL_RADIUS);
        }
        if (standNav != null) {
            if (!standNav.tick()) return;
            standNav = null;
            atWork = true;
            onArrivalAtStand();
        }
        if (atWork) tickAtWork(town, cfg);
    }

    protected final void releaseAndDispatch(ServerLevel level, @Nullable Town town) {
        if (heldSlot != null && town != null) town.releaseStand(npc.getUUID());
        heldSlot = null;
        heldBuilding = null;
        atWork = false;
        entryNav = null;
        standNav = null;
        if (level != null && town != null) {
            StandJobController.dispatchForNpc(this, town, level);
        } else {
            enterWander();
        }
    }
}
