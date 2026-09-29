package org.dawnoftime.onceuponatown.tick;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.Constants;
import org.dawnoftime.onceuponatown.building.schematic.ConnectorReader;
import org.dawnoftime.onceuponatown.entity.ai.shared.ConnectionPointYResolver;
import org.dawnoftime.onceuponatown.building.schematic.JigsawConnector;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBounds;
import org.dawnoftime.onceuponatown.building.schematic.TerrainMatchedPlacer;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuildGoal;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuildTask;
import org.dawnoftime.onceuponatown.entity.ai.builder.BuilderJob;
import org.dawnoftime.onceuponatown.entity.ai.builder.NewBuildAction;
import org.dawnoftime.onceuponatown.entity.ai.builder.RepairAction;
import org.dawnoftime.onceuponatown.entity.ai.builder.UpgradeAction;
import org.dawnoftime.onceuponatown.network.NetworkHelper;
import org.dawnoftime.onceuponatown.town.ActiveBuildState;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.QueueEntry;
import org.dawnoftime.onceuponatown.town.Town;
import org.dawnoftime.onceuponatown.town.TownLogEntry;
import org.dawnoftime.onceuponatown.town.TownLogEntry.TownLogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class BuildWorkManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BuildWorkManager.class);
    private static final Random RANDOM = new Random();

    // Per-town anchor: defIds already warned as blocked this session.
    private static final Map<Long, Set<String>> warnedByTown = new HashMap<>();

    private enum PlaceScanResult { STARTED, BLOCKED, NOTHING }

    private enum FailReason {
        NO_COMPATIBLE_CONNECTOR,
        BOUNDING_BOX_OVERLAP,
        WATER_IN_FOOTPRINT
    }

    private record PlacementSuccess(BlockPos pos, Rotation rotation, BlockPos entryConnectorWorldPos, BoundingBox bb) {}

    private record PlacementOutcome(PlacementSuccess success, FailReason failure) {
        static PlacementOutcome ok(BlockPos pos, Rotation rot, BlockPos ep, BoundingBox bb) {
            return new PlacementOutcome(new PlacementSuccess(pos, rot, ep, bb), null);
        }
        static PlacementOutcome fail(FailReason r) { return new PlacementOutcome(null, r); }
        boolean succeeded() { return success != null; }
    }

    // Attempts to assign one queue entry or street expansion to this builder.
    // Returns true if an assignment was made.
    public static boolean tryAssignOne(Town town, ServerLevel level, Npc npc, BuilderJob job,
                                       BlockPos anchorPos, long gameTime) {
        List<ConnectionPoint> freePoints = town.getAvailableConnectionPoints();
        if (freePoints.isEmpty()) return false;

        List<BoundingBox> occupied = town.getOccupiedBoxes();
        long anchorKey = anchorPos.asLong();
        Set<String> warned = warnedByTown.computeIfAbsent(anchorKey, k -> new HashSet<>());

        // Sync warned set with current queue.
        Set<String> activeDefIds = new HashSet<>();
        for (QueueEntry e : town.getConstructionQueue()) {
            if (e instanceof QueueEntry.NewBuild nb) activeDefIds.add(nb.defId());
        }
        warned.retainAll(activeDefIds);

        PlaceScanResult result = tryAssignQueueEntry(
            town, level, npc, job, freePoints, occupied, anchorPos, gameTime, warned);
        if (result == PlaceScanResult.STARTED) return true;

        if (result == PlaceScanResult.BLOCKED) {
            return tryAssignStreetExpansion(
                town, level, npc, job, freePoints, occupied, anchorPos, gameTime);
        }

        if (town.getConstructionQueue().isEmpty()) warnedByTown.remove(anchorKey);
        return false;
    }

    public static void tryResume(Town town, ServerLevel level, BlockPos anchorPos, long gameTime) {
        // Snapshot avoids ConcurrentModificationException when clearActiveBuild is called below.
        List<Map.Entry<Integer, ActiveBuildState>> snapshot = new ArrayList<>(town.getActiveBuilds().entrySet());

        for (Map.Entry<Integer, ActiveBuildState> e : snapshot) {
            int slot = e.getKey();
            ActiveBuildState saved = e.getValue();

            UUID builderId = town.getNpcAtSlot("builder", slot);
            if (builderId == null) {
                LOGGER.debug("[OUAT-RESUME] slot={} no builderUUID registered", slot);
                continue;
            }

            net.minecraft.world.entity.Entity entity = level.getEntity(builderId);
            if (!(entity instanceof Npc npc)) {
                LOGGER.debug("[OUAT-RESUME] slot={} builder {} not loaded in level", slot, builderId);
                continue;
            }

            BuilderJob job = npc.getBuilderJob();
            if (job == null || !job.isAvailableForAssignment()) {
                if (job != null) {
                    LOGGER.debug("[OUAT-RESUME] slot={} builder {} not ready  baseState={}",
                        slot, builderId, job.getBaseState());
                }
                continue;
            }

            BuildGoal resumed = BuildGoal.fromActiveBuildState(saved, npc, town, level);
            if (resumed == null) {
                LOGGER.warn("[OUAT-RESUME] slot={} fromActiveBuildState null for def={} at {}  clearing slot",
                    slot, saved.defId(), saved.placementPos());
                town.clearActiveBuild(slot);
                LevelTowns.get(level).markDirty();
                continue;
            }

            QueueEntry entry = findEntryForResume(town, saved);
            if (entry == null && saved.queueEntryId() >= 0) {
                    town.clearActiveBuild(slot);
                LevelTowns.get(level).markDirty();
                continue;
            }

            // Re-mark upgrade as in-progress so no other builder picks it up.
            if (entry instanceof QueueEntry.Upgrade u) {
                town.addUnderUpgrade(u.buildingWorldPos());
            }

            job.receiveAssignment(resumed, entry);
        }
    }

    private static QueueEntry findEntryForResume(Town town, ActiveBuildState saved) {
        if (saved.queueEntryId() < 0) return null; // street build, no queue entry
        int idx = town.findQueueIndex(saved.queueEntryId());
        if (idx < 0) return null;
        return town.getConstructionQueue().get(idx);
    }

    private static PlaceScanResult tryAssignQueueEntry(
            Town town, ServerLevel level, Npc npc, BuilderJob job,
            List<ConnectionPoint> freePoints, List<BoundingBox> occupied,
            BlockPos anchorPos, long gameTime, Set<String> warned) {

        List<QueueEntry> queue = town.getConstructionQueue();
        if (queue.isEmpty()) return PlaceScanResult.NOTHING;

        boolean anyBlocked = false;

        for (int i = 0; i < queue.size(); i++) {
            QueueEntry entry = queue.get(i);

            if (entry instanceof QueueEntry.NewBuild nb && nb.planned()) continue;
            if (entry instanceof QueueEntry.Upgrade u && u.planned()) continue;

            if (entry instanceof QueueEntry.Upgrade upgradeEntry) {
                if (town.isUnderUpgrade(upgradeEntry.buildingWorldPos())) {
                    LOGGER.debug("[OUAT-QUEUE] entry[{}] Upgrade:{} skipped (already under upgrade)", i, upgradeEntry.defId());
                    continue;
                }

                PlacedBuilding building = town.getBuildings().stream()
                    .filter(b -> b.worldPos.equals(upgradeEntry.buildingWorldPos()))
                    .findFirst().orElse(null);
                BuildingDef def = BuildingDataHandler.get(upgradeEntry.defId()).orElse(null);

                if (building == null || def == null) {
                    LOGGER.warn("[OUAT-QUEUE] entry[{}] Upgrade:{} building/def not found, consuming entry", i, upgradeEntry.defId());
                    town.consumeQueueEntry(entry);
                    LevelTowns.get(level).markDirty();
                    return PlaceScanResult.STARTED;
                }

                int slot = town.getNpcSlot("builder", npc.getUUID());
                if (slot >= 0) {
                    BlockPos upgradeEntryPos = building.entryPos != null ? building.entryPos : building.worldPos;
                    town.setActiveBuild(slot, new ActiveBuildState(
                        upgradeEntry.defId(), building.worldPos, building.rotation,
                        BlockPos.ZERO, Direction.NORTH, "", upgradeEntryPos,
                        List.of(), null, upgradeEntry.entryId(), upgradeEntry.fromLevel()));
                    LevelTowns.get(level).markDirty();
                }
                BuildTask goal = new BuildGoal(npc, new UpgradeAction(building, def, upgradeEntry.fromLevel(), town, level));
                job.receiveAssignment(goal, entry);
                TownLogEntry log = new TownLogEntry(TownLogType.UPGRADE_START, upgradeEntry.defId(), gameTime);
                town.addLogEntry(log);
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, log);
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
                return PlaceScanResult.STARTED;
            }

            if (entry instanceof QueueEntry.Repair repairEntry) {
                boolean alreadyAssigned = town.getActiveBuilds().values().stream()
                    .anyMatch(abs -> abs.queueEntryId() == repairEntry.entryId());
                if (alreadyAssigned) continue;
                if (town.isUnderUpgrade(repairEntry.buildingWorldPos())) {
                    LOGGER.debug("[OUAT-QUEUE] entry[{}] Repair:{} skipped (building under upgrade)", i, repairEntry.defId());
                    continue;
                }

                PlacedBuilding building = town.getBuildings().stream()
                    .filter(b -> b.worldPos.equals(repairEntry.buildingWorldPos()))
                    .findFirst().orElse(null);
                BuildingDef def = BuildingDataHandler.get(repairEntry.defId()).orElse(null);

                if (building == null || def == null) {
                    LOGGER.warn("[OUAT-QUEUE] entry[{}] Repair:{} building/def not found, consuming entry", i, repairEntry.defId());
                    town.consumeQueueEntry(entry);
                    LevelTowns.get(level).markDirty();
                    return PlaceScanResult.STARTED;
                }

                int slot = town.getNpcSlot("builder", npc.getUUID());
                if (slot >= 0) {
                    BlockPos repairEntryPos = building.entryPos != null ? building.entryPos : building.worldPos;
                    town.setActiveBuild(slot, new ActiveBuildState(
                        repairEntry.defId(), building.worldPos, building.rotation,
                        BlockPos.ZERO, Direction.NORTH, "", repairEntryPos,
                        List.of(), null, repairEntry.entryId(), -2));
                    LevelTowns.get(level).markDirty();
                }
                BuildTask goal = new BuildGoal(npc, new RepairAction(building, def, town, level));
                job.receiveAssignment(goal, entry);
                LevelTowns.get(level).markDirty();
                NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
                return PlaceScanResult.STARTED;
            }

            if (entry instanceof QueueEntry.NewBuild newBuild) {
                boolean alreadyAssigned = town.getActiveBuilds().values().stream()
                    .anyMatch(abs -> abs.queueEntryId() == newBuild.entryId());
                if (alreadyAssigned) continue;
                String defId = newBuild.defId();
                BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
                if (def == null) {
                    LOGGER.warn("[OUAT-QUEUE] entry[{}] NewBuild:{} def not found, consuming entry", i, defId);
                    town.consumeQueueEntry(entry);
                    LevelTowns.get(level).markDirty();
                    continue;
                }

                if (!town.meetsPrerequisites(def)) {
                    if (!warned.contains(defId + ":prereq")) {
                        warned.add(defId + ":prereq");
                    }
                    continue;
                }

                List<ConnectionPoint> matchingCps = new ArrayList<>();
                for (ConnectionPoint cp : freePoints) {
                    if (!cp.targetName().isEmpty() && def.entryPool.equals(cp.targetName())) matchingCps.add(cp);
                }

                if (matchingCps.isEmpty()) {
                    anyBlocked = true;
                    warned.add(defId);
                    continue;
                }

                matchingCps.sort(java.util.Comparator.comparingLong(ConnectionPoint::insertionOrder));

                int bbOverlaps = 0, noConnector = 0;

                for (ConnectionPoint point : matchingCps) {
                    ConnectionPoint corrected = ConnectionPointYResolver.correct(level, point);
                    PlacementOutcome outcome = attemptPlacement(level, corrected, occupied, def);
                    if (outcome.succeeded()) {
                        PlacementSuccess s = outcome.success();
                        town.useConnection(point);
                        LevelTowns.get(level).markDirty();
                        BuildTask goal = new BuildGoal(npc, new NewBuildAction(
                            def, corrected, s.pos(), s.rotation(), s.entryConnectorWorldPos(), List.of(), town));
                        if (s.bb() != null) town.addUnderConstruction(def.id, s.pos(), s.bb(), s.rotation());
                        int slot = town.getNpcSlot("builder", npc.getUUID());
                        if (slot >= 0) {
                            town.setActiveBuild(slot, new ActiveBuildState(
                                def.id, s.pos(), s.rotation(), corrected.pos(), corrected.direction(),
                                corrected.targetName(), s.entryConnectorWorldPos(), List.of(), defId, entry.entryId(), -1));
                            LevelTowns.get(level).markDirty();
                        }
                        job.receiveAssignment(goal, entry);
                        warned.remove(defId);
                        warned.remove(defId + ":prereq");
                        TownLogEntry log = new TownLogEntry(TownLogType.BUILD_START, defId, gameTime);
                        town.addLogEntry(log);
                        LevelTowns.get(level).markDirty();
                        NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, log);
                        NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
                        return PlaceScanResult.STARTED;
                    }
                    if (outcome.failure() == FailReason.BOUNDING_BOX_OVERLAP) bbOverlaps++;
                    else if (outcome.failure() == FailReason.NO_COMPATIBLE_CONNECTOR) noConnector++;
                }

                if (noConnector > 0 && bbOverlaps == 0) warned.add(defId);
                anyBlocked = true;
            }
        }

        LOGGER.debug("[OUAT-QUEUE] scan done  anyBlocked={} queueSize={}", anyBlocked, queue.size());
        return anyBlocked ? PlaceScanResult.BLOCKED : PlaceScanResult.NOTHING;
    }

    private static boolean tryAssignStreetExpansion(
            Town town, ServerLevel level, Npc npc, BuilderJob job,
            List<ConnectionPoint> freePoints, List<BoundingBox> occupied,
            BlockPos anchorPos, long gameTime) {

        List<BuildingDef> streetCandidates = new ArrayList<>(town.getBuildableBuildings().stream()
            .filter(d -> Constants.isStreetsPool(d.entryPool))
            .toList());

        List<ConnectionPoint> streetCps = new ArrayList<>();
        for (ConnectionPoint cp : freePoints) {
            if (Constants.isStreetsPool(cp.targetName())) streetCps.add(cp);
        }
        streetCps.sort(java.util.Comparator.comparingLong(ConnectionPoint::insertionOrder));

        if (streetCps.isEmpty() || streetCandidates.isEmpty()) {
            LOGGER.info("[OUAT-STREET] expansion blocked  streetCPs={} streetCandidates={}",
                streetCps.size(), streetCandidates.size());
            checkVillageFull(town, level, anchorPos, gameTime);
            return false;
        }
        LOGGER.debug("[OUAT-STREET] attempting expansion  streetCPs={} candidates={} preferred={} fallback={}",
            streetCps.size(), streetCandidates.size(), 0, 0);

        Set<String> neededPools = new HashSet<>();
        for (QueueEntry entry : town.getConstructionQueue()) {
            if (entry instanceof QueueEntry.NewBuild nb) {
                BuildingDataHandler.get(nb.defId()).ifPresent(def -> {
                    if (!def.entryPool.isEmpty()) neededPools.add(def.entryPool);
                });
            }
        }

        List<BuildingDef> preferred = new ArrayList<>();
        List<BuildingDef> fallback  = new ArrayList<>();
        for (BuildingDef c : streetCandidates) {
            if (offersNeededConnector(level, c, neededPools)) preferred.add(c);
            else fallback.add(c);
        }

        for (ConnectionPoint chosen : streetCps) {
            ConnectionPoint corrected = ConnectionPointYResolver.correct(level, chosen);
            for (int pass = 0; pass < 2; pass++) {
                List<BuildingDef> batch = (pass == 0) ? preferred : fallback;
                if (batch.isEmpty()) continue;
                shuffleInPlace(batch);
                for (BuildingDef candidate : batch) {
                    PlacementOutcome outcome = attemptPlacement(level, corrected, occupied, candidate);
                    if (outcome.succeeded()) {
                        PlacementSuccess s = outcome.success();
                        town.useConnection(chosen);
                        LevelTowns.get(level).markDirty();
                        BuildTask goal = new BuildGoal(npc, new NewBuildAction(
                            candidate, corrected, s.pos(), s.rotation(), s.entryConnectorWorldPos(),
                            candidate.constructionCost, town));
                        if (s.bb() != null) town.addUnderConstruction(candidate.id, s.pos(), s.bb(), s.rotation());
                        int slot = town.getNpcSlot("builder", npc.getUUID());
                        if (slot >= 0) {
                            town.setActiveBuild(slot, new ActiveBuildState(
                                candidate.id, s.pos(), s.rotation(), corrected.pos(), corrected.direction(),
                                corrected.targetName(), s.entryConnectorWorldPos(), candidate.constructionCost, null, -1L, -1));
                            LevelTowns.get(level).markDirty();
                        }
                        job.receiveAssignment(goal, null);
                        LOGGER.info("[OUAT-STREET] ASSIGNED road:{} to builder {} slot={} at {}",
                            candidate.id, npc.getUUID(), town.getNpcSlot("builder", npc.getUUID()), s.pos());
                        TownLogEntry log = new TownLogEntry(TownLogType.BUILD_START, candidate.id, gameTime);
                        town.addLogEntry(log);
                        LevelTowns.get(level).markDirty();
                        NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, log);
                        NetworkHelper.pushBuildingListToWatchers(level, town, anchorPos);
                        return true;
                    }
                }
            }
        }

        checkVillageFull(town, level, anchorPos, gameTime);
        return false;
    }

    private static void checkVillageFull(Town town, ServerLevel level, BlockPos anchorPos, long gameTime) {
        if (town.checkVillageFullTransition()) {
            TownLogEntry fullLog = new TownLogEntry(TownLogType.VILLAGE_FULL, "", gameTime);
            town.addLogEntry(fullLog);
            LevelTowns.get(level).markDirty();
            NetworkHelper.pushLogEntryToWatchers(level, town, anchorPos, fullLog);
        }
    }

    private static boolean offersNeededConnector(ServerLevel level, BuildingDef road, Set<String> neededPools) {
        if (neededPools.isEmpty()) return false;
        for (JigsawConnector c : ConnectorReader.readConnectors(level, road.nbt)) {
            if (!c.pool().isEmpty() && !c.pool().equals("minecraft:empty") && neededPools.contains(c.target())) {
                return true;
            }
        }
        return false;
    }

    private static PlacementOutcome attemptPlacement(ServerLevel level, ConnectionPoint point,
                                                      List<BoundingBox> occupied, BuildingDef def) {
        List<JigsawConnector> connectors = ConnectorReader.readConnectors(level, def.nbt);
        List<JigsawConnector> compatible = connectors.stream()
            .filter(c -> point.targetName().isEmpty() || c.name().equals(point.targetName()))
            .toList();
        if (compatible.isEmpty()) return PlacementOutcome.fail(FailReason.NO_COMPATIBLE_CONNECTOR);

        List<JigsawConnector> entryConnectors = compatible.stream()
            .filter(c -> c.pool().isEmpty() || c.pool().equals("minecraft:empty"))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<JigsawConnector> shuffled = entryConnectors.isEmpty() ? new ArrayList<>(compatible) : entryConnectors;
        shuffleInPlace(shuffled);

        BlockPos attachPoint = point.pos().relative(point.direction());
        int terrainY = def.terrainMatching ? TerrainMatchedPlacer.findGroundY(level, attachPoint) : 0;

        int waterBlocked = 0;

        for (JigsawConnector chosen : shuffled) {
            Rotation rotation = SchematicBounds.computeRequiredRotation(chosen.facing(), point.direction().getOpposite());
            BlockPos rawPos = SchematicBounds.computeCandidatePosition(
                point.pos(), point.direction(), chosen.posInTemplate(), rotation);

            int finalY = def.terrainMatching
                ? terrainY - chosen.posInTemplate().getY()
                : rawPos.getY();
            BlockPos finalPos = new BlockPos(rawPos.getX(), finalY, rawPos.getZ());

            Optional<BoundingBox> maybeBb = def.terrainMatching
                ? SchematicBounds.computeFootprintBoundingBox(level, finalPos, def.nbt, rotation)
                : SchematicBounds.computeBoundingBox(level, finalPos, def.nbt, rotation);

            if (SchematicBounds.footprintContainsWater(level, finalPos, def.nbt, rotation)) {
                waterBlocked++;
                continue;
            }

            if (maybeBb.isPresent()) {
                BoundingBox cb = maybeBb.get();
                boolean overlaps = occupied.stream().anyMatch(bb ->
                    bb.minX() < cb.maxX() && bb.maxX() > cb.minX() &&
                    bb.minZ() < cb.maxZ() && bb.maxZ() > cb.minZ()
                );
                if (overlaps) continue;
            }

            BlockPos entryConnectorWorldPos = finalPos.offset(
                StructureTemplate.transform(chosen.posInTemplate(), Mirror.NONE, rotation, BlockPos.ZERO));

            return PlacementOutcome.ok(finalPos, rotation, entryConnectorWorldPos, maybeBb.orElse(null));
        }

        if (waterBlocked > 0 && waterBlocked == shuffled.size()) {
            return PlacementOutcome.fail(FailReason.WATER_IN_FOOTPRINT);
        }
        return PlacementOutcome.fail(FailReason.BOUNDING_BOX_OVERLAP);
    }

    private static <T> void shuffleInPlace(List<T> list) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            T tmp = list.get(i);
            list.set(i, list.get(j));
            list.set(j, tmp);
        }
    }
}
