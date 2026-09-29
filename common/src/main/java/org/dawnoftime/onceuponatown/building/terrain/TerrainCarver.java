package org.dawnoftime.onceuponatown.building.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.util.HashSet;
import java.util.Set;

public class TerrainCarver {

    private static final int MAX_ANCHOR_DEPTH = 12;

    // Solid terrain blocks eligible for carving (Pass A) and replaceable by Pass B anchor fill.
    private static final Set<Block> CARVE_SET = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT,
            Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.CHERRY_LOG,
            Blocks.PODZOL, Blocks.STONE, Blocks.ANDESITE, Blocks.DIORITE,
            Blocks.GRANITE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRAVEL,
            Blocks.SAND, Blocks.SANDSTONE, Blocks.RED_SAND, Blocks.RED_SANDSTONE,
            Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.NETHER_GOLD_ORE, Blocks.NETHER_QUARTZ_ORE,
            Blocks.CALCITE, Blocks.DRIPSTONE_BLOCK, Blocks.MUD,
            Blocks.SNOW_BLOCK, Blocks.SNOW
    );

    // Natural surface clutter treated as replaceable terrain in Pass C outline fill.
    private static final Set<Block> TERRAIN_NOISE_SET = Set.of(
            Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES,
            Blocks.JUNGLE_LEAVES, Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES,
            Blocks.GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN,
            Blocks.DANDELION, Blocks.POPPY, Blocks.BLUE_ORCHID, Blocks.ALLIUM,
            Blocks.AZURE_BLUET, Blocks.RED_TULIP, Blocks.ORANGE_TULIP,
            Blocks.WHITE_TULIP, Blocks.PINK_TULIP, Blocks.OXEYE_DAISY,
            Blocks.CORNFLOWER, Blocks.LILY_OF_THE_VALLEY, Blocks.SUNFLOWER,
            Blocks.LILAC, Blocks.ROSE_BUSH, Blocks.PEONY,
            Blocks.OAK_LOG, Blocks.OAK_WOOD, Blocks.BIRCH_LOG, Blocks.BIRCH_WOOD,
            Blocks.SPRUCE_LOG, Blocks.SPRUCE_WOOD, Blocks.JUNGLE_LOG, Blocks.JUNGLE_WOOD,
            Blocks.ACACIA_LOG, Blocks.ACACIA_WOOD, Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_WOOD,
            Blocks.CHERRY_LOG, Blocks.CHERRY_WOOD, Blocks.CHERRY_LEAVES,
            Blocks.VINE, Blocks.SUGAR_CANE, Blocks.CACTUS, Blocks.BAMBOO,
            Blocks.HANGING_ROOTS
    );

    // Returns the local Y of the receiver jigsaw block (pool = "minecraft:empty") in the template.
    // This is the ground connection level -- the true separator between surface and underground content.
    // Defaults to 0 for buildings authored with the jigsaw at local Y = 0 (all legacy buildings).
    public static int readJigsawFloorY(StructureTemplate template) {
        try {
            CompoundTag nbt = template.save(new CompoundTag());
            ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks  = nbt.getList("blocks",  Tag.TAG_COMPOUND);

            int jigsawPaletteIdx = -1;
            for (int i = 0; i < palette.size(); i++) {
                if ("minecraft:jigsaw".equals(palette.getCompound(i).getString("Name"))) {
                    jigsawPaletteIdx = i;
                    break;
                }
            }
            if (jigsawPaletteIdx < 0) return 0;

            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag entry = blocks.getCompound(i);
                if (entry.getInt("state") != jigsawPaletteIdx) continue;
                if (!entry.contains("nbt")) continue;
                String pool = entry.getCompound("nbt").getString("pool");
                if (pool.isEmpty() || "minecraft:empty".equals(pool)) {
                    ListTag pos = entry.getList("pos", Tag.TAG_INT);
                    return pos.getInt(1);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    // Called BEFORE NBT placement.
    // Carves solid terrain AND non-solid clutter above the jigsaw layer so they don't poke into the building interior.
    // Only carves columns that have at least one real (non-air, non-void) block above the jigsaw in the NBT.
    // This prevents destroying exterior terrain in open structures like farm fields.
    public static void prePlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation,
                                 int jigsawLocalY) {
        try {
            Vec3i size = template.getSize();
            int trueFloorY = origin.getY() + jigsawLocalY;
            int[] bounds = computeFootprint(origin, template, rotation);
            Set<Long> noTouchColumns = scanStructureVoidColumns(template, origin, rotation);
            Set<Long> occupiedColumns = scanOccupiedColumns(template, origin, rotation, jigsawLocalY);

            // The jigsaw layer (trueFloorY) is the ground connection and must never be carved.
            carveInterior(level, bounds[0], bounds[1], bounds[2], bounds[3],
                    trueFloorY + 1, size.getY() - jigsawLocalY - 1, noTouchColumns, occupiedColumns);

        } catch (Exception e) {
            // silently ignored -- terrain carve failure does not block placement
        }
    }

    public static void prePlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation) {
        prePlace(level, origin, template, rotation, readJigsawFloorY(template));
    }

    // Called AFTER NBT placement.
    // Pass B: fills air gaps under the actual solid footprint to prevent floating buildings.
    // Pass C: fills a 1-block organic outline around the building's actual shape.
    // Pass D: applies surface rules (grass/dirt/stone) over all blocks placed by B and C.
    // skipAnchorFill: when true, Pass B is skipped. Used for underground-foundation buildings
    // (mines) whose NBT already provides its own underground fill.
    public static void postPlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation,
                                  boolean skipAnchorFill, int jigsawLocalY) {
        try {
            int trueFloorY = origin.getY() + jigsawLocalY;

            Set<Long> noTouchColumns = scanStructureVoidColumns(template, origin, rotation);

            // Derive footprint from the jigsaw layer of the template.
            Set<Long> solidFootprint = scanOccupiedColumns(template, origin, rotation, jigsawLocalY);

            Set<Long> outline = buildOrganicOutline(solidFootprint);
            outline.removeAll(noTouchColumns);

            // Pass B: fills air gaps below the jigsaw layer to prevent floating buildings.
            // Skipped for buildings that carry their own underground foundation in the NBT.
            if (!skipAnchorFill) {
                passB_anchorFill(level, solidFootprint, trueFloorY);
            }

            solidFootprint.removeAll(noTouchColumns);
            passC_organicOutlineFill(level, outline, trueFloorY);
            passD_surfaceRules(level, solidFootprint, outline, trueFloorY);

        } catch (Exception e) {
            // silently ignored -- terrain fill failure does not block placement
        }
    }

    public static void postPlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation,
                                  boolean skipAnchorFill) {
        postPlace(level, origin, template, rotation, skipAnchorFill, readJigsawFloorY(template));
    }

    public static void postPlace(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation) {
        postPlace(level, origin, template, rotation, false, readJigsawFloorY(template));
    }

    // Instantly places all NBT blocks at localY < 0 (underground content).
    // Called after prePlace and postPlace so surface foundation fill already ran.
    // Rules (default): structure_void and air variants -> skipped (terrain preserved).
    //                  barrier -> placed as AIR (corridor clearing).
    //                  other blocks -> placed with rotation applied. Block-entity NBT is not restored.
    // carveAir: when true (underground-foundation buildings), air blocks are actively placed as AIR,
    // carving through any existing terrain inside the mine shaft. structure_void is still skipped.
    public static void placeUnderground(ServerLevel level, BlockPos origin, StructureTemplate template,
                                         Rotation rotation, boolean carveAir, int jigsawLocalY) {
        try {
            CompoundTag nbt = template.save(new CompoundTag());

            ListTag paletteTag = nbt.contains("palettes", 9)
                ? nbt.getList("palettes", 9).getList(0)
                : nbt.getList("palette", 10);
            ListTag blocksTag = nbt.getList("blocks", 10);
            if (paletteTag.isEmpty() || blocksTag.isEmpty()) return;

            HolderGetter<Block> blockGetter = BuiltInRegistries.BLOCK.asLookup();

            Set<Integer> voidIndices = new HashSet<>();   // always skipped
            Set<Integer> airIndices = new HashSet<>();    // skipped unless carveAir
            int barrierIndex = -1;
            BlockState[] palette = new BlockState[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                CompoundTag entry = paletteTag.getCompound(i);
                String name = entry.getString("Name");
                palette[i] = NbtUtils.readBlockState(blockGetter, entry);
                if ("minecraft:structure_void".equals(name)) {
                    voidIndices.add(i);
                } else if (name.endsWith("air")) {
                    airIndices.add(i);
                } else if ("minecraft:barrier".equals(name)) {
                    barrierIndex = i;
                }
            }

            for (int i = 0; i < blocksTag.size(); i++) {
                CompoundTag entry = blocksTag.getCompound(i);
                ListTag posTag = entry.getList("pos", Tag.TAG_INT);
                int localY = posTag.getInt(1);
                // Process only blocks strictly below the jigsaw connection layer.
                if (localY >= jigsawLocalY) continue;

                int stateIdx = entry.getInt("state");
                if (voidIndices.contains(stateIdx)) continue;
                if (airIndices.contains(stateIdx) && !carveAir) continue;

                BlockPos localPos = new BlockPos(posTag.getInt(0), localY, posTag.getInt(2));
                BlockPos rotatedPos = StructureTemplate.transform(localPos, Mirror.NONE, rotation, BlockPos.ZERO);
                BlockPos worldPos = origin.offset(rotatedPos);
                if (!level.isLoaded(worldPos)) continue;

                BlockState toPlace;
                if (airIndices.contains(stateIdx) || stateIdx == barrierIndex) {
                    toPlace = Blocks.AIR.defaultBlockState();
                } else {
                    toPlace = palette[stateIdx].rotate(rotation);
                }
                level.setBlock(worldPos, toPlace, Block.UPDATE_ALL);
            }
        } catch (Exception e) {
            // silently ignored -- underground placement failure does not block surface build
        }
    }

    public static void placeUnderground(ServerLevel level, BlockPos origin, StructureTemplate template,
                                         Rotation rotation, boolean carveAir) {
        placeUnderground(level, origin, template, rotation, carveAir, readJigsawFloorY(template));
    }

    public static void placeUnderground(ServerLevel level, BlockPos origin, StructureTemplate template, Rotation rotation) {
        placeUnderground(level, origin, template, rotation, false, readJigsawFloorY(template));
    }

    // Expands the solid footprint by 1 block in all 4 cardinal directions.
    // Only positions NOT already in the solid footprint are included (true outer border).
    private static Set<Long> buildOrganicOutline(Set<Long> solidFootprint) {
        Set<Long> outline = new HashSet<>();
        for (long key : solidFootprint) {
            int x = unpackX(key);
            int z = unpackZ(key);
            long n = packXZ(x + 1, z);
            long s = packXZ(x - 1, z);
            long e = packXZ(x, z + 1);
            long w = packXZ(x, z - 1);
            if (!solidFootprint.contains(n)) outline.add(n);
            if (!solidFootprint.contains(s)) outline.add(s);
            if (!solidFootprint.contains(e)) outline.add(e);
            if (!solidFootprint.contains(w)) outline.add(w);
        }
        return outline;
    }

    // Pass B: fills air/carveable gaps below each solid footprint column down to real ground.
    // All placed blocks are DIRT; Pass D corrects the surface type.
    private static void passB_anchorFill(ServerLevel level, Set<Long> solidFootprint, int floorY) {
        for (long key : solidFootprint) {
            int x = unpackX(key);
            int z = unpackZ(key);
            for (int scanY = floorY - 1; scanY >= floorY - MAX_ANCHOR_DEPTH; scanY--) {
                BlockPos pos = new BlockPos(x, scanY, z);
                if (!level.isLoaded(pos)) break;
                BlockState block = level.getBlockState(pos);
                // Stop when we hit solid immovable terrain (not in CARVE_SET).
                if (block.isSolid() && !CARVE_SET.contains(block.getBlock())) break;
                if (block.isAir() || CARVE_SET.contains(block.getBlock())) {
                    level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    // Pass C: fills air gaps in the 1-block organic outline down to real ground.
    // Does NOT replace existing solid terrain — only fills where there is air or clutter.
    private static void passC_organicOutlineFill(ServerLevel level, Set<Long> outline, int floorY) {
        for (long key : outline) {
            int x = unpackX(key);
            int z = unpackZ(key);
            for (int scanY = floorY - 1; scanY >= floorY - MAX_ANCHOR_DEPTH; scanY--) {
                BlockPos pos = new BlockPos(x, scanY, z);
                if (!level.isLoaded(pos)) break;
                BlockState block = level.getBlockState(pos);
                if (block.isAir() || TERRAIN_NOISE_SET.contains(block.getBlock())) {
                    level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
                } else {
                    break; // hit real terrain, stop
                }
            }
        }
    }

    // Pass D: applies MC-style surface rules (grass/dirt/stone) starting from the topmost filled block.
    // Interior columns (under building floor) get DIRT at the top. Outline columns get GRASS_BLOCK.
    private static void passD_surfaceRules(ServerLevel level, Set<Long> solidFootprint, Set<Long> outline, int floorY) {
        applyColumnRules(level, solidFootprint, floorY, false);
        applyColumnRules(level, outline, floorY, true);
    }

    private static void applyColumnRules(ServerLevel level, Set<Long> columns, int floorY, boolean grassOnTop) {
        for (long key : columns) {
            int x = unpackX(key);
            int z = unpackZ(key);

            // Find the topmost non-air block at or below floorY-1.
            int topY = floorY - 1;
            boolean found = false;
            while (topY >= floorY - MAX_ANCHOR_DEPTH - 1) {
                BlockPos pos = new BlockPos(x, topY, z);
                if (!level.isLoaded(pos)) break;
                if (!level.getBlockState(pos).isAir()) {
                    found = true;
                    break;
                }
                topY--;
            }
            if (!found) continue;

            for (int depth = 0; depth <= MAX_ANCHOR_DEPTH; depth++) {
                BlockPos pos = new BlockPos(x, topY - depth, z);
                if (!level.isLoaded(pos)) break;
                // Stop at the first air block: Pass B only fills where there was an air gap,
                // so air here means we have reached untouched natural terrain.
                if (level.getBlockState(pos).isAir()) break;
                BlockState toPlace = switch (depth) {
                    case 0 -> grassOnTop
                            ? Blocks.GRASS_BLOCK.defaultBlockState()
                            : Blocks.DIRT.defaultBlockState();
                    case 1, 2, 3 -> Blocks.DIRT.defaultBlockState();
                    default -> Blocks.STONE.defaultBlockState();
                };
                level.setBlock(pos, toPlace, Block.UPDATE_ALL);
            }
        }
    }

    // Returns [minX, maxX, minZ, maxZ] of the structure bounding box in world coordinates.
    private static int[] computeFootprint(BlockPos origin, StructureTemplate template, Rotation rotation) {
        Vec3i size = template.getSize();
        int sX = size.getX(), sZ = size.getZ();
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{
                    origin.getX() - (sZ - 1), origin.getX(),
                    origin.getZ(),             origin.getZ() + sX - 1
            };
            case CLOCKWISE_180 -> new int[]{
                    origin.getX() - (sX - 1), origin.getX(),
                    origin.getZ() - (sZ - 1), origin.getZ()
            };
            case COUNTERCLOCKWISE_90 -> new int[]{
                    origin.getX(),             origin.getX() + sZ - 1,
                    origin.getZ() - (sX - 1), origin.getZ()
            };
            default -> new int[]{
                    origin.getX(), origin.getX() + sX - 1,
                    origin.getZ(), origin.getZ() + sZ - 1
            };
        };
    }

    // A void at any Y in a column means "keep the entire terrain column" — Y position in the NBT is irrelevant for carving.
    // filterBlocks() does not return STRUCTURE_VOID in this MC version; parse the raw template NBT instead.
    private static Set<Long> scanStructureVoidColumns(StructureTemplate template, BlockPos origin, Rotation rotation) {
        Set<Long> columns = new HashSet<>();
        try {
            CompoundTag nbt = template.save(new CompoundTag());
            ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks  = nbt.getList("blocks",  Tag.TAG_COMPOUND);

            // Find which palette index corresponds to minecraft:structure_void.
            int voidIndex = -1;
            for (int i = 0; i < palette.size(); i++) {
                if ("minecraft:structure_void".equals(palette.getCompound(i).getString("Name"))) {
                    voidIndex = i;
                    break;
                }
            }

            if (voidIndex >= 0) {
                for (int i = 0; i < blocks.size(); i++) {
                    CompoundTag entry = blocks.getCompound(i);
                    if (entry.getInt("state") == voidIndex) {
                        ListTag pos = entry.getList("pos", Tag.TAG_INT);
                        BlockPos raw = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                        BlockPos rotated = StructureTemplate.transform(raw, Mirror.NONE, rotation, BlockPos.ZERO);
                        int worldX = origin.getX() + rotated.getX();
                        int worldZ = origin.getZ() + rotated.getZ();
                        columns.add(packXZ(worldX, worldZ));

                    }
                }
            }
        } catch (Exception e) {
            // silently ignored
        }
        return columns;
    }

    // Collects world XZ columns that have at least one non-air, non-void block at the jigsaw layer in the NBT.
    // Used by prePlace/postPlace to restrict carving and footprint operations to columns the building occupies.
    // Columns with only air at the jigsaw level (e.g. outside a farm fence) are left untouched.
    private static Set<Long> scanOccupiedColumns(StructureTemplate template, BlockPos origin, Rotation rotation,
                                                  int jigsawLocalY) {
        Set<Long> columns = new HashSet<>();
        try {
            CompoundTag nbt = template.save(new CompoundTag());
            ListTag palette = nbt.getList("palette", Tag.TAG_COMPOUND);
            ListTag blocks  = nbt.getList("blocks",  Tag.TAG_COMPOUND);

            Set<Integer> skipIndices = new HashSet<>();
            for (int i = 0; i < palette.size(); i++) {
                String name = palette.getCompound(i).getString("Name");
                if (name.endsWith("air") || "minecraft:structure_void".equals(name)) {
                    skipIndices.add(i);
                }
            }

            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag entry = blocks.getCompound(i);
                if (skipIndices.contains(entry.getInt("state"))) continue;
                ListTag pos = entry.getList("pos", Tag.TAG_INT);
                if (pos.getInt(1) != jigsawLocalY) continue; // only the jigsaw layer defines the carve footprint
                BlockPos raw = new BlockPos(pos.getInt(0), 0, pos.getInt(2));
                BlockPos rotated = StructureTemplate.transform(raw, Mirror.NONE, rotation, BlockPos.ZERO);
                columns.add(packXZ(origin.getX() + rotated.getX(), origin.getZ() + rotated.getZ()));
            }
        } catch (Exception e) {
            // silently ignored
        }
        return columns;
    }

    // Carves solid terrain blocks and non-solid clutter inside the building interior volume.
    // Layer 0 (floorY) is never touched. Only columns in occupiedColumns are carved.
    // Columns in noTouchColumns (explicit structure_void) are also skipped as a secondary guard.
    // Each column is carved up to the higher of (template ceiling) or (actual terrain surface),
    // so buildings placed against hills carve through the full hillside rather than embedding.
    private static void carveInterior(ServerLevel level, int minX, int maxX, int minZ, int maxZ,
                                      int startY, int height, Set<Long> noTouchColumns, Set<Long> occupiedColumns) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                long col = packXZ(x, z);
                if (noTouchColumns.contains(col)) continue;
                if (!occupiedColumns.contains(col)) continue; // no NBT blocks above floor -> preserve terrain
                int templateCeiling = startY + height;
                int terrainSurface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int carveCeiling = Math.max(templateCeiling, terrainSurface);
                for (int y = startY; y < carveCeiling; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    boolean isTerrain = CARVE_SET.contains(state.getBlock());
                    boolean isClutter = !state.isAir() && !state.isSolid();
                    if (isTerrain || isClutter) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }
    }

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) (key & 0xFFFFFFFFL);
    }
}