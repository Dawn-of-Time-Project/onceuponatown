package org.dawnoftime.onceuponatown.item;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.ConnectorReader;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBounds;
import org.dawnoftime.onceuponatown.building.schematic.SchematicPlacer;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.registry.BlockRegistry;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.ConnectionPoint;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.Town;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class VillageBannerItem extends RecognitionMedalItem {

    private static final Logger LOGGER = LoggerFactory.getLogger(VillageBannerItem.class);

    public VillageBannerItem(Properties props) {
        super(props);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("onceuponatown.tooltip.village_banner.place_hint")
            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!ctx.isSecondaryUseActive()) return InteractionResult.PASS;

        ItemStack stack = ctx.getItemInHand();
        ServerLevel serverLevel = (ServerLevel) level;

        String starterBuildingId = RecognitionMedalItem.readStarterBuildingId(stack);
        LOGGER.info("[OUAT-BANNER] starterBuildingId='{}'", starterBuildingId);
        if (starterBuildingId.isEmpty()) {
            LOGGER.error("[OUAT-BANNER] FAIL: starterBuildingId is empty");
            return InteractionResult.FAIL;
        }

        BuildingDef def = BuildingDataHandler.get(starterBuildingId).orElse(null);
        LOGGER.info("[OUAT-BANNER] def={}", def != null ? def.id : "null");
        if (def == null) {
            LOGGER.error("[OUAT-BANNER] FAIL: BuildingDef not found for id='{}'", starterBuildingId);
            return InteractionResult.FAIL;
        }

        BlockPos origin = ctx.getClickedPos();
        Rotation rotation = Rotation.values()[serverLevel.random.nextInt(4)];
        LOGGER.info("[OUAT-BANNER] placing at origin={} rotation={} nbt={}", origin, rotation, def.nbt);

        boolean placed = SchematicPlacer.place(serverLevel, origin, def.nbt, rotation);
        LOGGER.info("[OUAT-BANNER] place result={}", placed);
        if (!placed) {
            LOGGER.error("[OUAT-BANNER] FAIL: SchematicPlacer.place returned false");
            return InteractionResult.FAIL;
        }

        BlockPos anchorPos = findAnchorInTemplate(serverLevel, origin, def, rotation);
        LOGGER.info("[OUAT-BANNER] anchorPos={}", anchorPos);
        if (anchorPos == null) {
            LOGGER.error("[OUAT-BANNER] FAIL: no TownAnchorBlock found in template '{}'", def.nbt);
            return InteractionResult.FAIL;
        }

        List<ConnectionPoint> connections = ConnectorReader.readJigsawPoints(
            serverLevel, origin, def.id, rotation,
            new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
            false);

        BoundingBox bb = SchematicBounds.computeBoundingBox(serverLevel, origin, def.nbt, rotation)
            .orElse(null);

        BlockPos entryPos = ConnectorReader.readEntryJigsawPos(serverLevel, origin, def.nbt, rotation);

        Town town = new Town();
        town.setAutonomyEnabled(false);

        town.registerBuilding(origin, def.id, connections, bb, rotation, List.of(), entryPos);
        town.initFromEraDef();

        Set<String> allIds = new HashSet<>(RecognitionMedalItem.readSignatureIds(stack));
        allIds.addAll(RecognitionMedalItem.readEraUnlockedIds(stack));
        town.addUnlockedBuildingIds(allIds);
        town.receiveMedal(allIds);

        LevelTowns levelTowns = LevelTowns.get(serverLevel);
        levelTowns.registerTown(anchorPos, town);
        levelTowns.markDirty();

        LOGGER.info("[OUAT-BANNER] SUCCESS: town registered at anchorPos={}", anchorPos);
        stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    private static BlockPos findAnchorInTemplate(ServerLevel level, BlockPos origin,
                                                  BuildingDef def, Rotation rotation) {
        Optional<StructureTemplate> tpl = level.getStructureManager().get(def.nbt);
        if (tpl.isEmpty()) return null;

        for (StructureTemplate.StructureBlockInfo info :
                tpl.get().filterBlocks(BlockPos.ZERO, new StructurePlaceSettings(), BlockRegistry.TOWN_ANCHOR)) {
            BlockPos rotatedRel = StructureTemplate.transform(info.pos(), Mirror.NONE, rotation, BlockPos.ZERO);
            return origin.offset(rotatedRel);
        }
        return null;
    }
}
