package org.dawnoftime.onceuponatown.entity.ai.cowherd;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.CowHerdConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.entity.ai.herder.AbstractHerderJob;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;
import org.dawnoftime.onceuponatown.town.Town;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CowHerdJob extends AbstractHerderJob<CowHerdConfigDataHandler.Config> {

    public CowHerdJob(Npc npc) {
        super(npc);
    }

    @Override public String getJobId() { return "cowherd"; }
    @Override protected CowHerdConfigDataHandler.Config getConfig() { return CowHerdConfigDataHandler.get(); }

    @Override
    protected List<UUID> scanBuilding(ServerLevel level, PlacedBuilding building, CowHerdConfigDataHandler.Config cfg) {
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null) return List.of();
        int maxHerds = def.resolveAtLevel(building.getUpgradeLevel()).resolvedMaxHerds();
        if (maxHerds == 0) return List.of();
        AABB aabb = buildingAabb(building);
        List<Cow> allCows = level.getEntitiesOfClass(Cow.class, aabb, e -> true);
        if (allCows.size() >= maxHerds) return List.of();
        List<UUID> eligible = new ArrayList<>();
        for (Cow cow : allCows) {
            if (cow.getAge() == 0 && !cow.isInLove() && cow.canFallInLove()) eligible.add(cow.getUUID());
        }
        if (!eligible.isEmpty()) npc.holdInMainHand(new ItemStack(Items.WHEAT));
        return eligible;
    }

    @Override
    protected boolean isAnimalReady(@Nullable Entity entity) {
        return entity instanceof Cow c && c.getAge() == 0 && !c.isInLove() && c.canFallInLove();
    }

    @Override
    protected void performOnAnimal(Entity animal, CowHerdConfigDataHandler.Config cfg, ServerLevel level) {
        if (animal instanceof Cow cow && cow.canFallInLove()) {
            cow.setInLove(null);
            npc.swing(InteractionHand.MAIN_HAND);
            npc.notifyBlockPlaced();
        }
    }

    @Override
    protected boolean hasAnyReadyAnimals(ServerLevel level, Town town, CowHerdConfigDataHandler.Config cfg) {
        return town.getBuildings().stream()
            .filter(b -> cfg.getWorkBuildings().contains(b.defId) && b.bb != null)
            .anyMatch(b -> {
                BuildingDef def = BuildingDataHandler.get(b.defId).orElse(null);
                if (def == null) return false;
                int maxHerds = def.resolveAtLevel(b.getUpgradeLevel()).resolvedMaxHerds();
                if (maxHerds == 0) return false;
                List<Cow> all = level.getEntitiesOfClass(Cow.class, buildingAabb(b), e -> true);
                if (all.size() >= maxHerds) return false;
                return all.stream().anyMatch(c -> c.getAge() == 0 && !c.isInLove() && c.canFallInLove());
            });
    }
}
