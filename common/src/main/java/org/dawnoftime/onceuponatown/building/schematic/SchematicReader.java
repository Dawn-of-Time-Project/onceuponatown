package org.dawnoftime.onceuponatown.building.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import org.dawnoftime.onceuponatown.building.ConstructionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class SchematicReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchematicReader.class);

    // Pair of the regular block list and barrier marker positions (both rotation-applied, relative to origin).
    public record SchematicReadResult(List<SchematicBlock> blocks, List<BlockPos> markerPositions) {}

    // Extracts a sorted, rotation-applied block list from an already-loaded StructureTemplate.
    // Filters out: AIR, CAVE_AIR, STRUCTURE_VOID, JIGSAW, BARRIER.
    // BARRIER blocks are collected separately into markerPositions and are never placed in the world.
    // Block positions in the returned lists are rotated (relative to origin 0,0,0 of the template).
    // Add finalPlacementPos to get world coordinates.
    public static SchematicReadResult readSortedBlocks(StructureTemplate template, Rotation rotation) {
        CompoundTag nbt;
        try {
            nbt = template.save(new CompoundTag());
        } catch (Exception e) {
            LOGGER.error("[OUAT] Failed to serialize StructureTemplate to NBT for block extraction", e);
            return new SchematicReadResult(List.of(), List.of());
        }

        HolderGetter<Block> blockGetter = BuiltInRegistries.BLOCK.asLookup();

        // Support both single palette and multi-palette NBT formats.
        ListTag paletteTag;
        if (nbt.contains("palettes", 9)) {
            paletteTag = nbt.getList("palettes", 9).getList(0);
        } else {
            paletteTag = nbt.getList("palette", 10);
        }
        ListTag blocksTag = nbt.getList("blocks", 10);

        if (paletteTag.isEmpty() || blocksTag.isEmpty()) return new SchematicReadResult(List.of(), List.of());

        // Build palette array: index -> BlockState.
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            palette[i] = NbtUtils.readBlockState(blockGetter, paletteTag.getCompound(i));
        }

        List<SchematicBlock> result = new ArrayList<>(blocksTag.size());
        List<BlockPos> markerPositions = new ArrayList<>();
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag blockTag = blocksTag.getCompound(i);
            int stateIdx = blockTag.getInt("state");
            if (stateIdx < 0 || stateIdx >= palette.length) continue;

            BlockState rawState = palette[stateIdx];
            Block block = rawState.getBlock();

            // Skip non-placeable blocks.
            if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.STRUCTURE_VOID) continue;

            // Barrier blocks are stand markers: collect position for NPC/stand discovery AND
            // add as AIR so the builder explicitly clears terrain at that position.
            if (block == Blocks.BARRIER) {
                ListTag markerPosTag = blockTag.getList("pos", 3);
                BlockPos markerLocal = new BlockPos(markerPosTag.getInt(0), markerPosTag.getInt(1), markerPosTag.getInt(2));
                BlockPos markerRotated = StructureTemplate.transform(markerLocal, Mirror.NONE, rotation, BlockPos.ZERO);
                markerPositions.add(markerRotated);
                result.add(new SchematicBlock(markerRotated, Blocks.AIR.defaultBlockState(), null));
                continue;
            }

            // Jigsaw blocks are connector markers: replace them with their final_state instead of skipping.
            if (block == Blocks.JIGSAW) {
                CompoundTag beTag = blockTag.contains("nbt") ? blockTag.getCompound("nbt") : null;
                if (beTag != null && beTag.contains("final_state")) {
                    String finalStateStr = beTag.getString("final_state");
                    String blockName = finalStateStr.contains("[") ? finalStateStr.substring(0, finalStateStr.indexOf('[')) : finalStateStr;
                    ResourceLocation rl = ResourceLocation.tryParse(blockName);
                    if (rl != null) {
                        Block finalBlock = BuiltInRegistries.BLOCK.get(rl);
                        if (finalBlock != Blocks.AIR) {
                            ListTag posTag2 = blockTag.getList("pos", 3);
                            BlockPos localPos2 = new BlockPos(posTag2.getInt(0), posTag2.getInt(1), posTag2.getInt(2));
                            BlockPos rotatedPos2 = StructureTemplate.transform(localPos2, Mirror.NONE, rotation, BlockPos.ZERO);
                            result.add(new SchematicBlock(rotatedPos2, finalBlock.defaultBlockState(), null));
                        }
                    }
                }
                continue;
            }

            ListTag posTag = blockTag.getList("pos", 3);
            BlockPos localPos = new BlockPos(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2));

            // Apply rotation to both position (around origin) and block state.
            BlockPos rotatedPos = StructureTemplate.transform(localPos, Mirror.NONE, rotation, BlockPos.ZERO);
            BlockState rotatedState = rawState.rotate(rotation);

            // Block entity NBT is stored under "nbt" in vanilla structure format.
            CompoundTag blockNbt = blockTag.contains("nbt") ? blockTag.getCompound("nbt") : null;

            result.add(new SchematicBlock(rotatedPos, rotatedState, blockNbt));
        }

        ConstructionUtils.sortBlocks(result);
        return new SchematicReadResult(result, markerPositions);
    }

    // Extracts entities from a StructureTemplate and returns them with absolute world positions.
    // Applies rotation to both position and entity yaw so entities face the correct direction.
    // Entities with an unknown or missing "id" field are silently skipped.
    public static List<SchematicEntity> readEntities(StructureTemplate template, Rotation rotation, BlockPos origin) {
        CompoundTag nbt;
        try {
            nbt = template.save(new CompoundTag());
        } catch (Exception e) {
            LOGGER.error("[OUAT] Failed to serialize StructureTemplate to NBT for entity extraction", e);
            return List.of();
        }

        ListTag entitiesTag = nbt.getList("entities", 10);
        if (entitiesTag.isEmpty()) return List.of();

        float yawOffset = switch (rotation) {
            case CLOCKWISE_90 -> 90.0f;
            case CLOCKWISE_180 -> 180.0f;
            case COUNTERCLOCKWISE_90 -> 270.0f;
            default -> 0.0f;
        };

        List<SchematicEntity> result = new ArrayList<>();
        for (int i = 0; i < entitiesTag.size(); i++) {
            CompoundTag entry = entitiesTag.getCompound(i);
            if (!entry.contains("pos") || !entry.contains("nbt")) continue;

            CompoundTag entityNbt = entry.getCompound("nbt");
            if (!entityNbt.contains("id")) continue;

            ListTag posTag = entry.getList("pos", 6); // 6 = TAG_Double
            if (posTag.size() < 3) continue;

            double lx = posTag.getDouble(0);
            double ly = posTag.getDouble(1);
            double lz = posTag.getDouble(2);

            // Rotate block-aligned portion the same way blocks are rotated, preserve fractional offset.
            BlockPos localBlock = new BlockPos((int) Math.floor(lx), (int) Math.floor(ly), (int) Math.floor(lz));
            BlockPos rotated = StructureTemplate.transform(localBlock, Mirror.NONE, rotation, BlockPos.ZERO);

            double wx = origin.getX() + rotated.getX() + (lx - Math.floor(lx));
            double wy = origin.getY() + rotated.getY() + (ly - Math.floor(ly));
            double wz = origin.getZ() + rotated.getZ() + (lz - Math.floor(lz));

            CompoundTag entityTag = entityNbt.copy();
            if (yawOffset != 0.0f && entityTag.contains("Rotation", 9)) {
                ListTag rotTag = entityTag.getList("Rotation", 5); // 5 = TAG_Float
                if (rotTag.size() >= 1) {
                    ListTag newRot = new ListTag();
                    newRot.add(FloatTag.valueOf(rotTag.getFloat(0) + yawOffset));
                    if (rotTag.size() >= 2) newRot.add(FloatTag.valueOf(rotTag.getFloat(1)));
                    entityTag.put("Rotation", newRot);
                }
            }

            result.add(new SchematicEntity(new Vec3(wx, wy, wz), entityTag));
        }
        return result;
    }
}
