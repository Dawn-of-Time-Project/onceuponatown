package org.dawnoftime.onceuponatown.datapack;

import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.HerderConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.List;

public class ShepherdConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig, HerderConfig {
        public final double walkSpeed;
        public final int shearDelayTicks;
        public final int breedDelayTicks;
        public final List<String> sheepBuildings;
        public final List<ActivityDef> secondaryActivities;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, int shearDelayTicks, int breedDelayTicks,
                      List<String> sheepBuildings, List<ActivityDef> secondaryActivities,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.shearDelayTicks = shearDelayTicks;
            this.breedDelayTicks = breedDelayTicks;
            this.sheepBuildings = sheepBuildings;
            this.secondaryActivities = secondaryActivities;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                           { return bedtime; }
        @Override public int getWakeupTime()                        { return wakeupTime; }
        @Override public List<String> getRestBuildings()            { return restBuildings; }
        @Override public double getWalkSpeed()                      { return walkSpeed; }
        @Override public List<String> getWorkBuildings()            { return sheepBuildings; }
        @Override public int getActionDelayTicks()                  { return shearDelayTicks; } // base value; ShepherdJob overrides per mode
        @Override public List<ActivityDef> getSecondaryActivities() { return secondaryActivities; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/shepherd.json", "shepherd",
            json -> new Config(
                json.get("walk_speed").getAsDouble(),
                json.get("shear_delay_ticks").getAsInt(),
                JobConfigParser.optInt(json, "breed_delay_ticks", 40),
                JobConfigParser.parseStringList(json, "sheep_buildings"),
                JobConfigParser.parseSecondaryActivities(json),
                JobConfigParser.optInt(json, "bedtime", -1),
                JobConfigParser.optInt(json, "wakeup_time", -1),
                JobConfigParser.parseStringList(json, "rest_buildings")
            ),
            c -> loaded = c
        );
    }
}
