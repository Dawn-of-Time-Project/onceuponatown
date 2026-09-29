package org.dawnoftime.onceuponatown.datapack;

import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.StandJobConfig;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class MerchantConfigDataHandler {

    public static final class Config implements StandJobConfig {
        public final double walkSpeed;
        public final List<String> workBuildings;
        public final List<String> restBuildings;
        public final int bedtime;
        public final int wakeupTime;
        // Stand type the merchant occupies in its work building. Null = fall back to block scan (legacy).
        public final @Nullable String workStandType;
        public final List<ActivityDef> secondaryActivities;

        public Config(double walkSpeed, List<String> workBuildings,
                      List<String> restBuildings, int bedtime, int wakeupTime,
                      @Nullable String workStandType, List<ActivityDef> secondaryActivities) {
            this.walkSpeed = walkSpeed;
            this.workBuildings = workBuildings;
            this.restBuildings = restBuildings;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.workStandType = workStandType;
            this.secondaryActivities = secondaryActivities;
        }

        @Override public int getBedtime()                          { return bedtime; }
        @Override public int getWakeupTime()                       { return wakeupTime; }
        @Override public List<String> getRestBuildings()           { return restBuildings; }
        @Override public double getWalkSpeed()                     { return walkSpeed; }
        @Override public List<String> getWorkBuildings()           { return workBuildings; }
        @Override public String getWorkStandType()                 { return workStandType; }
        @Override public List<ActivityDef> getSecondaryActivities(){ return secondaryActivities; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/merchant.json", "merchant",
            json -> new Config(
                json.get("walk_speed").getAsDouble(),
                JobConfigParser.parseStringList(json, "work_buildings"),
                JobConfigParser.parseStringList(json, "rest_buildings"),
                JobConfigParser.optInt(json, "bedtime", -1),
                JobConfigParser.optInt(json, "wakeup_time", -1),
                json.has("work_stand_type") ? json.get("work_stand_type").getAsString() : null,
                JobConfigParser.parseSecondaryActivities(json)
            ),
            c -> loaded = c
        );
    }
}
