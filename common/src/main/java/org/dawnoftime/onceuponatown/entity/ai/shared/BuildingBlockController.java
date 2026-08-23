package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Unified controller for the "navigate to building -> scan for block -> approach -> perform" pattern.
 * Mirrors NpcSleepController but drives work interactions instead of sleep.
 *
 * Usage:
 *   1. Call tick() every NPC tick, passing the eligible building list, walk speed, and a scanner lambda.
 *   2. When tick() returns PERFORMING, run job-specific interaction logic.
 *   3. When the work cycle ends, call advanceCursor() to move to the next building and reset.
 *   4. On sleep interrupt, call reset() to return to IDLE.
 */
public class BuildingBlockController {

    public enum Result { SEARCHING, PERFORMING, NOT_FOUND }

    @FunctionalInterface
    public interface BlockScanner {
        /**
         * Called once the NPC has arrived at the building center.
         * Return null  -> no work available here (controller advances cursor, returns NOT_FOUND).
         * Return the building center BlockPos -> work here, no per-block approach needed (PERFORMING immediately).
         * Return any other BlockPos -> navigate to that block (APPROACHING phase), then PERFORMING.
         */
        @Nullable BlockPos scan(ServerLevel level, PlacedBuilding building);
    }

    private enum Phase { IDLE, NAVIGATING, PERFORMING }

    private final Npc npc;
    private final double blockArrivalRadius;

    private Phase phase = Phase.IDLE;
    private int buildingCursor = 0;
    private PlacedBuilding currentBuilding = null;
    private GoToPosition nav = null;
    private BlockPos targetBlockPos = null;

    public BuildingBlockController(Npc npc, double blockArrivalRadius) {
        this.npc = npc;
        this.blockArrivalRadius = blockArrivalRadius;
    }

    public Result tick(ServerLevel level, Town town, List<String> workBuildings, double walkSpeed, BlockScanner scanner) {
        List<PlacedBuilding> eligible = town.getBuildings().stream()
            .filter(b -> workBuildings.contains(b.defId))
            .toList();
        if (eligible.isEmpty()) return Result.NOT_FOUND;

        if (phase == Phase.IDLE) {
            PlacedBuilding building = eligible.get(buildingCursor % eligible.size());
            if (building.bb == null) return Result.NOT_FOUND;
            BlockPos scanned = scanner.scan(level, building);
            if (scanned == null) {
                advanceCursor(town, workBuildings);
                return Result.NOT_FOUND;
            }
            currentBuilding = building;
            targetBlockPos = scanned;
            // Scanner returns building center when the job handles its own per-target navigation.
            BlockPos center = new BlockPos(
                (building.bb.minX() + building.bb.maxX()) / 2,
                building.bb.minY(),
                (building.bb.minZ() + building.bb.maxZ()) / 2
            );
            if (scanned.equals(center)) {
                phase = Phase.PERFORMING;
                return Result.PERFORMING;
            }
            BlockPos standingPos = StandingPositionFinder.find(level, scanned, blockArrivalRadius);
            BlockPos navTarget = standingPos != null ? standingPos : scanned;
            nav = new GoToPosition(npc, navTarget, walkSpeed, blockArrivalRadius);
            phase = Phase.NAVIGATING;
            return Result.SEARCHING;
        }

        if (phase == Phase.NAVIGATING) {
            if (!nav.tick()) return Result.SEARCHING;
            nav = null;
            npc.getNavigation().stop();
            phase = Phase.PERFORMING;
            return Result.PERFORMING;
        }

        // Phase.PERFORMING: caller drives interaction logic until it calls advanceCursor or reset.
        return Result.PERFORMING;
    }

    // Convenience overload: accepts a WorkConfig instead of raw list + speed.
    public Result tick(ServerLevel level, Town town, WorkConfig cfg, BlockScanner scanner) {
        return tick(level, town, cfg.getWorkBuildings(), cfg.getWalkSpeed(), scanner);
    }

    /** Returns the target block position when in PERFORMING phase, or null for center-only activities. */
    @Nullable
    public BlockPos getTargetBlockPos() { return targetBlockPos; }

    /** Returns the building being navigated or worked, or null when idle. */
    @Nullable
    public PlacedBuilding getCurrentBuilding() { return currentBuilding; }

    /** Resets to IDLE and clears all navigation. Call when interrupted (e.g., sleep trigger). */
    public void reset() {
        phase = Phase.IDLE;
        currentBuilding = null;
        nav = null;
        targetBlockPos = null;
    }

    // Convenience overload: accepts a WorkConfig instead of a raw building list.
    public void advanceCursor(Town town, WorkConfig cfg) {
        advanceCursor(town, cfg.getWorkBuildings());
    }

    /**
     * Advances the round-robin cursor to the next eligible building, then resets.
     * Call after successfully completing work in the current building, or to skip to the next one.
     */
    public void advanceCursor(Town town, List<String> workBuildings) {
        List<PlacedBuilding> eligible = town.getBuildings().stream()
            .filter(b -> workBuildings.contains(b.defId))
            .toList();
        if (!eligible.isEmpty()) {
            buildingCursor = (buildingCursor + 1) % eligible.size();
        }
        reset();
    }
}
