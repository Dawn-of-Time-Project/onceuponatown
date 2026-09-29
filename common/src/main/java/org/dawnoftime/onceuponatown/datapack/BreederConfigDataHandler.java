package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.HerderConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BreederConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig, HerderConfig {
        public final double walkSpeed;
        public final double workReach;
        public final float breedSpeedMin;
        public final float breedSpeedMax;
        public final float shearSpeedMin;
        public final float shearSpeedMax;
        public final boolean canShear;
        public final EntityType<?> entityType;
        public final Item breedingFoodItem;
        public final List<String> workBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final List<String> productionBuildings;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, double workReach,
                      float breedSpeedMin, float breedSpeedMax,
                      float shearSpeedMin, float shearSpeedMax,
                      boolean canShear, EntityType<?> entityType, Item breedingFoodItem,
                      List<String> workBuildings, List<ActivityDef> secondaryActivities,
                      List<String> productionBuildings, int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.workReach = workReach;
            this.breedSpeedMin = breedSpeedMin;
            this.breedSpeedMax = breedSpeedMax;
            this.shearSpeedMin = shearSpeedMin;
            this.shearSpeedMax = shearSpeedMax;
            this.canShear = canShear;
            this.entityType = entityType;
            this.breedingFoodItem = breedingFoodItem;
            this.workBuildings = workBuildings;
            this.secondaryActivities = secondaryActivities;
            this.productionBuildings = productionBuildings;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                           { return bedtime; }
        @Override public int getWakeupTime()                        { return wakeupTime; }
        @Override public List<String> getRestBuildings()            { return restBuildings; }
        @Override public double getWalkSpeed()                      { return walkSpeed; }
        @Override public double getWorkReach()                      { return workReach; }
        @Override public List<String> getWorkBuildings()            { return workBuildings; }
        @Override public List<ActivityDef> getSecondaryActivities() { return secondaryActivities; }
    }

    private static final Map<String, Config> configs = new HashMap<>();

    public static Config get(String jobId) { return configs.get(jobId); }

    public static void reload(MinecraftServer server, String jobId) {
        JobConfigParser.reload(server, "jobs/" + jobId + ".json", jobId,
            BreederConfigDataHandler::parseConfig,
            c -> configs.put(jobId, c)
        );
    }

    private static Config parseConfig(JsonObject json) {
        String animalTypeId = json.get("animal_type").getAsString();
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE
            .getOptional(new ResourceLocation(animalTypeId))
            .orElseThrow(() -> new IllegalArgumentException("Unknown entity type: " + animalTypeId));

        String foodId = json.get("breeding_food").getAsString();
        Item breedingFood = BuiltInRegistries.ITEM
            .getOptional(new ResourceLocation(foodId))
            .orElse(Items.WHEAT);

        boolean canShear = json.has("can_shear") && json.get("can_shear").getAsBoolean();

        JsonArray breedSpeed = json.getAsJsonArray("breed_speed");
        float breedSpeedMin = breedSpeed.get(0).getAsFloat();
        float breedSpeedMax = breedSpeed.get(1).getAsFloat();

        float shearSpeedMin = 0.5f;
        float shearSpeedMax = 1.5f;
        if (json.has("shear_speed")) {
            JsonArray shearSpeed = json.getAsJsonArray("shear_speed");
            shearSpeedMin = shearSpeed.get(0).getAsFloat();
            shearSpeedMax = shearSpeed.get(1).getAsFloat();
        }

        return new Config(
            json.get("walk_speed").getAsDouble(),
            json.get("work_reach").getAsDouble(),
            breedSpeedMin,
            breedSpeedMax,
            shearSpeedMin,
            shearSpeedMax,
            canShear,
            entityType,
            breedingFood,
            JobConfigParser.parseStringList(json, "work_buildings"),
            JobConfigParser.parseSecondaryActivities(json),
            JobConfigParser.parseStringList(json, "production_buildings"),
            JobConfigParser.optInt(json, "bedtime", -1),
            JobConfigParser.optInt(json, "wakeup_time", -1),
            JobConfigParser.parseStringList(json, "rest_buildings")
        );
    }
}
