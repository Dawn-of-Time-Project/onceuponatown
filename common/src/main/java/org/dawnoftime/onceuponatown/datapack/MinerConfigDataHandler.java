package org.dawnoftime.onceuponatown.datapack;

import net.minecraft.server.MinecraftServer;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;
import org.dawnoftime.onceuponatown.entity.ai.shared.WorkConfig;

import java.util.List;

public class MinerConfigDataHandler {

    public static final class Config implements SleepConfig, WorkConfig {
        public final double walkSpeed;
        public final int mineDelayTicks;
        public final int mineSessionTicks;
        public final List<String> workBuildings;
        public final List<String> mineableBlocks;
        public final List<ActivityDef> secondaryActivities;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, int mineDelayTicks, int mineSessionTicks,
                      List<String> workBuildings, List<String> mineableBlocks,
                      List<ActivityDef> secondaryActivities,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed           = walkSpeed;
            this.mineDelayTicks      = mineDelayTicks;
            this.mineSessionTicks    = mineSessionTicks;
            this.workBuildings       = workBuildings;
            this.mineableBlocks      = mineableBlocks;
            this.secondaryActivities = secondaryActivities;
            this.bedtime             = bedtime;
            this.wakeupTime          = wakeupTime;
            this.restBuildings       = restBuildings;
        }

        @Override public int getBedtime()                { return bedtime; }
        @Override public int getWakeupTime()             { return wakeupTime; }
        @Override public List<String> getRestBuildings() { return restBuildings; }
        @Override public double getWalkSpeed()           { return walkSpeed; }
        @Override public List<String> getWorkBuildings() { return workBuildings; }
    }

    private static Config loaded = null;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        JobConfigParser.reload(server, "jobs/miner.json", "miner",
            json -> {
                List<String> mineableBlocks = JobConfigParser.parseStringList(json, "mineable_blocks");
                if (mineableBlocks.isEmpty()) mineableBlocks.add("minecraft:stone");
                return new Config(
                    json.get("walk_speed").getAsDouble(),
                    JobConfigParser.optInt(json, "mine_delay_ticks", 20),
                    JobConfigParser.optInt(json, "mine_session_ticks", 300),
                    JobConfigParser.parseStringList(json, "work_buildings"),
                    mineableBlocks,
                    JobConfigParser.parseSecondaryActivities(json),
                    JobConfigParser.optInt(json, "bedtime", -1),
                    JobConfigParser.optInt(json, "wakeup_time", -1),
                    JobConfigParser.parseStringList(json, "rest_buildings")
                );
            },
            c -> loaded = c
        );
    }
}
