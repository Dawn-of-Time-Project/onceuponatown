package org.dawnoftime.onceuponatown.entity.ai.shepherd;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.ShepherdConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.herder.AbstractHerderJob;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class ShepherdJob extends AbstractHerderJob<ShepherdConfigDataHandler.Config> {

    // true = shearing pass (priority 1), false = breeding pass (priority 2)
    private boolean shearMode = true;

    public ShepherdJob(Npc npc) {
        super(npc);
    }

    @Override public String getJobId() { return "shepherd"; }
    @Override protected ShepherdConfigDataHandler.Config getConfig() { return ShepherdConfigDataHandler.get(); }

    @Override
    protected List<UUID> scanBuilding(ServerLevel level, PlacedBuilding building, ShepherdConfigDataHandler.Config cfg) {
        AABB aabb = buildingAabb(building);

        // Priority 1: shear any ready sheep
        List<Sheep> shearable = level.getEntitiesOfClass(Sheep.class, aabb, Sheep::readyForShearing);
        if (!shearable.isEmpty()) {
            shearMode = true;
            npc.holdInMainHand(new ItemStack(Items.SHEARS));
            return shearable.stream().map(Sheep::getUUID).collect(Collectors.toCollection(ArrayList::new));
        }

        // Priority 2: breed up to the building's configured herd cap
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null) return List.of();
        int maxHerds = def.resolveAtLevel(building.getUpgradeLevel()).resolvedMaxHerds();
        if (maxHerds == 0) return List.of();
        List<Sheep> all = level.getEntitiesOfClass(Sheep.class, aabb, e -> true);
        if (all.size() >= maxHerds) return List.of();
        List<UUID> breedable = new ArrayList<>();
        for (Sheep s : all) {
            if (s.getAge() == 0 && !s.isInLove() && s.canFallInLove()) breedable.add(s.getUUID());
        }
        if (!breedable.isEmpty()) {
            shearMode = false;
            npc.holdInMainHand(new ItemStack(Items.WHEAT));
        }
        return breedable;
    }

    @Override
    protected boolean isAnimalReady(@Nullable Entity entity) {
        if (!(entity instanceof Sheep sheep)) return false;
        return shearMode
            ? sheep.readyForShearing()
            : sheep.getAge() == 0 && !sheep.isInLove() && sheep.canFallInLove();
    }

    @Override
    protected void performOnAnimal(Entity animal, ShepherdConfigDataHandler.Config cfg, ServerLevel level) {
        if (!(animal instanceof Sheep sheep)) return;
        npc.swing(InteractionHand.MAIN_HAND);
        npc.notifyBlockPlaced();
        if (shearMode && sheep.readyForShearing()) {
            sheep.shear(SoundSource.PLAYERS);
        } else if (!shearMode && sheep.canFallInLove()) {
            sheep.setInLove(null);
        }
    }

    @Override
    protected boolean hasAnyReadyAnimals(ServerLevel level, Town town, ShepherdConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.getWorkBuildings().contains(b.defId) && b.bb != null)
            .anyMatch(b -> {
                AABB aabb = buildingAabb(b);
                if (!level.getEntitiesOfClass(Sheep.class, aabb, Sheep::readyForShearing).isEmpty()) return true;
                BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
                if (def == null) return false;
                int maxHerds = def.resolveAtLevel(b.getUpgradeLevel()).resolvedMaxHerds();
                if (maxHerds == 0) return false;
                List<Sheep> all = level.getEntitiesOfClass(Sheep.class, aabb, e -> true);
                if (all.size() >= maxHerds) return false;
                return all.stream().anyMatch(s -> s.getAge() == 0 && !s.isInLove() && s.canFallInLove());
            });
    }

    // Shearing and breeding use independent delay values from the config.
    @Override
    protected int getActionDelay(ShepherdConfigDataHandler.Config cfg) {
        return shearMode ? cfg.shearDelayTicks : cfg.breedDelayTicks;
    }
}
