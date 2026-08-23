package org.dawnoftime.onceuponatown.town;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public record TownLogEntry(TownLogType type, String param, long gameTick) {

    public enum TownLogType {
        BUILD_START,
        BUILD_DONE,
        UPGRADE_START,
        UPGRADE_DONE,
        FOOD_CONSUMED,
        VILLAGE_FULL,
        AUTONOMY_PLANNED,
        RESIDENT_PLANNED,
        ERA_ADVANCED
    }

    public MutableComponent toComponent() {
        MutableComponent building = Component.translatableWithFallback(
            "onceuponatown.building." + param, formatBuildingId(param));
        MutableComponent body = switch (type) {
            case BUILD_START      -> Component.translatable("onceuponatown.log.build_start", building);
            case BUILD_DONE       -> Component.translatable("onceuponatown.log.build_done", building);
            case UPGRADE_START    -> Component.translatable("onceuponatown.log.upgrade_start", building);
            case UPGRADE_DONE     -> Component.translatable("onceuponatown.log.upgrade_done", building);
            case FOOD_CONSUMED    -> Component.translatable("onceuponatown.log.food_consumed", param);
            case VILLAGE_FULL     -> Component.translatable("onceuponatown.log.village_full");
            case AUTONOMY_PLANNED -> Component.translatable("onceuponatown.log.autonomy_planned", building);
            case RESIDENT_PLANNED -> Component.translatable("onceuponatown.log.resident_planned", building);
            case ERA_ADVANCED     -> Component.translatable("onceuponatown.log.era_advanced", param);
        };
        return body.withStyle(s -> s.withColor(logColor()));
    }

    private int logColor() {
        return switch (type) {
            case BUILD_START, UPGRADE_START -> 0xAAAAFF;
            case BUILD_DONE, UPGRADE_DONE   -> 0x55FF55;
            case FOOD_CONSUMED              -> 0xDDDDDD;
            case VILLAGE_FULL               -> 0xFF5555;
            case AUTONOMY_PLANNED,
                 RESIDENT_PLANNED           -> 0xFFAA55;
            case ERA_ADVANCED               -> 0xFFD700;
        };
    }

    private static String formatBuildingId(String id) {
        if (id == null || id.isEmpty()) return id;
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!sb.isEmpty()) sb.append(" ");
            if (!part.isEmpty()) sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
