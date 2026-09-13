package org.dawnoftime.onceuponatown.town;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.dawnoftime.onceuponatown.datapack.BuildingDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraDef;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDataHandler;
import org.dawnoftime.onceuponatown.datapack.EraTransitionDef;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TownEraState {

    private int currentEra = 0;
    private String currentEraPath = "";
    private String currentOrientation = "";
    // Cached namespace of the datapack that owns this village's culture. Derived once at world gen
    // and persisted so the village remains isolated even if the datapack is temporarily removed.
    private String cultureNamespace = "";
    private final Set<String> unlockedBuildingIds = new HashSet<>();
    private int activeResidents = 0;
    private int currentMaxWeight = 0;
    private int currentMaxUpgradeLevel = 2;
    private boolean autonomyEnabled = true;
    private String autonomyChosenTransitionId = "";

    // -- Simple getters / setters --

    public int getCurrentEra()                              { return currentEra; }
    public void setCurrentEra(int v)                        { this.currentEra = v; }
    public String getCurrentEraPath()                       { return currentEraPath; }
    public void setCurrentEraPath(String v)                 { this.currentEraPath = v; }
    public String getCurrentOrientation()                   { return currentOrientation; }
    public void setCurrentOrientation(String v)             { this.currentOrientation = v; }
    public String getCultureNamespace()                      { return cultureNamespace; }
    public Set<String> getUnlockedBuildingIds()             { return Collections.unmodifiableSet(unlockedBuildingIds); }
    public void addUnlockedBuildingIds(Collection<String> ids) { unlockedBuildingIds.addAll(ids); }
    public int getActiveResidents()                         { return activeResidents; }
    public void setActiveResidents(int v)                   { this.activeResidents = v; }
    public int getCurrentMaxWeight()                        { return currentMaxWeight; }
    public void addMaxWeight(int delta)                     { this.currentMaxWeight += delta; }
    public void setCurrentMaxWeight(int v)                  { this.currentMaxWeight = v; }
    public int getCurrentMaxUpgradeLevel()                  { return currentMaxUpgradeLevel; }
    public void setCurrentMaxUpgradeLevel(int v)            { this.currentMaxUpgradeLevel = v; }
    public boolean isAutonomyEnabled()                      { return autonomyEnabled; }
    public void setAutonomyEnabled(boolean v)               { this.autonomyEnabled = v; }
    public String getAutonomyChosenTransitionId()           { return autonomyChosenTransitionId; }
    public void setAutonomyChosenTransitionId(String v)     { this.autonomyChosenTransitionId = v; }

    // -- Domain logic -- pure on era/orientation data --

    // Seeds currentMaxWeight from the era 0 data file matching the current orientation.
    // Called once at world gen after all starter buildings are registered.
    public void initFromEraDef() {
        if (currentOrientation.isEmpty()) return;
        EraDef def = EraTransitionDataHandler.getEraDefByOrientation(currentOrientation);
        if (def == null) return;
        if (def.initialMaxWeight > 0) currentMaxWeight = def.initialMaxWeight;
        currentMaxUpgradeLevel = def.initialMaxUpgradeLevel;
        // Derive and cache the datapack namespace that owns this culture.
        cultureNamespace = BuildingDataHandler.get(def.starterBuildingId)
            .map(b -> b.namespace)
            .orElse("");
    }

    public List<EraTransitionDef> getAvailableTransitions() {
        return EraTransitionDataHandler.getAvailableTransitions(currentEra, currentOrientation, cultureNamespace);
    }

    public List<String> getBoostedBuildingIds() {
        if (currentOrientation.isEmpty()) return List.of();
        EraDef eraDef = EraTransitionDataHandler.getEraDefByOrientation(currentOrientation);
        if (eraDef == null) return List.of();
        return eraDef.boostedBuildings;
    }

    // -- NBT --

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("CurrentEra", currentEra);
        tag.putString("CurrentEraPath", currentEraPath);
        tag.putString("CurrentOrientation", currentOrientation);
        tag.putString("CultureNamespace", cultureNamespace);
        ListTag unlockedTag = new ListTag();
        unlockedBuildingIds.forEach(id -> unlockedTag.add(StringTag.valueOf(id)));
        tag.put("UnlockedBuildingIds", unlockedTag);
        tag.putInt("ActiveResidents", activeResidents);
        tag.putInt("CurrentMaxWeight", currentMaxWeight);
        tag.putInt("CurrentMaxUpgradeLevel", currentMaxUpgradeLevel);
        tag.putBoolean("AutonomyEnabled", autonomyEnabled);
        tag.putString("AutonomyChosenTransitionId", autonomyChosenTransitionId);
        return tag;
    }

    public static TownEraState fromNbt(CompoundTag tag) {
        TownEraState state = new TownEraState();
        state.currentEra = tag.getInt("CurrentEra");
        state.currentEraPath = tag.getString("CurrentEraPath");
        state.currentOrientation = tag.getString("CurrentOrientation");
        // Migration: old saves lack CultureNamespace — derive it from the orientation on first load.
        if (tag.contains("CultureNamespace") && !tag.getString("CultureNamespace").isEmpty()) {
            state.cultureNamespace = tag.getString("CultureNamespace");
        } else {
            EraDef eraDef = EraTransitionDataHandler.getEraDefByOrientation(state.currentOrientation);
            if (eraDef != null) {
                state.cultureNamespace = BuildingDataHandler.get(eraDef.starterBuildingId)
                    .map(b -> b.namespace)
                    .orElse("");
            }
        }
        if (tag.contains("UnlockedBuildingIds")) {
            tag.getList("UnlockedBuildingIds", Tag.TAG_STRING)
                .forEach(t -> state.unlockedBuildingIds.add(t.getAsString()));
        }
        state.activeResidents = tag.getInt("ActiveResidents");
        state.currentMaxWeight = tag.getInt("CurrentMaxWeight");
        state.currentMaxUpgradeLevel = tag.contains("CurrentMaxUpgradeLevel") ? tag.getInt("CurrentMaxUpgradeLevel") : 2;
        state.autonomyEnabled = tag.contains("AutonomyEnabled") && tag.getBoolean("AutonomyEnabled");
        state.autonomyChosenTransitionId = tag.getString("AutonomyChosenTransitionId");
        return state;
    }

}
