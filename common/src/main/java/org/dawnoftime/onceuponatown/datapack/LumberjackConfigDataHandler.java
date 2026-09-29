package org.dawnoftime.onceuponatown.datapack;

import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.List;

public class LumberjackConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig {
        public final double walkSpeed;
        public final double workReach;
        public final float chopSpeedMin;
        public final float chopSpeedMax;
        public final float plantSpeedMin;
        public final float plantSpeedMax;
        public final List<String> woodSourceBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final List<String> productionBuildings;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, double workReach,
                      float chopSpeedMin, float chopSpeedMax,
                      float plantSpeedMin, float plantSpeedMax,
                      List<String> woodSourceBuildings, List<ActivityDef> secondaryActivities,
                      List<String> productionBuildings,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.workReach = workReach;
            this.chopSpeedMin = chopSpeedMin;
            this.chopSpeedMax = chopSpeedMax;
            this.plantSpeedMin = plantSpeedMin;
            this.plantSpeedMax = plantSpeedMax;
            this.woodSourceBuildings = woodSourceBuildings;
            this.secondaryActivities = secondaryActivities;
            this.productionBuildings = productionBuildings;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                 { return bedtime; }
        @Override public int getWakeupTime()              { return wakeupTime; }
        @Override public List<String> getRestBuildings()  { return restBuildings; }
        @Override public double getWalkSpeed()            { return walkSpeed; }
        @Override public double getWorkReach()            { return workReach; }
        @Override public List<String> getWorkBuildings()  { return woodSourceBuildings; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/lumberjack.json", "lumberjack",
            json -> {
                var chopSpeed  = json.getAsJsonArray("chop_speed");
                var plantSpeed = json.getAsJsonArray("plant_speed");
                return new Config(
                    json.get("walk_speed").getAsDouble(),
                    json.get("work_reach").getAsDouble(),
                    chopSpeed.get(0).getAsFloat(),
                    chopSpeed.get(1).getAsFloat(),
                    plantSpeed.get(0).getAsFloat(),
                    plantSpeed.get(1).getAsFloat(),
                    JobConfigParser.parseStringList(json, "wood_source_buildings"),
                    JobConfigParser.parseSecondaryActivities(json),
                    JobConfigParser.parseStringList(json, "production_buildings"),
                    JobConfigParser.optInt(json, "bedtime", -1),
                    JobConfigParser.optInt(json, "wakeup_time", -1),
                    JobConfigParser.parseStringList(json, "rest_buildings")
                );
            },
            c -> loaded = c
        );
    }
}
