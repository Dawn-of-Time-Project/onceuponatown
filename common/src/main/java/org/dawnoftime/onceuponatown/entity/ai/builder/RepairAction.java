package org.dawnoftime.onceuponatown.entity.ai.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.dawnoftime.onceuponatown.building.schematic.BlockStep;
import org.dawnoftime.onceuponatown.building.schematic.PlacementStep;
import org.dawnoftime.onceuponatown.building.schematic.SchematicBlock;
import org.dawnoftime.onceuponatown.building.schematic.SchematicConstants;
import org.dawnoftime.onceuponatown.building.schematic.SchematicReader;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.LevelTowns;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Repairs a building by diffing the live world against the current-level template and
// re-placing only mismatched blocks. Does not change the building's upgrade level.
public class RepairAction implements BuilderAction {
    private final PlacedBuilding building;
    private final BuildingDef def;
    private final Town town;

    public RepairAction(PlacedBuilding building, BuildingDef def, Town town) {
        this.building = building;
        this.def = def;
        this.town = town;
        town.addUnderUpgrade(building.worldPos);
    }

    @Override
    public BlockPos getTargetPos() {
        return building.entryPos != null ? building.entryPos : building.worldPos;
    }

    @Override
    public BlockPos getOrigin() { return building.worldPos; }

    @Override
    public boolean isInstant() { return false; }

    @Override
    public boolean executeInstant(ServerLevel level, Npc npc) { return false; }

    @Override
    public void onArrived(Npc npc) {
        npc.startReading(40 + npc.getRandom().nextInt(61));
    }

    @Override
    public List<PlacementStep> prepareSteps(ServerLevel level, Npc npc) {
        int upgradeLevel = building.getUpgradeLevel();
        ResourceLocation nbtPath = (upgradeLevel == 0)
            ? def.nbt
            : (upgradeLevel - 1 < def.nbtLevels.size() ? def.nbtLevels.get(upgradeLevel - 1).nbt() : null);
        if (nbtPath == null) return List.of();

        Optional<StructureTemplate> templateOpt = level.getStructureManager().get(nbtPath);
        if (templateOpt.isEmpty()) return List.of();

        List<SchematicBlock> templateBlocks = SchematicReader.readSortedBlocks(templateOpt.get(), building.rotation);
        List<PlacementStep> normal = new ArrayList<>();
        List<PlacementStep> deferred = new ArrayList<>();
        for (SchematicBlock b : templateBlocks) {
            BlockPos worldPos = building.worldPos.offset(b.localPos());
            if (level.getBlockState(worldPos).equals(b.state())) continue;
            BlockStep step = new BlockStep(worldPos, b.state(), b.nbt());
            if (SchematicConstants.getDeferredPriority(b.state()).isPresent()) {
                deferred.add(step);
            } else {
                normal.add(step);
            }
        }
        deferred.sort(Comparator.comparingInt(s -> SchematicConstants.getDeferredPriority(((BlockStep) s).state()).getAsInt()));
        List<PlacementStep> steps = new ArrayList<>(normal);
        steps.addAll(deferred);
        return steps;
    }

    @Override
    public void onComplete(ServerLevel level, Npc npc) {
        town.removeUnderUpgrade(building.worldPos);
        LevelTowns.get(level).markDirty();
        npc.freeHands();
    }

    @Override
    public boolean isFailed() { return false; }

    @Override
    public void saveTo(CompoundTag tag) {}
}
