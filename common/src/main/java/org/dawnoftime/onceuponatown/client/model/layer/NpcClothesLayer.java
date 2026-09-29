package org.dawnoftime.onceuponatown.client.model.layer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import org.dawnoftime.onceuponatown.Ouat;
import org.dawnoftime.onceuponatown.client.model.NpcModel;
import org.dawnoftime.onceuponatown.client.renderer.NpcRenderer;
import org.dawnoftime.onceuponatown.entity.Npc;

import java.util.Map;

public class NpcClothesLayer<T extends Npc, M extends NpcModel<T>> extends RenderLayer<T, M> {
    private static final Map<String, ResourceLocation> CLOTHES_BY_JOB = Map.ofEntries(
        Map.entry("beekeeper",     new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/beekeeper_clothes.png")),
        Map.entry("builder",       new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/builder_clothes.png")),
        Map.entry("cowherd",       new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/cowherd_clothes.png")),
        Map.entry("lumberjack",    new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/lumberjack_clothes.png")),
        Map.entry("merchant",      new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/merchant_clothes.png")),
        Map.entry("miner",         new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/miner_clothes.png")),
        Map.entry("potato_farmer", new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/potato_farmer_clothes.png")),
        Map.entry("shepherd",      new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/shepherd_clothes.png")),
        Map.entry("swineherd",     new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/swineherd_clothes.png")),
        Map.entry("toolsmith",     new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/toolsmith_clothes.png")),
        Map.entry("wheat_farmer",  new ResourceLocation(Ouat.MOD_ID, "textures/entity/npc/wheat_farmer_clothes.png"))
    );

    @SuppressWarnings("unchecked")
    public NpcClothesLayer(NpcRenderer renderer) {
        super((RenderLayerParent<T, M>) renderer);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T npc,
                        float limbSwing, float limbSwingAmount, float partialTick,
                        float ageInTicks, float netHeadYaw, float headPitch) {
        if (npc.isInvisible()) return;
        ResourceLocation texture = CLOTHES_BY_JOB.get(npc.getJobId());
        if (texture == null) return;
        renderColoredCutoutModel(getParentModel(), texture, poseStack, buffer, packedLight, npc, 1.0F, 1.0F, 1.0F);
    }
}
