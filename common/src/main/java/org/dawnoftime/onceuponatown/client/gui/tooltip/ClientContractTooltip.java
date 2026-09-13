package org.dawnoftime.onceuponatown.client.gui.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import org.dawnoftime.onceuponatown.town.ContractEntry;
import org.joml.Matrix4f;

import java.util.List;

public class ClientContractTooltip implements ClientTooltipComponent {

    private static final int MAX_COLS = 12;
    private static final int CELL     = 18;

    private final ContractTooltip data;

    public ClientContractTooltip(ContractTooltip data) {
        this.data = data;
    }

    @Override
    public int getHeight() {
        int rows = (data.entries().size() - 1) / MAX_COLS + 1;
        return rows * CELL;
    }

    @Override
    public int getWidth(Font font) {
        return Math.min(data.entries().size(), MAX_COLS) * CELL;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics g) {
        List<ContractEntry> entries = data.entries();
        for (int i = 0; i < entries.size(); i++) {
            ContractEntry entry = entries.get(i);
            int col = i % MAX_COLS;
            int row = i / MAX_COLS;
            int cx  = x + col * CELL;
            int cy  = y + row * CELL;
            // Per-minute rate shown as stack count badge (clamped 1-999)
            // Contract entries always fire once per day; amount IS the daily delivery.
            int perDay = Math.min(999, Math.max(1, entry.amount()));
            ItemStack display = new ItemStack(entry.item(), perDay);
            g.renderItem(display, cx, cy + 1);
            g.renderItemDecorations(font, display, cx, cy + 1);
        }
    }

    @Override
    public void renderText(Font font, int x, int y, Matrix4f matrix, MultiBufferSource.BufferSource bufferSource) {
        // No text pass: the icon + count badge carries all information.
    }
}
