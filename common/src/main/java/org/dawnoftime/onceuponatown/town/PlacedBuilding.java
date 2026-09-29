package org.dawnoftime.onceuponatown.town;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlacedBuilding {
    private static final Logger LOGGER = LoggerFactory.getLogger(PlacedBuilding.class);

    public final String defId;
    public final BlockPos worldPos;
    // World bounding box of this building. Null for buildings loaded from saves that predate this field.
    public final BoundingBox bb;
    // Rotation applied when this building was placed. NONE for saves that predate this field.
    public final Rotation rotation;
    // World positions of obstacle blocks recorded at placement time for SITE_CLEARANCE quest verification.
    // Populated only for terrain-matched buildings with obstacle_blocks defined. Empty otherwise.
    public final List<BlockPos> obstaclePositions;
    // Road-facing connection point used when the NPC originally built this building.
    // Always set for NPC-built buildings; null for worldgen starters (ChunkGeneratorMixin).
    public final @Nullable BlockPos entryPos;
    // World-space barrier positions extracted at build/upgrade/repair time. Not persisted.
    private List<BlockPos> barrierPositions = List.of();
    // Physical market stands discovered from barrier positions. Persisted to avoid re-scan on load.
    private final List<StandSlot> stands = new ArrayList<>();

    // Per-instance production multiplier. 1.0 = normal. Set to 1.15 for orientation bootstrap buildings.
    private double instanceProductionMultiplier = 1.0;
    private int upgradeLevel = 0;
    // Default true: safe on first load, updated each dawn by FoodManager.
    private boolean herdFed = true;
    private final Map<Item, Integer> stock = new HashMap<>();

    public PlacedBuilding(String defId, BlockPos worldPos, BoundingBox bb, Rotation rotation,
                          List<BlockPos> obstaclePositions, @Nullable BlockPos entryPos) {
        this.defId = defId;
        this.worldPos = worldPos;
        this.bb = bb;
        this.rotation = rotation;
        this.obstaclePositions = obstaclePositions != null ? List.copyOf(obstaclePositions) : List.of();
        this.entryPos = entryPos;
    }

    // Called by ProductionManager - clamps to per-building resolvedCapacity, returns true if any stock was added
    public boolean produce(net.minecraft.world.item.Item item, int boostedAmount, int resolvedCapacity) {
        int current = stock.getOrDefault(item, 0);
        if (current >= resolvedCapacity) return false;
        stock.put(item, Math.min(current + boostedAmount, resolvedCapacity));
        return true;
    }

    // Called by TownInventory.removeStock() - drains up to requested amount
    public int drain(Item item, int requested) {
        int available = stock.getOrDefault(item, 0);
        int taken = Math.min(available, requested);
        stock.put(item, available - taken);
        return taken;
    }

    // Called by Town.addStock() (player command) - bypasses capacity cap intentionally
    public void forceAdd(Item item, int quantity) {
        stock.merge(item, quantity, Integer::sum);
    }

    public int getStock(Item item) { return stock.getOrDefault(item, 0); }
    public String getDefId() { return defId; }

    public double getInstanceProductionMultiplier() { return instanceProductionMultiplier; }
    public void setInstanceProductionMultiplier(double value) { this.instanceProductionMultiplier = value; }

    public int getUpgradeLevel() { return upgradeLevel; }
    public void setUpgradeLevel(int level) { this.upgradeLevel = level; }

    public boolean isHerdFed() { return herdFed; }
    public void setHerdFed(boolean fed) { this.herdFed = fed; }

    // Returns the effective village-wide production bonus contributed by this building at its current upgrade level.
    public double resolvedProductionBonus(org.dawnoftime.onceuponatown.town.BuildingDef def) {
        double bonus = def.productionBonus;
        int capped = Math.min(upgradeLevel, def.upgrades.size());
        for (int i = 0; i < capped; i++) {
            bonus += def.upgrades.get(i).productionBonusAdd();
        }
        return bonus;
    }

    // Returns the extra capacity stacks this building adds to every productive building in the village.
    public int resolvedStockBonus(org.dawnoftime.onceuponatown.town.BuildingDef def) {
        int bonus = def.stockBonus;
        int capped = Math.min(upgradeLevel, def.upgrades.size());
        for (int i = 0; i < capped; i++) {
            bonus += def.upgrades.get(i).stockBonusAdd();
        }
        return bonus;
    }

    public void setBarrierPositions(List<BlockPos> positions) {
        this.barrierPositions = positions != null ? List.copyOf(positions) : List.of();
    }

    // Scans world around each barrier position to match a StandDef by its associated_block.
    // Called at end of build/upgrade/repair onComplete(), once all blocks are placed.
    public void discoverStands(ServerLevel level, BuildingDef def) {
        stands.clear();
        if (def.stands.isEmpty() || barrierPositions.isEmpty()) return;
        for (BlockPos barrierPos : barrierPositions) {
            boolean matched = false;
            for (BuildingDef.StandDef standDef : def.stands) {
                if (matched) break;
                int r = standDef.radius();
                for (int dx = -r; dx <= r && !matched; dx++) {
                    for (int dy = -r; dy <= r && !matched; dy++) {
                        for (int dz = -r; dz <= r && !matched; dz++) {
                            BlockPos assocPos = barrierPos.offset(dx, dy, dz);
                            if (level.getBlockState(assocPos).is(standDef.associatedBlock())) {
                                stands.add(new StandSlot(barrierPos, standDef.type(), null, standDef.toolItem(), assocPos));
                                matched = true;
                            }
                        }
                    }
                }
            }
            if (!matched) {
                LOGGER.warn("[OUAT] Stand barrier at {} in building '{}' matched no stand type", barrierPos, defId);
            }
        }
    }

    // Acquires the first free stand of the given type. Sets occupant and returns the slot, or null if none free.
    // Also returns a slot already held by npcUuid so NPCs can reclaim their stand after a world reload.
    public @Nullable StandSlot acquireStand(String type, UUID npcUuid) {
        for (StandSlot slot : stands) {
            if (slot.type.equals(type) && (slot.occupant == null || npcUuid.equals(slot.occupant))) {
                slot.occupant = npcUuid;
                return slot;
            }
        }
        return null;
    }

    // Releases the stand held by npcUuid. Returns true if a slot was found and freed.
    public boolean releaseStand(UUID npcUuid) {
        for (StandSlot slot : stands) {
            if (npcUuid.equals(slot.occupant)) {
                slot.occupant = null;
                return true;
            }
        }
        return false;
    }

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putString("DefId", defId);
        tag.putLong("WorldPos", worldPos.asLong());
        CompoundTag stockTag = new CompoundTag();
        stock.forEach((item, qty) -> {
            String key = BuiltInRegistries.ITEM.getKey(item).toString();
            stockTag.putInt(key, qty);
        });
        tag.put("Stock", stockTag);
        if (bb != null) {
            CompoundTag bbTag = new CompoundTag();
            bbTag.putInt("MinX", bb.minX());
            bbTag.putInt("MinY", bb.minY());
            bbTag.putInt("MinZ", bb.minZ());
            bbTag.putInt("MaxX", bb.maxX());
            bbTag.putInt("MaxY", bb.maxY());
            bbTag.putInt("MaxZ", bb.maxZ());
            tag.put("BoundingBox", bbTag);
        }
        tag.putInt("Rotation", rotation.ordinal());
        if (!obstaclePositions.isEmpty()) {
            long[] longs = new long[obstaclePositions.size()];
            for (int i = 0; i < obstaclePositions.size(); i++) longs[i] = obstaclePositions.get(i).asLong();
            tag.putLongArray("ObstaclePositions", longs);
        }
        if (instanceProductionMultiplier != 1.0)
            tag.putDouble("InstanceProductionMultiplier", instanceProductionMultiplier);
        if (upgradeLevel != 0)
            tag.putInt("UpgradeLevel", upgradeLevel);
        if (!herdFed)
            tag.putBoolean("HerdFed", false);
        if (entryPos != null) tag.putLong("EntryPos", entryPos.asLong());
        if (!stands.isEmpty()) {
            ListTag standsTag = new ListTag();
            for (StandSlot slot : stands) {
                CompoundTag st = new CompoundTag();
                st.putString("Type", slot.type);
                st.putInt("PosX", slot.position.getX());
                st.putInt("PosY", slot.position.getY());
                st.putInt("PosZ", slot.position.getZ());
                if (slot.occupant != null) {
                    st.putLong("OccupantMost", slot.occupant.getMostSignificantBits());
                    st.putLong("OccupantLeast", slot.occupant.getLeastSignificantBits());
                }
                if (slot.toolItem != null) st.putString("ToolItem", slot.toolItem);
                if (slot.associatedBlockPos != null) {
                    st.putInt("AssocPosX", slot.associatedBlockPos.getX());
                    st.putInt("AssocPosY", slot.associatedBlockPos.getY());
                    st.putInt("AssocPosZ", slot.associatedBlockPos.getZ());
                }
                standsTag.add(st);
            }
            tag.put("Stands", standsTag);
        }
        return tag;
    }

    public static PlacedBuilding fromNbt(CompoundTag tag) {
        String defId = tag.getString("DefId");
        BlockPos pos = BlockPos.of(tag.getLong("WorldPos"));
        BoundingBox bb = null;
        if (tag.contains("BoundingBox")) {
            CompoundTag bbTag = tag.getCompound("BoundingBox");
            bb = new BoundingBox(
                bbTag.getInt("MinX"), bbTag.getInt("MinY"), bbTag.getInt("MinZ"),
                bbTag.getInt("MaxX"), bbTag.getInt("MaxY"), bbTag.getInt("MaxZ")
            );
        }
        Rotation rotation = tag.contains("Rotation")
            ? Rotation.values()[tag.getInt("Rotation")]
            : Rotation.NONE;
        List<BlockPos> obstaclePositions = new ArrayList<>();
        if (tag.contains("ObstaclePositions")) {
            for (long l : tag.getLongArray("ObstaclePositions")) obstaclePositions.add(BlockPos.of(l));
        }
        BlockPos entryPos = tag.contains("EntryPos") ? BlockPos.of(tag.getLong("EntryPos")) : null;
        PlacedBuilding b = new PlacedBuilding(defId, pos, bb, rotation, obstaclePositions, entryPos);
        if (tag.contains("InstanceProductionMultiplier"))
            b.instanceProductionMultiplier = tag.getDouble("InstanceProductionMultiplier");
        if (tag.contains("UpgradeLevel"))
            b.upgradeLevel = tag.getInt("UpgradeLevel");
        b.herdFed = !tag.contains("HerdFed") || tag.getBoolean("HerdFed");
        CompoundTag stockTag = tag.getCompound("Stock");
        for (String key : stockTag.getAllKeys()) {
            Item item = BuiltInRegistries.ITEM.get(new ResourceLocation(key));
            b.stock.put(item, stockTag.getInt(key));
        }
        if (tag.contains("Stands")) {
            ListTag standsTag = tag.getList("Stands", 10);
            for (int i = 0; i < standsTag.size(); i++) {
                CompoundTag st = standsTag.getCompound(i);
                String type = st.getString("Type");
                BlockPos standPos = new BlockPos(st.getInt("PosX"), st.getInt("PosY"), st.getInt("PosZ"));
                UUID occupant = st.contains("OccupantMost")
                    ? new UUID(st.getLong("OccupantMost"), st.getLong("OccupantLeast")) : null;
                String toolItem = st.contains("ToolItem") ? st.getString("ToolItem") : null;
                BlockPos assocPos = st.contains("AssocPosX")
                    ? new BlockPos(st.getInt("AssocPosX"), st.getInt("AssocPosY"), st.getInt("AssocPosZ")) : null;
                b.stands.add(new StandSlot(standPos, type, occupant, toolItem, assocPos));
            }
        }
        return b;
    }
}
