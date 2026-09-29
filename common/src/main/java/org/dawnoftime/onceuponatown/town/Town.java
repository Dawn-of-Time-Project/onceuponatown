package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.town.StandSlot;
import org.dawnoftime.onceuponatown.datapack.EraDef;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class Town {

    private static final Logger LOGGER = LoggerFactory.getLogger(Town.class);

    public record UnderConstructionEntry(String defId, BlockPos worldPos, BoundingBox bb, Rotation rotation) {}

    private final List<PlacedBuilding> buildings = new ArrayList<>();
    private final List<ConnectionPoint> freeConnections = new ArrayList<>();
    private final Map<Item, Integer> reserveStock = new HashMap<>();
    private TownInventory cachedInventory;
    private boolean occupiedBoxesDirty = true;
    private List<BoundingBox> cachedOccupiedBoxes = null;
    private final List<UnderConstructionEntry> underConstruction = new ArrayList<>();
    // Bounding boxes reserved for pieces that have no building def (starter, vanilla pieces).
    // Must be serialized - the mixin that populates these only fires during world gen, not on reload.
    private final List<BoundingBox> blockedZones = new ArrayList<>();
    // World positions of buildings currently being upgraded by an NPC (runtime-only, not persisted).
    private final Set<BlockPos> underUpgrade = new HashSet<>();
    private TownNpcState npcState = new TownNpcState();
    private TownQueueState queueState = new TownQueueState();
    private String name = "Unknown Town";
    private TownQuestState questState = new TownQuestState();

    // Sliding window of the last 20 activity events; persisted to NBT.
    private final ArrayDeque<TownLogEntry> activityLog = new ArrayDeque<>();
    private static final int LOG_MAX = 20;

    // Players who opted in to receiving village log entries as chat messages. Persisted to NBT.
    private final Set<UUID> chatSubscribers = new HashSet<>();

    private TownEraState eraState = new TownEraState();
    // Monotonically increasing counter stamped onto each ConnectionPoint when added to freeConnections.
    // Allows sorting by insertion age: lower = older = closer to the village center.
    private long cpInsertionCounter = 0;

    // Active commerce contract: virtual production entries that tick like a real building.
    @Nullable
    private List<ContractEntry> activeContract = null;
    private final Map<Item, Integer> contractStock = new HashMap<>();

    private boolean medalClaimed = false;
    @Nullable private Set<String> depositedMedalIds = null;

    public record MedalSnapshot(
        String namespace,
        String mainOrientation,
        String mainOrientationLabel,
        String currentOrientation,
        String currentOrientationLabel,
        String starterBuildingId,
        Set<String> eraUnlockedIds,
        Set<String> signatureIds
    ) {}

    public record SignatureBuildingProgress(
        String defId, String iconItem,
        boolean accessible,
        boolean built, int currentLevel, int maxLevel, boolean maxed
    ) {}

    public Town() {
        this.cachedInventory = new TownInventory(buildings, reserveStock, this);
    }

    public PlacedBuilding registerBuilding(BlockPos worldPos, String defId, List<ConnectionPoint> connections, BoundingBox bb, Rotation rotation, List<BlockPos> obstaclePositions, @org.jetbrains.annotations.Nullable BlockPos entryPos) {
        PlacedBuilding placed = new PlacedBuilding(defId, worldPos, bb, rotation, obstaclePositions, entryPos);
        buildings.add(placed);
        occupiedBoxesDirty = true;
        for (ConnectionPoint cp : connections) {
            freeConnections.add(new ConnectionPoint(cp.pos(), cp.direction(), cp.targetName(), cpInsertionCounter++));
        }
        // Remove phantom CPs whose expansion block falls inside the new building's footprint.
        // These are open connectors that point into already-occupied space and can never be used.
        if (bb != null) {
            freeConnections.removeIf(cp -> {
                net.minecraft.core.BlockPos expansion = cp.pos().relative(cp.direction());
                return bb.isInside(expansion);
            });
        }
        return placed;
    }

    // Acquires the first free stand of standType across all buildings with the given defId.
    public @org.jetbrains.annotations.Nullable StandSlot acquireStand(String buildingDefId, String standType, java.util.UUID npcUuid) {
        for (PlacedBuilding building : buildings) {
            if (!building.defId.equals(buildingDefId)) continue;
            StandSlot slot = building.acquireStand(standType, npcUuid);
            if (slot != null) return slot;
        }
        return null;
    }

    // Releases any stand held by npcUuid across all buildings.
    public void releaseStand(java.util.UUID npcUuid) {
        for (PlacedBuilding building : buildings) {
            if (building.releaseStand(npcUuid)) return;
        }
    }

    public void addBlockedZone(BoundingBox bb) {
        blockedZones.add(bb);
        occupiedBoxesDirty = true;
    }

    public void addUnderConstruction(String defId, BlockPos pos, BoundingBox bb, Rotation rotation) {
        if (underConstruction.stream().noneMatch(e -> e.worldPos().equals(pos))) {
            underConstruction.add(new UnderConstructionEntry(defId, pos, bb, rotation));
            occupiedBoxesDirty = true;
        }
    }

    public void removeUnderConstruction(BlockPos pos) {
        underConstruction.removeIf(e -> e.worldPos().equals(pos));
        occupiedBoxesDirty = true;
    }

    public List<UnderConstructionEntry> getUnderConstructionBuildings() {
        return Collections.unmodifiableList(underConstruction);
    }

    public void addUnderUpgrade(BlockPos pos) {
        underUpgrade.add(pos);
    }

    public void removeUnderUpgrade(BlockPos pos) {
        underUpgrade.remove(pos);
    }

    // Returns the world bounding boxes of all placed buildings plus blocked zones plus in-progress builds.
    // Buildings from saves predating BB tracking have null bb - they are skipped.
    public List<BoundingBox> getOccupiedBoxes() {
        if (!occupiedBoxesDirty && cachedOccupiedBoxes != null) return cachedOccupiedBoxes;
        List<BoundingBox> all = new ArrayList<>();
        buildings.stream().map(b -> b.bb).filter(Objects::nonNull).forEach(all::add);
        all.addAll(blockedZones);
        underConstruction.stream().map(UnderConstructionEntry::bb).filter(Objects::nonNull).forEach(all::add);
        cachedOccupiedBoxes = all;
        occupiedBoxesDirty = false;
        return all;
    }

    // Tracks whether the "village full" chat message has fired for the current empty state.
    // Reset when a new CP is added so the message can fire again if the village hits zero a second time.
    private boolean villageFullNotified = false;

    // Transient meal state: set by FoodManager when the feeding schedule fires. Not persisted.
    private static final int MEAL_DURATION_TICKS = 1200;
    private boolean mealActive = false;
    private long mealStartGameTime = 0L;

    public void addFreeConnection(ConnectionPoint point) {
        freeConnections.add(new ConnectionPoint(point.pos(), point.direction(), point.targetName(), cpInsertionCounter++));
        villageFullNotified = false;
    }

    public void useConnection(ConnectionPoint point) {
        freeConnections.remove(point);
    }

    // Returns true exactly once when freeConnections transitions from non-empty to empty.
    public boolean checkVillageFullTransition() {
        if (!villageFullNotified && freeConnections.isEmpty()) {
            villageFullNotified = true;
            return true;
        }
        return false;
    }

    public List<ConnectionPoint> getAvailableConnectionPoints() {
        return Collections.unmodifiableList(freeConnections);
    }

    // Returns building defs available to build: all buildings whose construction cost is met by the town inventory.
    public List<BuildingDef> getBuildableBuildings() {
        TownInventory inv = getTownInventory();
        return BuildingDataHandler.getAll().stream()
            .filter(def -> inv.hasStock(def.constructionCost))
            .toList();
    }

    // Computes committed weight: placed buildings + NewBuild entries in the player queue.
    // Queued-but-unplaced buildings are included so tryAddToConstructionQueue enforces the
    // cap against the full committed load, not just what is already physically in the world.
    public int getCurrentWeight() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            total += def.weight;
        }
        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.NewBuild nb) {
                BuildingDef def = BuildingDataHandler.get(nb.defId()).orElse(null);
                if (def != null) total += def.weight;
            }
        }
        return total;
    }

    public int getCurrentEra()                              { return eraState.getCurrentEra(); }
    public String getCurrentEraPath()                       { return eraState.getCurrentEraPath(); }
    public String getCurrentOrientation()                   { return eraState.getCurrentOrientation(); }
    public String getMainOrientation()                      { return eraState.getMainOrientation(); }
    public String getCultureNamespace()                     { return eraState.getCultureNamespace(); }
    public Set<String> getUnlockedBuildingIds()             { return eraState.getUnlockedBuildingIds(); }
    public void addUnlockedBuildingIds(Collection<String> ids) { eraState.addUnlockedBuildingIds(ids); }
    public int getCurrentMaxWeight()                        { return eraState.getCurrentMaxWeight(); }
    public int getCurrentMaxUpgradeLevel()                  { return eraState.getCurrentMaxUpgradeLevel(); }

    public boolean isMedalClaimed()               { return medalClaimed; }
    public boolean isMedalReceived()              { return depositedMedalIds != null; }
    public @Nullable Set<String> getDepositedMedalIds() { return depositedMedalIds; }
    public void receiveMedal(Set<String> ids)     { this.depositedMedalIds = new HashSet<>(ids); }

    public MedalSnapshot claimMedal() {
        medalClaimed = true;

        String mainOri = eraState.getMainOrientation();
        EraDef rootDef = EraTransitionDataHandler.getEraDefByOrientation(mainOri);
        if (rootDef == null) {
            for (PlacedBuilding b : buildings) {
                EraDef candidate = EraTransitionDataHandler.getEraDefForStarter(b.defId);
                if (candidate != null) { rootDef = candidate; break; }
            }
        }
        String mainLabel = rootDef != null ? rootDef.orientationLabel : mainOri;
        String starterBuildingId = rootDef != null ? rootDef.starterBuildingId : "";

        String currentOri = eraState.getCurrentOrientation();
        String currentLabel = mainOri.equals(currentOri)
            ? mainLabel
            : EraTransitionDataHandler.getAll().stream()
                .filter(t -> t.nextOrientation.equals(currentOri))
                .map(t -> t.orientationLabel)
                .findFirst().orElse(currentOri);

        Set<String> signatureIds = buildings.stream()
            .filter(b -> {
                Optional<BuildingDef> def = BuildingDataHandler.get(b.defId);
                if (def.isEmpty() || !def.get().signature) return false;
                int maxLevel = Math.max(def.get().upgrades.size(), def.get().nbtLevels.size());
                return maxLevel == 0 || b.getUpgradeLevel() >= maxLevel;
            })
            .map(b -> b.defId)
            .collect(Collectors.toSet());

        Set<String> eraUnlockedIds = new HashSet<>(eraState.getUnlockedBuildingIds());

        return new MedalSnapshot(
            eraState.getCultureNamespace(),
            mainOri, mainLabel,
            currentOri, currentLabel,
            starterBuildingId,
            eraUnlockedIds,
            signatureIds
        );
    }

    public List<SignatureBuildingProgress> getSignatureBuildingProgress() {
        String ns = eraState.getCultureNamespace();
        int currentEra = eraState.getCurrentEra();
        Set<String> unlocked = eraState.getUnlockedBuildingIds();

        Set<String> reachableFuture = new HashSet<>();
        for (EraTransitionDef t : EraTransitionDataHandler.getAll()) {
            if (!t.namespace.equals(ns)) continue;
            if (t.fromEra < currentEra) continue;
            reachableFuture.addAll(t.unlockedBuildingIds);
        }

        Set<String> visible = new HashSet<>(unlocked);
        visible.addAll(reachableFuture);

        return BuildingDataHandler.getAll(ns).stream()
            .filter(def -> def.signature && visible.contains(def.id))
            .map(def -> {
                int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
                int current = buildings.stream()
                    .filter(b -> b.defId.equals(def.id))
                    .mapToInt(PlacedBuilding::getUpgradeLevel)
                    .findFirst().orElse(-1);
                boolean built = current >= 0;
                boolean accessible = unlocked.contains(def.id) || built;
                boolean maxed = built && (maxLevel == 0 || current >= maxLevel);
                return new SignatureBuildingProgress(def.id, def.iconItem, accessible, built, current, maxLevel, maxed);
            })
            .toList();
    }
    public List<BoundingBox> getBlockedZones()              { return Collections.unmodifiableList(blockedZones); }
    public boolean isUnderUpgrade(BlockPos pos)             { return underUpgrade.contains(pos); }
    // Derives orientation from the placed starter building if not already set (world gen path),
    // then seeds currentMaxWeight from the matched era 0 data file.
    public void initFromEraDef() {
        if (eraState.getCurrentOrientation().isEmpty()) {
            for (PlacedBuilding b : buildings) {
                EraDef eraDef = EraTransitionDataHandler.getEraDefForStarter(b.defId);
                if (eraDef != null) {
                    eraState.setCurrentOrientation(eraDef.orientation);
                    break;
                }
            }
        }
        eraState.initFromEraDef();
    }
    public List<EraTransitionDef> getAvailableTransitions() { return eraState.getAvailableTransitions(); }

    // Returns true if all prereqs for the given era transition are satisfied.
    public boolean meetsEraTransitionPrereqs(EraTransitionDef t) {
        int w = getCurrentWeight();
        if (w > getCurrentMaxWeight()) return false;
        if (t.requiredResidents > 0 && eraState.getActiveResidents() < t.requiredResidents) return false;
        if (!isPlayerControlled()) {
            for (BuildingDef.BuildingRequirement req : t.requiredBuildings) {
                long count = buildings.stream().filter(b -> b.defId.equals(req.defId())).count();
                if (count < req.count()) return false;
            }
        }
        if (!getTownInventory().hasStock(t.resourceCost)) return false;
        return true;
    }

    // Performs the era transition identified by pathId. Returns true if successful.
    public boolean advanceEra(String pathId) {
        EraTransitionDef t = EraTransitionDataHandler.get(pathId).orElse(null);
        if (t == null) return false;
        List<EraTransitionDef> available = getAvailableTransitions();
        if (available.stream().noneMatch(a -> a.id.equals(pathId))) return false;
        if (!meetsEraTransitionPrereqs(t)) return false;
        getTownInventory().removeStock(t.resourceCost);
        eraState.setCurrentEra(eraState.getCurrentEra() + 1);
        eraState.addMaxWeight(t.weightCapIncrease);
        if (t.maxUpgradeLevel > 0) eraState.setCurrentMaxUpgradeLevel(Math.max(eraState.getCurrentMaxUpgradeLevel(), t.maxUpgradeLevel));
        eraState.setCurrentEraPath(t.id);
        if (!t.nextOrientation.isEmpty()) eraState.setCurrentOrientation(t.nextOrientation);
        eraState.addUnlockedBuildingIds(t.unlockedBuildingIds);
        t.unlockNpcCounts.forEach((jobId, count) ->
            npcState.addTargetNpcCount(jobId, count));
        if (!t.autoUpgradeIds.isEmpty()) {
            for (PlacedBuilding b : buildings) {
                if (t.autoUpgradeIds.contains(b.defId)) {
                    forceQueueUpgrade(b.worldPos);
                }
            }
        }
        return true;
    }

    public List<String> getBoostedBuildingIds() { return eraState.getBoostedBuildingIds(); }

    // Called by the NPC after a NewBuild placement succeeds.
    // Stamps the orientation bonus multiplier onto the building if its defId is boosted.
    public void onBuildingPlaced(String defId) {
        if (buildings.isEmpty()) return;
        String orientation = eraState.getCurrentOrientation();
        if (orientation.isEmpty()) return;
        EraDef era = EraTransitionDataHandler.getEraDefByOrientation(orientation);
        if (era == null || !era.boostedBuildings.contains(defId)) return;
        buildings.get(buildings.size() - 1).setInstanceProductionMultiplier(era.boostMultiplier);
    }

    // Computed aggregate view: buildings + floating reserve + contract stock
    public TownInventory getTownInventory() {
        return cachedInventory;
    }

    public @Nullable List<ContractEntry> getActiveContract() { return activeContract; }

    public void setActiveContract(List<ContractEntry> entries) {
        this.activeContract = entries;
        this.contractStock.clear();
    }

    public void clearContract() {
        this.activeContract = null;
        contractStock.forEach((item, amount) -> reserveStock.merge(item, amount, Integer::sum));
        this.contractStock.clear();
    }

    public Map<Item, Integer> getContractStock() { return contractStock; }

    public void addContractStock(Item item, int amount) {
        contractStock.merge(item, amount, Integer::sum);
    }

    public void removeContractStock(Item item, int amount) {
        int current = contractStock.getOrDefault(item, 0);
        int remaining = current - amount;
        if (remaining <= 0) contractStock.remove(item);
        else contractStock.put(item, remaining);
    }

    // Player command injection - into first building if available, otherwise into reserve
    public void addStock(Item item, int quantity) {
        if (!buildings.isEmpty()) {
            buildings.get(0).forceAdd(item, quantity);
        } else {
            reserveStock.merge(item, quantity, Integer::sum);
        }
    }

    // Returns {NWCorner, SECorner} as BlockPos array derived from all occupied boxes.
    // Y is set to 0 - the map is purely 2D (XZ plane). Returns null if no boxes exist.
    public BlockPos[] getMapBounds() {
        List<BoundingBox> boxes = getOccupiedBoxes();
        if (boxes.isEmpty()) return null;
        int minX = boxes.stream().mapToInt(BoundingBox::minX).min().getAsInt();
        int minZ = boxes.stream().mapToInt(BoundingBox::minZ).min().getAsInt();
        int maxX = boxes.stream().mapToInt(BoundingBox::maxX).max().getAsInt();
        int maxZ = boxes.stream().mapToInt(BoundingBox::maxZ).max().getAsInt();
        return new BlockPos[]{ new BlockPos(minX, 0, minZ), new BlockPos(maxX, 0, maxZ) };
    }

    // -------------------------------------------------------------------------
    // Player construction queue
    // -------------------------------------------------------------------------

    public List<QueueEntry> getConstructionQueue()              { return queueState.getConstructionQueue(); }
    public int findQueueIndex(long entryId)                     { return queueState.findQueueIndex(entryId); }
    public void consumeQueueEntry(QueueEntry entry)             { queueState.consumeQueueEntry(entry); }
    public String getNextAutoBuildTarget(List<EraTransitionDef.AutoBuildEntry> seq) { return queueState.getNextAutoBuildTarget(seq, buildings); }

    // Returns false if the NewBuild entry cannot be added: queue full, weight cap exceeded,
    // prerequisites unmet, or insufficient stock (depending on which checks are requested).
    private boolean canAddNewBuild(BuildingDef def, boolean checkWeight, boolean checkPrereqs, boolean checkStock) {
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        if (checkWeight && getCurrentWeight() + def.weight > getCurrentMaxWeight()) return false;
        if (checkPrereqs && !meetsPrerequisites(def)) return false;
        if (checkStock && !getTownInventory().hasStock(def.constructionCost)) return false;
        return true;
    }

    // Deducts cost from inventory (unless planned), reserves it in the queue slot, and appends a NewBuild entry.
    private void enqueueNewBuild(BuildingDef def, boolean locked, boolean planned, boolean residentTrack) {
        if (!planned) {
            getTownInventory().removeStock(def.constructionCost);
            queueState.reserveStock(def.constructionCost);
        }
        queueState.addEntry(new QueueEntry.NewBuild(queueState.nextEntryId(), def.id, locked, planned, residentTrack));
    }

    // Checks affordability (available stock minus already-reserved amounts), reserves resources,
    // and appends a NewBuild entry to the queue. Returns false if unaffordable or queue is full.
    public boolean tryAddToConstructionQueue(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || !canAddNewBuild(def, true, false, true)) return false;
        enqueueNewBuild(def, false, false, false);
        return true;
    }

    // Like tryAddToConstructionQueue but marks the entry locked (autonomy-injected).
    // Locked entries cannot be removed by the player and are tracked by EraManager.
    public boolean tryAddToConstructionQueueLocked(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || !canAddNewBuild(def, true, false, true)) return false;
        enqueueNewBuild(def, true, false, false);
        return true;
    }

    // Adds a locked planned entry for the given building without reserving stock.
    // The builder will skip it; EraManager promotes it once resources become available.
    public boolean tryAddPlannedEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || !canAddNewBuild(def, true, true, false)) return false;
        enqueueNewBuild(def, true, true, false);
        return true;
    }

    // Promotes a planned entry to a real locked entry by reserving its stock.
    // Returns false if no planned entry for defId exists or stock is insufficient.
    public boolean tryPromotePlannedEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null) return false;
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(def.constructionCost)) return false;
        if (!queueState.promotePlannedEntry(defId)) return false;
        inv.removeStock(def.constructionCost);
        queueState.reserveStock(def.constructionCost);
        return true;
    }

    // Counts placed residents plus residents pending from resident-track queue entries.
    public int computeEffectiveResidents() {
        int total = getTotalResidents();
        for (QueueEntry e : getConstructionQueue()) {
            if (e instanceof QueueEntry.NewBuild nb && nb.residentTrack()) {
                BuildingDef def = BuildingDataHandler.get(nb.defId()).orElse(null);
                if (def != null) total += def.residents;
            }
        }
        return total;
    }

    // Reserves stock and injects a resident-track locked entry (slot R, stock ready).
    public boolean tryAddLockedResidentEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || !canAddNewBuild(def, true, true, true)) return false;
        enqueueNewBuild(def, true, false, true);
        return true;
    }

    // Injects a resident-track planned entry (slot R, stock not yet available).
    public boolean tryAddPlannedResidentEntry(String defId) {
        BuildingDef def = BuildingDataHandler.get(defId).orElse(null);
        if (def == null || !canAddNewBuild(def, true, true, false)) return false;
        enqueueNewBuild(def, true, true, true);
        return true;
    }

    // Promotes a resident-planned entry for defId once stock is available.
    // Structurally identical to tryPromotePlannedEntry -- delegates to it.
    public boolean tryPromoteResidentEntry(String defId) {
        return tryPromotePlannedEntry(defId);
    }

    public QueueEntry.NewBuild getPlannedResidentEntry()  { return queueState.findPlannedResidentEntry(); }
    public boolean hasRealResidentLockedEntry()           { return queueState.hasRealResidentLockedEntry(); }

    // -- Autonomy upgrade slot (slot U) --

    // Reserves stock and injects a locked Upgrade entry for the autonomy upgrade slot.
    public boolean tryAddLockedUpgradeEntry(BlockPos worldPos) {
        PlacedBuilding building = buildings.stream().filter(b -> b.worldPos.equals(worldPos)).findFirst().orElse(null);
        if (building == null) return false;
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;
        int effectiveLevel = building.getUpgradeLevel();
        if (effectiveLevel >= eraState.getCurrentMaxUpgradeLevel()) return false;
        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        List<ItemCost> cost = effectiveLevel < def.upgrades.size() ? def.upgrades.get(effectiveLevel).upgradeCost() : List.of();
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(cost)) return false;
        inv.removeStock(cost);
        queueState.reserveStock(cost);
        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, true, false));
        return true;
    }

    // Injects a planned (no stock reserved) locked Upgrade entry for the autonomy upgrade slot.
    public boolean tryAddPlannedUpgradeEntry(BlockPos worldPos) {
        PlacedBuilding building = buildings.stream().filter(b -> b.worldPos.equals(worldPos)).findFirst().orElse(null);
        if (building == null) return false;
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;
        int effectiveLevel = building.getUpgradeLevel();
        if (effectiveLevel >= eraState.getCurrentMaxUpgradeLevel()) return false;
        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;
        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, true, true));
        return true;
    }

    // Promotes the planned autonomous upgrade entry to real once stock is available.
    public boolean tryPromotePlannedUpgrade() {
        QueueEntry.Upgrade planned = queueState.findPlannedAutonomousUpgrade();
        if (planned == null) return false;
        BuildingDef def = BuildingDataHandler.get(planned.defId()).orElse(null);
        if (def == null) return false;
        List<ItemCost> cost = planned.fromLevel() < def.upgrades.size()
            ? def.upgrades.get(planned.fromLevel()).upgradeCost() : List.of();
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(cost)) return false;
        if (!queueState.promoteAutonomousUpgradeEntry()) return false;
        inv.removeStock(cost);
        queueState.reserveStock(cost);
        return true;
    }

    public QueueEntry.Upgrade getPlannedAutonomousUpgrade()   { return queueState.findPlannedAutonomousUpgrade(); }
    public boolean hasRealAutonomousUpgradeEntry()            { return queueState.hasRealAutonomousUpgradeEntry(); }
    public int countRealAutonomousUpgradeEntries()            { return queueState.countRealAutonomousUpgradeEntries(); }
    public int countPlannedAutonomousUpgradeEntries()         { return queueState.countPlannedAutonomousUpgradeEntries(); }

    // Removes all locked NewBuild entries whose defId is absent from newSequence.
    // Called when the player switches the autonomy path so orphaned locks are cancelled.
    public void cancelOrphanedLockedEntries(List<EraTransitionDef.AutoBuildEntry> newSequence) {
        Map<Item, Integer> refunds = queueState.cancelOrphanedLockedEntries(newSequence);
        refunds.forEach((item, qty) -> reserveStock.merge(item, qty, Integer::sum));
    }

    public boolean isAutonomyEnabled()                  { return eraState.isAutonomyEnabled(); }
    public void setAutonomyEnabled(boolean v)           { eraState.setAutonomyEnabled(v); }
    public boolean isPlayerControlled()                 { return !eraState.isAutonomyEnabled(); }
    public boolean isAutoUpgradeEnabled()               { return eraState.isAutoUpgradeEnabled(); }
    public void setAutoUpgradeEnabled(boolean v)        { eraState.setAutoUpgradeEnabled(v); }
    public String getAutonomyChosenTransitionId()       { return eraState.getAutonomyChosenTransitionId(); }
    public void setAutonomyChosenTransitionId(String v) { eraState.setAutonomyChosenTransitionId(v); }

    // Checks affordability and appends an Upgrade entry to the queue.
    // Returns false if: building not found, already at max level, upgrade already pending,
    // queue full, or insufficient stock. Only one upgrade per building can be queued at a time.
    public boolean tryQueueUpgrade(BlockPos worldPos) {
        PlacedBuilding building = null;
        for (PlacedBuilding b : buildings) {
            if (b.worldPos.equals(worldPos)) { building = b; break; }
        }
        if (building == null) return false;

        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;

        // Block if an upgrade for this building is already pending in the queue.
        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.Upgrade u && u.buildingWorldPos().equals(worldPos)) {
                return false;
            }
        }

        int effectiveLevel = building.getUpgradeLevel();
        if (effectiveLevel >= eraState.getCurrentMaxUpgradeLevel()) return false;
        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;

        // Visual-only upgrades (nbt_levels only, no stat upgrades) are free -- cost already paid by era advance.
        List<ItemCost> cost = effectiveLevel < def.upgrades.size()
            ? def.upgrades.get(effectiveLevel).upgradeCost()
            : List.of();
        TownInventory inv = getTownInventory();
        if (!inv.hasStock(cost)) return false;

        inv.removeStock(cost);
        queueState.reserveStock(cost);
        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, false, false));
        return true;
    }

    // Queues a repair task for a building whose world state diverges from its stored template.
    // Free (no cost). Returns false if: not found, under upgrade, already has repair/upgrade queued,
    // queue full, or world scan finds zero mismatches.
    public boolean tryQueueRepair(BlockPos worldPos, ServerLevel level) {
        PlacedBuilding building = buildings.stream()
            .filter(b -> b.worldPos.equals(worldPos)).findFirst().orElse(null);
        if (building == null) return false;

        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null) return false;

        if (isUnderUpgrade(worldPos)) return false;

        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.Repair r && r.buildingWorldPos().equals(worldPos)) return false;
            if (entry instanceof QueueEntry.Upgrade u && u.buildingWorldPos().equals(worldPos)) return false;
        }

        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;

        int upgradeLevel = building.getUpgradeLevel();
        ResourceLocation nbtPath = (upgradeLevel == 0)
            ? def.nbt
            : (upgradeLevel - 1 < def.nbtLevels.size() ? def.nbtLevels.get(upgradeLevel - 1).nbt() : null);
        if (nbtPath == null) return false;

        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(nbtPath);
        if (templateOpt.isEmpty()) return false;

        List<SchematicBlock> templateBlocks = SchematicReader.readSortedBlocks(templateOpt.get(), building.rotation).blocks();
        boolean anyMismatch = templateBlocks.stream().anyMatch(b ->
            !level.getBlockState(building.worldPos.offset(b.localPos())).equals(b.state()));
        if (!anyMismatch) return false;

        queueState.addEntry(new QueueEntry.Repair(queueState.nextEntryId(), def.id, worldPos));
        return true;
    }

    // Free upgrade bypassing resource check -- cost absorbed by era transition.
    // Still verifies: building exists, not at max level, queue not full.
    public boolean forceQueueUpgrade(BlockPos worldPos) {
        PlacedBuilding building = null;
        for (PlacedBuilding b : buildings) {
            if (b.worldPos.equals(worldPos)) { building = b; break; }
        }
        if (building == null) return false;

        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null || (def.upgrades.isEmpty() && def.nbtLevels.isEmpty())) return false;

        int effectiveLevel = building.getUpgradeLevel();
        for (QueueEntry entry : queueState.getConstructionQueue()) {
            if (entry instanceof QueueEntry.Upgrade u && u.buildingWorldPos().equals(worldPos)) {
                effectiveLevel++;
            }
        }

        int maxLevel = Math.max(def.upgrades.size(), def.nbtLevels.size());
        if (effectiveLevel >= maxLevel) return false;
        if (queueState.getConstructionQueue().size() >= TownQueueState.QUEUE_CAPACITY) return false;

        queueState.addEntry(new QueueEntry.Upgrade(queueState.nextEntryId(), building.defId, worldPos, effectiveLevel, true, false));
        return true;
    }

    // Removes entry at index, restoring its reserved resources to the floating reserve.
    // Locked entries (autonomy-injected) cannot be removed by the player.
    public boolean removeFromConstructionQueue(int index) {
        if (index < 0 || index >= queueState.getConstructionQueue().size()) return false;
        QueueEntry entry = queueState.getConstructionQueue().get(index);
        if (entry.locked()) return false;
        List<ItemCost> refund = queueState.popEntry(index);
        for (ItemCost cost : refund) {
            int reserved = queueState.getQueueReservedStock().getOrDefault(cost.item(), 0);
            int toRestore = Math.min(reserved, cost.amount());
            if (toRestore > 0) {
                queueState.getQueueReservedStock().put(cost.item(), reserved - toRestore);
                reserveStock.merge(cost.item(), toRestore, Integer::sum);
            }
        }
        return true;
    }

    // Sums resolved residents (including upgrade bonuses) for all placed buildings.
    public int getTotalResidents() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            total += def.resolveAtLevel(b.getUpgradeLevel()).resolvedResidents();
        }
        return total;
    }

    // Computes total food units demanded per day across all residential and herd buildings (unrounded float).
    public float computeTotalFoodDemandFloat() {
        float total = 0f;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            BuildingDef.ResolvedBuildingStats stats = def.resolveAtLevel(b.getUpgradeLevel());
            if (stats.resolvedResidents() > 0) {
                total += stats.resolvedResidents() * FoodRegistry.getUnitsPerResident();
            }
            int effectiveAnimals = stats.resolvedMaxHerds() > 0 ? stats.resolvedMaxHerds() : stats.resolvedHerd();
            if (effectiveAnimals > 0) {
                total += effectiveAnimals * FoodRegistry.getUnitsPerAnimal();
            }
        }
        return total;
    }

    // Sums resolved herd count across all placed buildings.
    public int getTotalHerd() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            total += def.resolveAtLevel(b.getUpgradeLevel()).resolvedHerd();
        }
        return total;
    }

    // Sums resolved herd count for buildings whose herd was fed at last dawn.
    public int getActiveHerd() {
        int total = 0;
        for (PlacedBuilding b : buildings) {
            BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
            if (def == null) continue;
            int h = def.resolveAtLevel(b.getUpgradeLevel()).resolvedHerd();
            if (h > 0 && b.isHerdFed()) total += h;
        }
        return total;
    }

    public int getActiveResidents()         { return eraState.getActiveResidents(); }
    public void setActiveResidents(int v)   { eraState.setActiveResidents(v); }

    public void startMeal(long gameTime) {
        mealActive = true;
        mealStartGameTime = gameTime;
    }

    public boolean isMealTime(long gameTime) {
        return mealActive && (gameTime - mealStartGameTime < MEAL_DURATION_TICKS);
    }

    // Per-NPC variant: the NPC enters eating only after startOffset ticks have elapsed,
    // and exits eating endOffset ticks before the window closes.
    public boolean isMealTimeFor(long gameTime, int startOffset, int endOffset) {
        if (!mealActive) return false;
        long elapsed = gameTime - mealStartGameTime;
        return elapsed >= startOffset && elapsed < (MEAL_DURATION_TICKS - endOffset);
    }

    // Returns true if all prerequisites of the given def are currently satisfied.
    // Uses activeResidents (fed population) instead of total residents.
    public boolean meetsPrerequisites(BuildingDef def) {
        if (def.requiredResidents > 0 && eraState.getActiveResidents() < def.requiredResidents) return false;
        for (BuildingDef.BuildingRequirement req : def.requiredBuildings) {
            long count = buildings.stream().filter(b -> b.defId.equals(req.defId())).count();
            if (count < req.count()) return false;
        }
        return true;
    }

    // Returns hub data: map + era + catalog + stock + queue + summary + quests.
    public CompoundTag getHubData(BlockPos anchorPos, ServerLevel level) {
        return new TownHubDataBuilder(this, level).buildHubData(anchorPos);
    }

    // -------------------------------------------------------------------------
    // Targeted serialization helpers (used by targeted S2C packets)
    // -------------------------------------------------------------------------

    public CompoundTag getStockUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this, null).buildStockUpdateData(anchorPos);
    }

    public CompoundTag getBuildingListData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this, null).buildBuildingListData(anchorPos);
    }

    public CompoundTag getQuestUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this, null).buildQuestUpdateData(anchorPos);
    }

    public CompoundTag getEraUpdateData(BlockPos anchorPos) {
        return new TownHubDataBuilder(this, null).buildEraUpdateData(anchorPos);
    }

    public CompoundTag getCitizenUpdateData(BlockPos anchorPos, ServerLevel level) {
        return new TownHubDataBuilder(this, level).buildCitizenUpdateData(anchorPos);
    }

    // -------------------------------------------------------------------------
    // Quest management
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Activity log
    // -------------------------------------------------------------------------

    public void addLogEntry(TownLogEntry entry) {
        if (activityLog.size() >= LOG_MAX) activityLog.pollFirst();
        activityLog.addLast(entry);
    }

    public List<TownLogEntry> getActivityLog() {
        return List.copyOf(activityLog);
    }

    public void addChatSubscriber(UUID playerId)    { chatSubscribers.add(playerId); }
    public void removeChatSubscriber(UUID playerId) { chatSubscribers.remove(playerId); }
    public boolean isChatSubscriber(UUID playerId)  { return chatSubscribers.contains(playerId); }
    public Set<UUID> getChatSubscribers()           { return Collections.unmodifiableSet(chatSubscribers); }

    public List<Quest> getActiveQuests()                    { return questState.getActiveQuests(); }
    public void addQuest(Quest q)                           { questState.addQuest(q); }
    public void removeQuest(String questId)                 { questState.removeQuest(questId); }
    public Map<String, Long> getQuestDefLastCompleted()     { return questState.getQuestDefLastCompleted(); }
    public boolean cleanupOrphanedQuestData(Set<String> v)  { return questState.cleanupOrphanedQuestData(v); }

    // Tries to add item to town stock without checking the accepted set.
    // Used after quest consumption to route any remainder into stock.
    public int tryAddToStockUnchecked(Item item, int amount) {
        TownInventory inv = getTownInventory();
        int maxStock = inv.getMaxStock(item);
        if (maxStock == 0) maxStock = 999;
        int room = maxStock - inv.getStock(item);
        if (room <= 0) return 0;
        int toAdd = Math.min(amount, room);
        inv.addStock(List.of(new ItemCost(item, toAdd)));
        return toAdd;
    }

    // Collects all items produced or transformed by currently placed buildings in this town.
    // Used server-side to validate deposit requests.
    public Set<Item> buildAcceptedItemSet() {
        Set<Item> accepted = new HashSet<>();
        for (PlacedBuilding b : buildings) {
            BuildingDataHandler.get(b.defId).ifPresent(def -> {
                def.production.forEach(p -> accepted.add(p.item()));
                def.transformations.forEach(t -> accepted.add(t.outputItem()));
            });
        }
        return accepted;
    }

    public Map<Item, Integer> getReserveStock() { return reserveStock; }

    public List<PlacedBuilding> getBuildings() { return buildings; }

    public List<UUID> getNpcsByJob(String jobId)           { return npcState.getNpcsByJob(jobId); }
    public int getTargetNpcCount(String jobId)             { return npcState.getTargetNpcCount(jobId); }
    public Map<String, Integer> getTargetNpcCounts()       { return npcState.getTargetNpcCounts(); }
    public void incrementTargetNpcCount(String jobId)      { npcState.incrementTargetNpcCount(jobId); }
    public void setNpcIdAtSlot(String j, int s, UUID id)   { npcState.setNpcIdAtSlot(j, s, id); }
    public int getNpcSlot(String jobId, UUID id)           { return npcState.getNpcSlot(jobId, id); }
    public UUID getNpcAtSlot(String jobId, int slot)       { return npcState.getNpcAtSlot(jobId, slot); }

    public void setActiveBuild(int slot, ActiveBuildState s)    { queueState.setActiveBuild(slot, s); }
    public void clearActiveBuild(int slot)                      { queueState.clearActiveBuild(slot); }
    public ActiveBuildState getActiveBuild(int slot)            { return queueState.getActiveBuild(slot); }
    public Map<Integer, ActiveBuildState> getActiveBuilds()     { return queueState.getActiveBuilds(); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        tag.put("NpcState", npcState.toNbt());
        ListTag buildingsTag = new ListTag();
        buildings.forEach(b -> buildingsTag.add(b.toNbt()));
        tag.put("Buildings", buildingsTag);
        ListTag connTag = new ListTag();
        freeConnections.forEach(c -> connTag.add(connectionToNbt(c)));
        tag.put("FreeConnections", connTag);
        CompoundTag reserveTag = new CompoundTag();
        reserveStock.forEach((item, qty) ->
            reserveTag.putInt(BuiltInRegistries.ITEM.getKey(item).toString(), qty));
        tag.put("ReserveStock", reserveTag);
        ListTag zonesTag = new ListTag();
        for (BoundingBox bb : blockedZones) {
            CompoundTag zTag = new CompoundTag();
            zTag.putInt("MinX", bb.minX()); zTag.putInt("MinY", bb.minY()); zTag.putInt("MinZ", bb.minZ());
            zTag.putInt("MaxX", bb.maxX()); zTag.putInt("MaxY", bb.maxY()); zTag.putInt("MaxZ", bb.maxZ());
            zonesTag.add(zTag);
        }
        tag.put("BlockedZones", zonesTag);
        ListTag ucTag = new ListTag();
        for (UnderConstructionEntry uc : underConstruction) {
            if (uc.bb() == null) continue;
            CompoundTag ucEntry = new CompoundTag();
            ucEntry.putString("DefId", uc.defId());
            ucEntry.put("Pos", NbtUtils.writeBlockPos(uc.worldPos()));
            ucEntry.putString("Rotation", uc.rotation().name());
            ucEntry.putInt("MinX", uc.bb().minX()); ucEntry.putInt("MinY", uc.bb().minY()); ucEntry.putInt("MinZ", uc.bb().minZ());
            ucEntry.putInt("MaxX", uc.bb().maxX()); ucEntry.putInt("MaxY", uc.bb().maxY()); ucEntry.putInt("MaxZ", uc.bb().maxZ());
            ucTag.add(ucEntry);
        }
        tag.put("UnderConstruction", ucTag);
        tag.put("EraState", eraState.toNbt());
        tag.put("QuestState", questState.toNbt());
        tag.put("QueueState", queueState.toNbt());
        if (!activityLog.isEmpty()) {
            ListTag logTag = new ListTag();
            for (TownLogEntry e : activityLog) {
                CompoundTag lt = new CompoundTag();
                lt.putString("Type", e.type().name());
                lt.putString("Param", e.param());
                lt.putLong("Tick", e.gameTick());
                logTag.add(lt);
            }
            tag.put("ActivityLog", logTag);
        }
        if (!chatSubscribers.isEmpty()) {
            ListTag subsTag = new ListTag();
            chatSubscribers.forEach(id -> subsTag.add(StringTag.valueOf(id.toString())));
            tag.put("ChatSubscribers", subsTag);
        }
        tag.putLong("CpInsertionCounter", cpInsertionCounter);
        if (activeContract != null) {
            ListTag contractList = new ListTag();
            for (ContractEntry e : activeContract) contractList.add(e.toNbt());
            tag.put("ActiveContract", contractList);

            CompoundTag cStock = new CompoundTag();
            contractStock.forEach((item, count) ->
                cStock.putInt(BuiltInRegistries.ITEM.getKey(item).toString(), count));
            tag.put("ContractStock", cStock);
        }
        if (medalClaimed) tag.putBoolean("MedalClaimed", true);
        if (depositedMedalIds != null) {
            ListTag medalList = new ListTag();
            for (String id : depositedMedalIds) medalList.add(StringTag.valueOf(id));
            tag.put("MedalReceivedIds", medalList);
        }
        return tag;
    }

    public static Town fromNbt(CompoundTag tag) {
        Town town = new Town();
        town.name = tag.contains("Name") ? tag.getString("Name") : "Unknown Town";
        town.npcState = TownNpcState.fromNbt(tag.contains("NpcState") ? tag.getCompound("NpcState") : new CompoundTag());
        tag.getList("Buildings", Tag.TAG_COMPOUND)
            .forEach(t -> town.buildings.add(PlacedBuilding.fromNbt((CompoundTag) t)));
        tag.getList("FreeConnections", Tag.TAG_COMPOUND)
            .forEach(t -> town.freeConnections.add(connectionFromNbt((CompoundTag) t)));
        town.cpInsertionCounter = tag.contains("CpInsertionCounter") ? tag.getLong("CpInsertionCounter") : (long) town.freeConnections.size();
        if (tag.contains("ReserveStock")) {
            CompoundTag reserveTag = tag.getCompound("ReserveStock");
            for (String key : reserveTag.getAllKeys()) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(key));
                town.reserveStock.put(item, reserveTag.getInt(key));
            }
        }
        tag.getList("BlockedZones", Tag.TAG_COMPOUND).forEach(t -> {
            CompoundTag zTag = (CompoundTag) t;
            town.blockedZones.add(new BoundingBox(
                zTag.getInt("MinX"), zTag.getInt("MinY"), zTag.getInt("MinZ"),
                zTag.getInt("MaxX"), zTag.getInt("MaxY"), zTag.getInt("MaxZ")
            ));
        });
        if (tag.contains("UnderConstruction")) {
            tag.getList("UnderConstruction", Tag.TAG_COMPOUND).forEach(t -> {
                CompoundTag uc = (CompoundTag) t;
                String defId = uc.getString("DefId");
                BlockPos pos = NbtUtils.readBlockPos(uc.getCompound("Pos"));
                Rotation rotation;
                try { rotation = Rotation.valueOf(uc.getString("Rotation")); }
                catch (IllegalArgumentException e) { rotation = Rotation.NONE; }
                BoundingBox bb = new BoundingBox(
                    uc.getInt("MinX"), uc.getInt("MinY"), uc.getInt("MinZ"),
                    uc.getInt("MaxX"), uc.getInt("MaxY"), uc.getInt("MaxZ")
                );
                town.underConstruction.add(new UnderConstructionEntry(defId, pos, bb, rotation));
            });
        }
        town.eraState = TownEraState.fromNbt(tag.contains("EraState") ? tag.getCompound("EraState") : new CompoundTag());
        town.questState = TownQuestState.fromNbt(tag.contains("QuestState") ? tag.getCompound("QuestState") : new CompoundTag());
        town.queueState = TownQueueState.fromNbt(tag.contains("QueueState") ? tag.getCompound("QueueState") : new CompoundTag());
        if (tag.contains("ActivityLog")) {
            tag.getList("ActivityLog", Tag.TAG_COMPOUND).forEach(t -> {
                CompoundTag lt = (CompoundTag) t;
                try {
                    TownLogEntry.TownLogType type = TownLogEntry.TownLogType.valueOf(lt.getString("Type"));
                    town.activityLog.addLast(new TownLogEntry(type, lt.getString("Param"), lt.getLong("Tick")));
                } catch (IllegalArgumentException ignored) {}
            });
        }
        if (tag.contains("ChatSubscribers")) {
            tag.getList("ChatSubscribers", Tag.TAG_STRING).forEach(t -> {
                try { town.chatSubscribers.add(UUID.fromString(t.getAsString())); }
                catch (IllegalArgumentException ignored) {}
            });
        }
        if (tag.contains("ActiveContract")) {
            ListTag contractList = tag.getList("ActiveContract", Tag.TAG_COMPOUND);
            List<ContractEntry> entries = new ArrayList<>();
            for (int i = 0; i < contractList.size(); i++)
                entries.add(ContractEntry.fromNbt(contractList.getCompound(i)));
            town.activeContract = entries;
        }
        if (tag.contains("ContractStock")) {
            CompoundTag cStock = tag.getCompound("ContractStock");
            for (String key : cStock.getAllKeys()) {
                Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(key));
                if (item != null) town.contractStock.put(item, cStock.getInt(key));
            }
        }
        if (tag.contains("MedalClaimed")) town.medalClaimed = tag.getBoolean("MedalClaimed");
        if (tag.contains("MedalReceivedIds")) {
            ListTag medalList = tag.getList("MedalReceivedIds", Tag.TAG_STRING);
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < medalList.size(); i++) ids.add(medalList.getString(i));
            town.depositedMedalIds = ids;
        } else if (tag.getBoolean("MedalReceived")) {
            // Backwards compat: old saves had boolean only, no IDs stored
            town.depositedMedalIds = new HashSet<>();
        }
        return town;
    }

    private static CompoundTag connectionToNbt(ConnectionPoint c) {
        CompoundTag tag = new CompoundTag();
        tag.put("Pos", NbtUtils.writeBlockPos(c.pos()));
        tag.putString("Dir", c.direction().getName());
        tag.putString("Pool", c.targetName());
        tag.putLong("Order", c.insertionOrder());
        return tag;
    }

    private static ConnectionPoint connectionFromNbt(CompoundTag tag) {
        BlockPos pos = NbtUtils.readBlockPos(tag.getCompound("Pos"));
        net.minecraft.core.Direction dir = net.minecraft.core.Direction.byName(tag.getString("Dir"));
        String pool = tag.getString("Pool");
        long order = tag.getLong("Order");
        return new ConnectionPoint(pos, dir != null ? dir : net.minecraft.core.Direction.NORTH, pool, order);
    }
}
