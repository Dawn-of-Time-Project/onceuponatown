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

    private enum Phase { IDLE, ENTERING_BUILDING, NAVIGATING, PERFORMING }

    private final Npc npc;

    private Phase phase = Phase.IDLE;
    private List<PlacedBuilding> cachedEligible = null;
    private int buildingCursor = 0;
    private @Nullable PlacedBuilding currentBuilding = null;
    private @Nullable BuildingEntryNav entryNav = null;
    private @Nullable GoToPosition nav = null;
    private @Nullable BlockPos targetBlockPos = null;

    public BuildingBlockController(Npc npc) {
        this.npc = npc;
    }

    public Result tick(ServerLevel level, Town town, List<String> workBuildings, double walkSpeed, double workReach, BlockScanner scanner) {
        List<PlacedBuilding> eligible = getEligibleBuildings(town, workBuildings);
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
            entryNav = new BuildingEntryNav(npc, BuildingEntryNav.resolveEntryPos(level, building), walkSpeed);
            phase = Phase.ENTERING_BUILDING;
            return Result.SEARCHING;
        }

        if (phase == Phase.ENTERING_BUILDING) {
            if (!entryNav.tick()) return Result.SEARCHING;
            entryNav = null;
            // Scanner returns building center when the job handles its own per-target navigation.
            BlockPos center = new BlockPos(
                (currentBuilding.bb.minX() + currentBuilding.bb.maxX()) / 2,
                currentBuilding.bb.minY(),
                (currentBuilding.bb.minZ() + currentBuilding.bb.maxZ()) / 2
            );
            if (targetBlockPos.equals(center)) {
                phase = Phase.PERFORMING;
                return Result.PERFORMING;
            }
            nav = new GoToPosition(npc, targetBlockPos, walkSpeed, workReach);
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

    // Convenience overload: accepts a WorkConfig instead of raw list + speed + radius.
    public Result tick(ServerLevel level, Town town, WorkConfig cfg, BlockScanner scanner) {
        return tick(level, town, cfg.getWorkBuildings(), cfg.getWalkSpeed(), cfg.getWorkReach(), scanner);
    }

    /** Returns the target block position when in PERFORMING phase, or null for center-only activities. */
    @Nullable
    public BlockPos getTargetBlockPos() { return targetBlockPos; }

    /** Returns the building being navigated or worked, or null when idle. */
    @Nullable
    public PlacedBuilding getCurrentBuilding() { return currentBuilding; }

    /** Returns true when the controller is navigating or performing (not waiting for a new scan). */
    public boolean isActive() { return phase != Phase.IDLE; }

    /** Resets to IDLE and clears all navigation. Call when interrupted (e.g., sleep trigger). */
    public void reset() {
        phase = Phase.IDLE;
        currentBuilding = null;
        entryNav = null;
        nav = null;
        targetBlockPos = null;
        cachedEligible = null;
    }

    // Starts a work cycle directly for a given building and optional target block,
    // bypassing the cursor rotation. Used by controllers that already selected the building.
    public void startFor(PlacedBuilding building, ServerLevel level, double walkSpeed, @Nullable BlockPos targetBlock) {
        reset();
        this.currentBuilding = building;
        if (targetBlock != null) this.targetBlockPos = targetBlock;
        this.entryNav = new BuildingEntryNav(npc, BuildingEntryNav.resolveEntryPos(level, building), walkSpeed);
        this.phase = Phase.ENTERING_BUILDING;
    }

    // Registers the building without any navigation — goes straight to PERFORMING.
    // Used when the job handles its own block-level navigation (e.g. farmers navigating to crops).
    public void startFor(PlacedBuilding building, @Nullable BlockPos targetBlock) {
        reset();
        this.currentBuilding = building;
        if (targetBlock != null) this.targetBlockPos = targetBlock;
        this.phase = Phase.PERFORMING;
    }

    // Convenience overload: accepts a WorkConfig instead of a raw building list.
    public void advanceCursor(Town town, WorkConfig cfg) {
        advanceCursor(town, cfg.getWorkBuildings());
    }

    // Resets state when the building was selected externally (e.g., by a job controller).
    public void advanceCursor() {
        reset();
    }

    /**
     * Advances the round-robin cursor to the next eligible building, then resets.
     * Call after successfully completing work in the current building, or to skip to the next one.
     */
    public void advanceCursor(Town town, List<String> workBuildings) {
        List<PlacedBuilding> eligible = getEligibleBuildings(town, workBuildings);
        if (!eligible.isEmpty()) {
            buildingCursor = (buildingCursor + 1) % eligible.size();
        }
        reset();
    }

    private List<PlacedBuilding> getEligibleBuildings(Town town, List<String> workBuildings) {
        if (cachedEligible == null)
            cachedEligible = town.getBuildings().stream()
                .filter(b -> workBuildings.contains(b.defId)).toList();
        return cachedEligible;
    }
}
