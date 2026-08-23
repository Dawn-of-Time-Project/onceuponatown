package org.dawnoftime.onceuponatown.datapack;

import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.List;

public class LumberjackConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig {
        public final double walkSpeed;
        public final int chopDelayTicks;
        public final List<String> woodSourceBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, int chopDelayTicks, List<String> woodSourceBuildings,
                      List<ActivityDef> secondaryActivities,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.chopDelayTicks = chopDelayTicks;
            this.woodSourceBuildings = woodSourceBuildings;
            this.secondaryActivities = secondaryActivities;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                { return bedtime; }
        @Override public int getWakeupTime()             { return wakeupTime; }
        @Override public List<String> getRestBuildings() { return restBuildings; }
        @Override public double getWalkSpeed()           { return walkSpeed; }
        @Override public List<String> getWorkBuildings() { return woodSourceBuildings; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/lumberjack.json", "lumberjack",
            json -> new Config(
                json.get("walk_speed").getAsDouble(),
                json.get("chop_delay_ticks").getAsInt(),
                JobConfigParser.parseStringList(json, "wood_source_buildings"),
                JobConfigParser.parseSecondaryActivities(json),
                JobConfigParser.optInt(json, "bedtime", -1),
                JobConfigParser.optInt(json, "wakeup_time", -1),
                JobConfigParser.parseStringList(json, "rest_buildings")
            ),
            c -> loaded = c
        );
    }
}
