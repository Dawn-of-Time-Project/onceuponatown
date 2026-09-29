package org.dawnoftime.onceuponatown.entity.ai.shared;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import org.dawnoftime.onceuponatown.building.schematic.ConnectorReader;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;

/**
 * Navigates an NPC to the doorstep of a building: Y+1 above the receiver jigsaw (minecraft:empty).
 * Uses exact-nav (no arrival radius).
 *
 * Entry resolution:
 *   NPC-built buildings: entryPos populated at construction time
 *   Worldgen buildings: read receiver jigsaw from template at runtime
 *   Lone buildings (no jigsaw): navigate to structure origin
 */
public class BuildingEntryNav {

    /**
     * Resolves the doorstep position (Y+1 above entry jigsaw) for a placed building.
     * Always returns a non-null position.
     */
    public static BlockPos resolveEntryPos(ServerLevel level, PlacedBuilding building,
                                           ResourceLocation nbt, Rotation rotation) {
        // NPC-built buildings: entryPos populated at construction time
        if (building.entryPos != null) return building.entryPos.above();
        // Worldgen buildings: read receiver jigsaw from template
        BlockPos jigsaw = ConnectorReader.readEntryJigsawPos(level, building.worldPos, nbt, rotation);
        if (jigsaw != null) return jigsaw.above();
        // Lone buildings (no jigsaw): navigate to structure origin
        return building.worldPos.above();
    }

    /**
     * Convenience overload: resolves the doorstep using the building's registered def.
     * Always returns a non-null position.
     */
    public static BlockPos resolveEntryPos(ServerLevel level, PlacedBuilding building) {
        // NPC-built buildings: entryPos populated at construction time
        if (building.entryPos != null) return building.entryPos.above();
        // Worldgen buildings: read receiver jigsaw from template
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def != null) {
            BlockPos jigsaw = ConnectorReader.readEntryJigsawPos(level, building.worldPos, def.nbt, building.rotation);
            if (jigsaw != null) return jigsaw.above();
        }
        // Lone buildings (no jigsaw): navigate to structure origin
        return building.worldPos.above();
    }

    private final GoToPosition nav;

    public BuildingEntryNav(Npc npc, BlockPos entryTarget, double speed) {
        this.nav = new GoToPosition(npc, entryTarget, speed);
    }

    /** Returns true when the NPC has reached the doorstep. */
    public boolean tick() { return nav.tick(); }
}
