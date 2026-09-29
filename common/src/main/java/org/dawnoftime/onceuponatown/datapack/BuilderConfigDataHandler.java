package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.resources.ResourceLocation;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import org.dawnoftime.onceuponatown.entity.ai.shared.SleepConfig;

public class BuilderConfigDataHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(BuilderConfigDataHandler.class);

    public static final class Config implements SleepConfig {
        public final double walkSpeed;
        public final double blockReachDistance;
        public final float blockSpeedMin;
        public final float blockSpeedMax;
        public final float planReadChance;
        public final float planReadPauseMin;
        public final float planReadPauseMax;
        public final List<ActivityDef> secondaryActivities;
        public final int bedtime;
        public final int wakeupTime;
        public final List<String> restBuildings;

        public Config(double walkSpeed, double blockReachDistance,
                      float blockSpeedMin, float blockSpeedMax,
                      float planReadChance, float planReadPauseMin, float planReadPauseMax,
                      List<ActivityDef> secondaryActivities,
                      int bedtime, int wakeupTime, List<String> restBuildings) {
            this.walkSpeed = walkSpeed;
            this.blockReachDistance = blockReachDistance;
            this.blockSpeedMin = blockSpeedMin;
            this.blockSpeedMax = blockSpeedMax;
            this.planReadChance = planReadChance;
            this.planReadPauseMin = planReadPauseMin;
            this.planReadPauseMax = planReadPauseMax;
            this.secondaryActivities = secondaryActivities;
            this.bedtime = bedtime;
            this.wakeupTime = wakeupTime;
            this.restBuildings = restBuildings;
        }

        @Override public int getBedtime()                { return bedtime; }
        @Override public int getWakeupTime()             { return wakeupTime; }
        @Override public List<String> getRestBuildings() { return restBuildings; }
        @Override public double getWalkSpeed()           { return walkSpeed; }
    }

    private static final Config DEFAULTS = new Config(
        0.6, 6.0, 0.1f, 0.9f, 0.05f, 0.75f, 1.75f, List.of(), -1, -1, List.of()
    );

    private static Config loaded = DEFAULTS;

    public static Config get() { return loaded; }

    public static void reload(MinecraftServer server) {
        ResourceLocation location = new ResourceLocation(Ouat.MOD_ID, "jobs/builder.json");
        Optional<Resource> resource = server.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            loaded = DEFAULTS;
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            List<ActivityDef> activities = JobConfigParser.parseSecondaryActivities(json);
            List<String> restBuildings = JobConfigParser.parseStringList(json, "rest_buildings");

            int bedtime    = json.has("bedtime")     ? json.get("bedtime").getAsInt()     : -1;
            int wakeupTime = json.has("wakeup_time") ? json.get("wakeup_time").getAsInt() : -1;

            float blockSpeedMin = DEFAULTS.blockSpeedMin;
            float blockSpeedMax = DEFAULTS.blockSpeedMax;
            if (json.has("block_speed")) {
                JsonArray arr = json.getAsJsonArray("block_speed");
                blockSpeedMin = arr.get(0).getAsFloat();
                blockSpeedMax = arr.get(1).getAsFloat();
            }

            float planReadPauseMin = DEFAULTS.planReadPauseMin;
            float planReadPauseMax = DEFAULTS.planReadPauseMax;
            if (json.has("plan_read_pause")) {
                JsonArray arr = json.getAsJsonArray("plan_read_pause");
                planReadPauseMin = arr.get(0).getAsFloat();
                planReadPauseMax = arr.get(1).getAsFloat();
            }

            loaded = new Config(
                getDbl(json, "walk_speed",         DEFAULTS.walkSpeed),
                getDbl(json, "work_reach",          DEFAULTS.blockReachDistance),
                blockSpeedMin,
                blockSpeedMax,
                getFlt(json, "plan_read_chance",   DEFAULTS.planReadChance),
                planReadPauseMin,
                planReadPauseMax,
                activities,
                bedtime,
                wakeupTime,
                restBuildings
            );
        } catch (Exception e) {
            LOGGER.error("[OUAT] Failed to load builder config {}: {} -- using defaults", location, e.getMessage());
            loaded = DEFAULTS;
        }
    }

    private static double getDbl(JsonObject json, String key, double def) {
        return json.has(key) ? json.get(key).getAsDouble() : def;
    }

    private static float getFlt(JsonObject json, String key, float def) {
        return json.has(key) ? json.get(key).getAsFloat() : def;
    }
}
