package org.dawnoftime.onceuponatown.datapack;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BuildingListDataHandler {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildingListDataHandler.class);
    // One ordered list of building defIds per datapack namespace.
    private static Map<String, List<String>> ORDER_BY_NAMESPACE = Collections.emptyMap();

    public static void reload(MinecraftServer server) {
        Map<String, List<String>> byNamespace = new HashMap<>();
        ResourceManager rm = server.getResourceManager();
        rm.listResources("config", path -> path.getPath().endsWith("building_list.json"))
            .forEach((location, resource) -> {
                String namespace = location.getNamespace();
                try (InputStreamReader reader = new InputStreamReader(resource.open())) {
                    JsonObject json = GSON.fromJson(reader, JsonObject.class);
                    if (json.has("order")) {
                        List<String> order = new ArrayList<>();
                        for (var el : json.getAsJsonArray("order")) {
                            order.add(el.getAsString());
                        }
                        byNamespace.put(namespace, Collections.unmodifiableList(order));
                    }
                } catch (Exception e) {
                    LOGGER.error("[OUAT] Failed to load building_list.json from {}: {}", location, e.getMessage());
                }
            });
        ORDER_BY_NAMESPACE = Collections.unmodifiableMap(byNamespace);
    }

    public static List<String> getOrder(String namespace) {
        return ORDER_BY_NAMESPACE.getOrDefault(namespace, Collections.emptyList());
    }

    // Returns the display index for a defId within the given namespace's list.
    // Falls back to the unscoped search across all lists if not found in the namespace list.
    // Unlisted buildings return Integer.MAX_VALUE so they sort after all listed ones.
    public static int getIndex(String namespace, String defId) {
        List<String> order = ORDER_BY_NAMESPACE.get(namespace);
        if (order != null) {
            int idx = order.indexOf(defId);
            if (idx >= 0) return idx;
        }
        // Fallback: check other namespaces (handles calls without a known namespace).
        for (List<String> fallbackOrder : ORDER_BY_NAMESPACE.values()) {
            int idx = fallbackOrder.indexOf(defId);
            if (idx >= 0) return idx;
        }
        return Integer.MAX_VALUE;
    }
}
