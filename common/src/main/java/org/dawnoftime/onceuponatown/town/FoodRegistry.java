package org.dawnoftime.onceuponatown.town;

import net.minecraft.world.item.Item;
import org.dawnoftime.onceuponatown.datapack.FoodListDataHandler;

import java.util.List;
import java.util.Map;

// Thin facade over FoodListDataHandler. Data is fully driven by food_list.json.
public class FoodRegistry {

    public static List<Map.Entry<Item, Integer>> residentEntriesInOrder() {
        return FoodListDataHandler.residentEntriesInOrder();
    }

    public static List<Map.Entry<Item, Integer>> herdEntriesInOrder() {
        return FoodListDataHandler.herdEntriesInOrder();
    }

    public static List<Long> getFeedingSchedule() {
        return FoodListDataHandler.getFeedingSchedule();
    }

    public static float getUnitsPerResident() { return FoodListDataHandler.getUnitsPerResident(); }
    public static float getUnitsPerAnimal()   { return FoodListDataHandler.getUnitsPerAnimal(); }
}
