package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.AbstractNpcJob;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

public class NpcSleepController {

    private final Npc npc;
    // Per-NPC offsets from NpcTimingProfile: stagger bedtime and wakeup independently
    // so NPCs don't all transition at the same tick.
    private final int sleepOffset;
    private final int wakeOffset;
    private BlockPos sleepBedPos     = null;
    private BlockPos sleepStandPos   = null;
    private BuildingEntryNav sleepEntryNav = null;
    private GoToPosition sleepGoTo   = null;

    public NpcSleepController(Npc npc, NpcTimingProfile timing) {
        this.npc = npc;
        this.sleepOffset = timing.sleepOffset;
        this.wakeOffset  = timing.wakeOffset;
    }

    // Returns true when the current daytime falls inside the configured sleep window.
    // Handles midnight wrap-around: e.g., bedtime=13000 wakeup=1000 spans past midnight.
    // sleepOffset staggers each NPC's bedtime and wakeOffset staggers their wakeup
    // so they don't all transition at the same moment.
    public boolean isSleepTime(long dayTime, SleepConfig cfg) {
        int sleep = cfg.getBedtime() + sleepOffset;
        int wake  = cfg.getWakeupTime() + wakeOffset;
        if (sleep > wake) return dayTime >= sleep || dayTime < wake;
        return dayTime >= sleep && dayTime < wake;
    }

    // Clears all navigation state. Called on enterSleep and on wakeup.
    public void reset() {
        sleepBedPos    = null;
        sleepStandPos  = null;
        sleepEntryNav  = null;
        sleepGoTo      = null;
    }

    public enum SleepCheck { NONE, RESYNC, TRIGGER }

    // Centralizes the per-tick sleep resync and trigger logic shared by all jobs.
    // RESYNC:  NPC is already in bed (reloaded from NBT) but job state is not SLEEPING.
    //          Caller sets current = SLEEPING with no teardown -- NPC is already resting.
    // TRIGGER: Sleep window just opened. Caller must call its own enterSleep() for full teardown.
    // NONE:    No action needed.
    public SleepCheck checkTick(long dayTime, SleepConfig cfg, boolean inSleepState) {
        if (npc.isSleeping() && !inSleepState) {
            if (cfg.getBedtime() >= 0 && isSleepTime(dayTime, cfg)) return SleepCheck.RESYNC;
            npc.stopSleeping();
            npc.setSuppressLookAtPlayer(false);
            return SleepCheck.NONE;
        }
        if (!inSleepState && cfg.getBedtime() >= 0 && isSleepTime(dayTime, cfg)) return SleepCheck.TRIGGER;
        return SleepCheck.NONE;
    }

    // Drives the full sleep lifecycle each tick.
    // Returns true  -> caller stays in SLEEPING state.
    // Returns false -> sleep window ended, caller must switch to IDLE.
    public boolean tick(ServerLevel level, Town town, SleepConfig cfg) {
        long dayTime = level.getDayTime() % AbstractNpcJob.DAY_TICKS;

        if (!isSleepTime(dayTime, cfg)) {
            if (npc.isSleeping()) {
                npc.stopSleeping();
                if (sleepStandPos != null) {
                    npc.teleportTo(sleepStandPos.getX() + 0.5, sleepStandPos.getY(), sleepStandPos.getZ() + 0.5);
                }
            }
            npc.setSuppressLookAtPlayer(false);
            reset();
            return false;
        }

        // Suppress look-at-player for the entire sleep phase (navigation to bed + sleeping).
        npc.setSuppressLookAtPlayer(true);

        // NPC already lying in bed: nothing to do until the wake condition above fires.
        if (npc.isSleeping()) return true;

        // On first tick (or after a retry): locate rest building, bed HEAD, and the
        // standing position adjacent to the bed FOOT to navigate directly there.
        if (sleepBedPos == null) {
            if (town == null) return true;
            PlacedBuilding restBuilding = findRestBuilding(town, cfg);
            if (restBuilding == null || restBuilding.bb == null) return true;
            sleepBedPos = scanBedInBox(level, restBuilding.bb);
            if (sleepBedPos == null) return true;

            BlockState headState = level.getBlockState(sleepBedPos);
            Direction facing = headState.getValue(BedBlock.FACING); // foot -> head direction
            BlockPos footPos = sleepBedPos.relative(facing.getOpposite());
            sleepStandPos = findBedStandingPos(level, footPos, facing);
            if (sleepStandPos == null) {
                // No walkable adjacent position found around the foot; retry next tick.
                sleepBedPos = null;
                return true;
            }
            sleepEntryNav = new BuildingEntryNav(npc, BuildingEntryNav.resolveEntryPos(level, restBuilding), cfg.getWalkSpeed());
        }

        if (sleepEntryNav != null) {
            if (!sleepEntryNav.tick()) return true;
            sleepEntryNav = null;
            sleepGoTo = new GoToPosition(npc, sleepStandPos, cfg.getWalkSpeed());
        }

        // sleepBedPos is set but sleepGoTo is null: navigation completed but startSleeping didn't
        // stick (e.g. the bed was removed or the sleep call failed silently). Reset and retry.
        if (sleepGoTo == null) {
            sleepBedPos = null;
            return true;
        }

        if (sleepGoTo.tick()) {
            sleepGoTo = null;
            BlockState headState = level.getBlockState(sleepBedPos);
            if (!(headState.getBlock() instanceof BedBlock)) {
                // Bed was removed between navigation start and arrival: retry next cycle.
                sleepBedPos = null;
                return true;
            }
            // Disable physics for the TP so BedBlock.fallOn() cannot fire before vanilla takes over sleeping position.
            npc.noPhysics = true;
            npc.teleportTo(sleepBedPos.getX() + 0.5, sleepBedPos.getY() + 0.6875, sleepBedPos.getZ() + 0.5);
            npc.startSleeping(sleepBedPos);
            npc.noPhysics = false;
        }

        return true;
    }

    // Tries 3 positions around the bed foot in priority order (open end, right side, left side).
    // Returns the first position where the NPC can stand, or null if all are blocked.
    private static BlockPos findBedStandingPos(ServerLevel level, BlockPos footPos, Direction facing) {
        BlockPos[] candidates = {
            footPos.relative(facing.getOpposite()),        // open end (away from head)
            footPos.relative(facing.getClockWise()),       // right side of foot
            footPos.relative(facing.getCounterClockWise()) // left side of foot
        };
        for (BlockPos pos : candidates) {
            BlockPos below = pos.below();
            if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
            if (level.getBlockState(pos).isSolid()) continue;
            if (level.getBlockState(pos.above()).isSolid()) continue;
            return pos;
        }
        return null;
    }

    // Returns the highest-priority rest building present in the village.
    // The first entry in restBuildings is the primary: if it exists in the village, it always wins.
    // Subsequent entries are fallbacks tried in order when the primary is absent.
    private PlacedBuilding findRestBuilding(Town town, SleepConfig cfg) {
        for (String buildingId : cfg.getRestBuildings()) {
            for (PlacedBuilding building : town.getBuildings()) {
                if (buildingId.equals(building.defId) && building.bb != null) {
                    return building;
                }
            }
        }
        return null;
    }

    // Scans a bounding box for a BedBlock HEAD part. startSleeping requires the head position.
    private static BlockPos scanBedInBox(ServerLevel level, BoundingBox bb) {
        for (int x = bb.minX(); x <= bb.maxX(); x++) {
            for (int y = bb.minY(); y <= bb.maxY(); y++) {
                for (int z = bb.minZ(); z <= bb.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BedBlock
                            && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
