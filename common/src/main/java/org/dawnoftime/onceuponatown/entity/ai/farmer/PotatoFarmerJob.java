package org.dawnoftime.onceuponatown.entity.ai.farmer;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.dawnoftime.onceuponatown.datapack.FarmerConfigDataHandler;
import org.dawnoftime.onceuponatown.entity.Npc;

import java.util.List;

public class PotatoFarmerJob extends AbstractFarmerJob {

    public PotatoFarmerJob(Npc npc) {
        super(npc);
    }

    @Override
    public String getJobId() { return "potato_farmer"; }

    @Override
    public List<String> getProductionBuildings() {
        FarmerConfigDataHandler.Config cfg = getConfig();
        return cfg == null ? List.of() : cfg.productionBuildings;
    }

    @Override
    protected FarmerConfigDataHandler.Config getConfig() {
        return FarmerConfigDataHandler.get("potato_farmer");
    }

    @Override
    protected Block getCropBlock() {
        return Blocks.POTATOES;
    }

    @Override
    protected Item getSeedItem() {
        return Items.POTATO;
    }
}
