package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.JsonArray;
import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;

import java.util.List;

public class ToolsmithConfigDataHandler {

    public static final class Config implements StandJobConfig {
        public final double walkSpeed;
        public final float smithingSpeedMin;
        public final float smithingSpeedMax;
        public final String workStandType;
        public final List<String> workBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final List<String> productionBuildings;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, float smithingSpeedMin, float smithingSpeedMax,
                      String workStandType, List<String> workBuildings,
                      List<ActivityDef> secondaryActivities, List<String> productionBuildings,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed           = walkSpeed;
            this.smithingSpeedMin    = smithingSpeedMin;
            this.smithingSpeedMax    = smithingSpeedMax;
            this.workStandType       = workStandType;
            this.workBuildings       = workBuildings;
            this.secondaryActivities = secondaryActivities;
            this.productionBuildings = productionBuildings;
            this.bedtime             = bedtime;
            this.wakeupTime          = wakeupTime;
            this.restBuildings       = restBuildings;
        }

        @Override public int getBedtime()                           { return bedtime; }
        @Override public int getWakeupTime()                        { return wakeupTime; }
        @Override public List<String> getRestBuildings()            { return restBuildings; }
        @Override public double getWalkSpeed()                      { return walkSpeed; }
        @Override public List<String> getWorkBuildings()            { return workBuildings; }
        @Override public String getWorkStandType()                  { return workStandType; }
        @Override public List<ActivityDef> getSecondaryActivities() { return secondaryActivities; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/toolsmith.json", "toolsmith",
            json -> {
                float smithingSpeedMin = 0.5f;
                float smithingSpeedMax = 1.5f;
                if (json.has("smithing_speed")) {
                    JsonArray arr = json.getAsJsonArray("smithing_speed");
                    smithingSpeedMin = arr.get(0).getAsFloat();
                    smithingSpeedMax = arr.get(1).getAsFloat();
                }
                return new Config(
                    json.get("walk_speed").getAsDouble(),
                    smithingSpeedMin,
                    smithingSpeedMax,
                    json.get("work_stand_type").getAsString(),
                    JobConfigParser.parseStringList(json, "work_buildings"),
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
