package org.dawnoftime.onceuponatown.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Client-side mirror of building upgrade data, synced once on player login via S2CBuildingDefsPacket.
public class ClientBuildingDefsRegistry {

    public record CostEntry(String itemId, int amount) {}

    public record UpgradeLevelClient(float cadenceMultiplier, int capacityStacksAdd, int amountAdd,
                                      int residentsAdd,
                                      double productionBonusAdd,
                                      int stockBonusAdd,
                                      int maxHerdsTarget,
                                      int tradeSlotsAdd,
                                      float priceDiscountAdd,
                                      List<String> unlockedDisplay,
                                      List<CostEntry> upgradeCost) {}

    // baseCapacity/baseAmount = first production entry's values at level 0, used for bar scaling.
    public record DefEntry(int baseCapacity, int baseAmount, int baseResidents, int baseHerd,
                            double baseProductionBonus, List<UpgradeLevelClient> upgrades) {}

    private static final Map<String, DefEntry> DEFS = new HashMap<>();

    public static void setFromNbt(CompoundTag tag) {
        DEFS.clear();
        ListTag defsTag = tag.getList("Defs", Tag.TAG_COMPOUND);
        for (Tag rawDef : defsTag) {
            CompoundTag dt = (CompoundTag) rawDef;
            String id = dt.getString("Id");
            int baseCap       = dt.getInt("BaseCapacity");
            int baseAmount    = dt.getInt("BaseAmount");
            int baseResidents = dt.getInt("BaseResidents");
            int baseHerd      = dt.getInt("BaseHerd");
            double baseProdBonus = dt.getDouble("BaseProductionBonus");
            List<UpgradeLevelClient> upgrades = new ArrayList<>();
            for (Tag rawUpg : dt.getList("Upgrades", Tag.TAG_COMPOUND)) {
                CompoundTag ut = (CompoundTag) rawUpg;
                float cadenceMult    = ut.getFloat("CadenceMult");
                int capAdd           = ut.getInt("CapAdd");
                int amountAdd        = ut.getInt("AmountAdd");
                int residentsAdd     = ut.getInt("ResidentsAdd");
                double prodBonusAdd  = ut.getDouble("ProductionBonusAdd");
                int stockBonusAdd    = ut.getInt("StockBonusAdd");
                int maxHerdsTarget   = ut.getInt("MaxHerdsTarget");
                int tradeSlotsAdd    = ut.getInt("TradeSlotsAdd");
                float priceDiscountAdd = ut.getFloat("PriceDiscountAdd");
                List<String> unlocksDisplay = new ArrayList<>();
                for (Tag t : ut.getList("UnlocksDisplay", Tag.TAG_STRING)) {
                    unlocksDisplay.add(t.getAsString());
                }
                List<CostEntry> cost = new ArrayList<>();
                for (Tag rawCost : ut.getList("Cost", Tag.TAG_COMPOUND)) {
                    CompoundTag ct = (CompoundTag) rawCost;
                    cost.add(new CostEntry(ct.getString("Item"), ct.getInt("Amount")));
                }
                upgrades.add(new UpgradeLevelClient(cadenceMult, capAdd, amountAdd, residentsAdd,
                    prodBonusAdd, stockBonusAdd, maxHerdsTarget, tradeSlotsAdd, priceDiscountAdd,
                    unlocksDisplay, cost));
            }
            DEFS.put(id, new DefEntry(baseCap, baseAmount, baseResidents, baseHerd, baseProdBonus, upgrades));
        }
    }

    @Nullable
    public static DefEntry get(String id) {
        return DEFS.get(id);
    }
}
