package org.dawnoftime.onceuponatown.entity.ai.swineherd;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.SwineherdConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.herder.AbstractHerderJob;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SwineherdJob extends AbstractHerderJob<SwineherdConfigDataHandler.Config> {

    public SwineherdJob(Npc npc) {
        super(npc);
    }

    @Override public String getJobId() { return "swineherd"; }
    @Override protected SwineherdConfigDataHandler.Config getConfig() { return SwineherdConfigDataHandler.get(); }

    @Override
    protected List<UUID> scanBuilding(ServerLevel level, PlacedBuilding building, SwineherdConfigDataHandler.Config cfg) {
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null) return List.of();
        int maxHerds = def.resolveAtLevel(building.getUpgradeLevel()).resolvedMaxHerds();
        if (maxHerds == 0) return List.of();
        AABB aabb = buildingAabb(building);
        List<Pig> allPigs = level.getEntitiesOfClass(Pig.class, aabb, e -> true);
        if (allPigs.size() >= maxHerds) return List.of();
        List<UUID> eligible = new ArrayList<>();
        for (Pig pig : allPigs) {
            if (pig.getAge() == 0 && !pig.isInLove() && pig.canFallInLove()) eligible.add(pig.getUUID());
        }
        if (!eligible.isEmpty()) npc.holdInMainHand(new ItemStack(Items.CARROT));
        return eligible;
    }

    @Override
    protected boolean isAnimalReady(@Nullable Entity entity) {
        return entity instanceof Pig p && p.getAge() == 0 && !p.isInLove() && p.canFallInLove();
    }

    @Override
    protected void performOnAnimal(Entity animal, SwineherdConfigDataHandler.Config cfg, ServerLevel level) {
        if (animal instanceof Pig pig && pig.canFallInLove()) {
            pig.setInLove(null);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
        }
    }

    @Override
    protected boolean hasAnyReadyAnimals(ServerLevel level, Town town, SwineherdConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.getWorkBuildings().contains(b.defId) && b.bb != null)
            .anyMatch(b -> {
                BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
                if (def == null) return false;
                int maxHerds = def.resolveAtLevel(b.getUpgradeLevel()).resolvedMaxHerds();
                if (maxHerds == 0) return false;
                List<Pig> all = level.getEntitiesOfClass(Pig.class, buildingAabb(b), e -> true);
                if (all.size() >= maxHerds) return false;
                return all.stream().anyMatch(p -> p.getAge() == 0 && !p.isInLove() && p.canFallInLove());
            });
    }
}
