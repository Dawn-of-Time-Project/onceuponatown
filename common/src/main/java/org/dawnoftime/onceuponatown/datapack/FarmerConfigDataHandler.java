package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FarmerConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig {
        public final double walkSpeed;
        public final double workReach;
        public final float harvestSpeedMin;
        public final float harvestSpeedMax;
        public final float plantSpeedMin;
        public final float plantSpeedMax;
        public final List<String> farmBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final List<String> productionBuildings;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, double workReach,
                      float harvestSpeedMin, float harvestSpeedMax,
                      float plantSpeedMin, float plantSpeedMax,
                      List<String> farmBuildings, List<ActivityDef> secondaryActivities,
                      List<String> productionBuildings,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.workReach = workReach;
            this.harvestSpeedMin = harvestSpeedMin;
            this.harvestSpeedMax = harvestSpeedMax;
            this.plantSpeedMin = plantSpeedMin;
            this.plantSpeedMax = plantSpeedMax;
            this.farmBuildings = farmBuildings;
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
        @Override public List<String> getWorkBuildings()            { return farmBuildings; }
        public List<ActivityDef> getSecondaryActivities()           { return secondaryActivities; }
    }

    private static final Map<String, Config> configs = new HashMap<>();

    public static Config get(String jobId) { return configs.get(jobId); }

    public static void reload(MinecraftServer server, String jobId) {
        JobConfigParser.reload(server, "jobs/" + jobId + ".json", jobId,
            FarmerConfigDataHandler::parseConfig,
            c -> configs.put(jobId, c)
        );
    }

    private static Config parseConfig(JsonObject json) {
        var harvestSpeed = json.getAsJsonArray("harvest_speed");
        var plantSpeed   = json.getAsJsonArray("plant_speed");
        return new Config(
            json.get("walk_speed").getAsDouble(),
            json.get("work_reach").getAsDouble(),
            harvestSpeed.get(0).getAsFloat(),
            harvestSpeed.get(1).getAsFloat(),
            plantSpeed.get(0).getAsFloat(),
            plantSpeed.get(1).getAsFloat(),
            JobConfigParser.parseStringList(json, "farm_buildings"),
            JobConfigParser.parseSecondaryActivities(json),
            JobConfigParser.parseStringList(json, "production_buildings"),
            JobConfigParser.optInt(json, "bedtime", -1),
            JobConfigParser.optInt(json, "wakeup_time", -1),
            JobConfigParser.parseStringList(json, "rest_buildings")
        );
    }
}
