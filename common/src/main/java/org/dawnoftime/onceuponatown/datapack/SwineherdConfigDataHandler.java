package org.dawnoftime.onceuponatown.datapack;

import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.HerderConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.List;

public class SwineherdConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig, HerderConfig {
        public final double walkSpeed;
        public final int breedDelayTicks;
        public final List<String> pigBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, int breedDelayTicks, List<String> pigBuildings,
                      List<ActivityDef> secondaryActivities,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.breedDelayTicks = breedDelayTicks;
            this.pigBuildings = pigBuildings;
            this.secondaryActivities = secondaryActivities;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                           { return bedtime; }
        @Override public int getWakeupTime()                        { return wakeupTime; }
        @Override public List<String> getRestBuildings()            { return restBuildings; }
        @Override public double getWalkSpeed()                      { return walkSpeed; }
        @Override public List<String> getWorkBuildings()            { return pigBuildings; }
        @Override public int getActionDelayTicks()                  { return breedDelayTicks; }
        @Override public List<ActivityDef> getSecondaryActivities() { return secondaryActivities; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/swineherd.json", "swineherd",
            json -> new Config(
                json.get("walk_speed").getAsDouble(),
                json.get("breed_delay_ticks").getAsInt(),
                JobConfigParser.parseStringList(json, "pig_buildings"),
                JobConfigParser.parseSecondaryActivities(json),
                JobConfigParser.optInt(json, "bedtime", -1),
                JobConfigParser.optInt(json, "wakeup_time", -1),
                JobConfigParser.parseStringList(json, "rest_buildings")
            ),
            c -> loaded = c
        );
    }
}
