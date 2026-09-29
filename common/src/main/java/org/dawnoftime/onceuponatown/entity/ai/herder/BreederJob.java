package org.dawnoftime.onceuponatown.entity.ai.herder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Shearable;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.dawnoftime.onceuponatown.datapack.BreederConfigDataHandler;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;
import org.dawnoftime.onceuponatown.town.BuildingDef;
import org.dawnoftime.onceuponatown.town.PlacedBuilding;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class BreederJob extends AbstractHerderJob<BreederConfigDataHandler.Config> {

    private final String jobId;
    // true = shearing pass (priority 1), false = breeding pass (priority 2). Only relevant when canShear = true.
    private boolean shearMode = false;

    public BreederJob(Npc npc, String jobId) {
        super(npc);
        this.jobId = jobId;
    }

    @Override public String getJobId() { return jobId; }
    @Override protected BreederConfigDataHandler.Config getConfig() { return BreederConfigDataHandler.get(jobId); }

    @Override
    public List<String> getProductionBuildings() {
        BreederConfigDataHandler.Config cfg = getConfig();
        return cfg == null ? List.of() : cfg.productionBuildings;
    }

    @Override
    protected List<UUID> scanBuilding(ServerLevel level, PlacedBuilding building, BreederConfigDataHandler.Config cfg) {
        AABB aabb = buildingAabb(building);

        // Priority 1: shear any ready animals if this breeder supports shearing
        if (cfg.canShear) {
            List<Animal> shearable = level.getEntitiesOfClass(Animal.class, aabb,
                e -> e.getType() == cfg.entityType && e instanceof Shearable s && s.readyForShearing());
            if (!shearable.isEmpty()) {
                shearMode = true;
                npc.holdInMainHand(new ItemStack(Items.SHEARS));
                return shearable.stream().map(Animal::getUUID).collect(Collectors.toCollection(ArrayList::new));
            }
        }

        // Priority 2: breed up to the building's configured herd cap
        shearMode = false;
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null) return List.of();
        int maxHerds = def.resolveAtLevel(building.getUpgradeLevel()).resolvedMaxHerds();
        if (maxHerds == 0) return List.of();
        List<Animal> all = level.getEntitiesOfClass(Animal.class, aabb, e -> e.getType() == cfg.entityType);
        if (all.size() >= maxHerds) return List.of();
        List<UUID> breedable = new ArrayList<>();
        for (Animal a : all) {
            if (a.getAge() == 0 && !a.isInLove() && a.canFallInLove()) breedable.add(a.getUUID());
        }
        if (!breedable.isEmpty()) npc.holdInMainHand(new ItemStack(cfg.breedingFoodItem));
        return breedable;
    }

    @Override
    protected boolean isAnimalReady(@Nullable Entity entity) {
        BreederConfigDataHandler.Config cfg = getConfig();
        if (cfg == null || !(entity instanceof Animal animal) || animal.getType() != cfg.entityType) return false;
        if (shearMode) return entity instanceof Shearable s && s.readyForShearing();
        return animal.getAge() == 0 && !animal.isInLove() && animal.canFallInLove();
    }

    @Override
    protected void performOnAnimal(Entity entity, BreederConfigDataHandler.Config cfg, ServerLevel level) {
        if (!(entity instanceof Animal animal) || animal.getType() != cfg.entityType) return;
        npc.swing(InteractionHand.MAIN_HAND);
        npc.notifyBlockPlaced();
        if (shearMode && entity instanceof Shearable s && s.readyForShearing()) {
            s.shear(SoundSource.PLAYERS);
        } else if (!shearMode && animal.canFallInLove()) {
            animal.setInLove(null);
        }
    }

    @Override
    protected int getActionDelay(BreederConfigDataHandler.Config cfg) {
        float min = shearMode ? cfg.shearSpeedMin : cfg.breedSpeedMin;
        float max = shearMode ? cfg.shearSpeedMax : cfg.breedSpeedMax;
        float speed = min + npc.getRandom().nextFloat() * (max - min);
        return speedToTicks(speed);
    }

    public static boolean hasReadyAnimals(ServerLevel level, PlacedBuilding building, String jobId) {
        BreederConfigDataHandler.Config cfg = BreederConfigDataHandler.get(jobId);
        if (cfg == null || building.bb == null) return false;
        AABB aabb = buildingAabb(building);
        if (cfg.canShear && !level.getEntitiesOfClass(Animal.class, aabb,
                e -> e.getType() == cfg.entityType && e instanceof Shearable s && s.readyForShearing()).isEmpty()) {
            return true;
        }
        BuildingDef def = BuildingDataHandler.get(building.defId).orElse(null);
        if (def == null) return false;
        int maxHerds = def.resolveAtLevel(building.getUpgradeLevel()).resolvedMaxHerds();
        if (maxHerds == 0) return false;
        List<Animal> all = level.getEntitiesOfClass(Animal.class, aabb, e -> e.getType() == cfg.entityType);
        if (all.size() >= maxHerds) return false;
        return all.stream().anyMatch(a -> a.getAge() == 0 && !a.isInLove() && a.canFallInLove());
    }
}
