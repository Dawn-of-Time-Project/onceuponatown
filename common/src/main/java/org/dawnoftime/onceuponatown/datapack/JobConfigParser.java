package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.entity.ai.ActivityDef;
import org.dawnoftime.onceuponatown.entity.ai.AnimationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

public final class JobConfigParser {

    static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(JobConfigParser.class);

    private JobConfigParser() {}

    public static List<ActivityDef> parseSecondaryActivities(JsonObject json) {
        List<ActivityDef> list = new ArrayList<>();
        if (!json.has("secondary_activities")) return list;
        for (JsonElement el : json.getAsJsonArray("secondary_activities")) {
            JsonObject obj = el.getAsJsonObject();
            list.add(new ActivityDef(
                obj.get("requiredBuilding").getAsString(),
                obj.get("heldItem").getAsString(),
                AnimationType.valueOf(obj.get("animationType").getAsString()),
                obj.has("target_block") ? obj.get("target_block").getAsString() : null
            ));
        }
        return list;
    }

    public static List<String> parseStringList(JsonObject json, String key) {
        List<String> list = new ArrayList<>();
        if (json.has(key)) json.getAsJsonArray(key).forEach(e -> list.add(e.getAsString()));
        return list;
    }

    public static int optInt(JsonObject json, String key, int def) {
        return json.has(key) ? json.get(key).getAsInt() : def;
    }

    public static double optDbl(JsonObject json, String key, double def) {
        return json.has(key) ? json.get(key).getAsDouble() : def;
    }

    public static <C> void reload(
            MinecraftServer server,
            String jsonPath,
            String npcName,
            Function<JsonObject, C> parser,
            Consumer<C> setter
    ) {
        setter.accept(null);
        ResourceLocation location = new ResourceLocation(Ouat.MOD_ID, jsonPath);
        Optional<Resource> resource = server.getResourceManager().getResource(location);
        if (resource.isEmpty()) {
            LOGGER.error("[OUAT] {} not found -- {} NPC will stay idle", jsonPath, npcName);
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(resource.get().open())) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            setter.accept(parser.apply(json));
        } catch (Exception e) {
            LOGGER.error("[OUAT] Failed to load {} : {} -- {} NPC will stay idle", location, e.getMessage(), npcName);
        }
    }
}
