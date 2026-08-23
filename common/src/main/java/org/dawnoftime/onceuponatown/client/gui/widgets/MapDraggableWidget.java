package org.dawnoftime.onceuponatown.client.gui.widgets;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MapDraggableWidget extends DraggableWidget {

    private final TownMapWidget mapWidget;

    public MapDraggableWidget(int x, int y, int width, int height,
                               int freeZoneMaxX, int screenH, CompoundTag mapData) {
        super(x, y, width, height, freeZoneMaxX, screenH);
        this.mapWidget = new TownMapWidget(x, y + TITLE_BAR_H, width, height - TITLE_BAR_H, mapData);
    }

    public void updateMapData(CompoundTag mapData) {
        mapWidget.updateMapData(mapData);
    }

    public void setOnBuildingClicked(BiConsumer<Long, String> callback) {
        mapWidget.setOnBuildingClicked(callback);
    }

    public void setOnBuildingRightClicked(Consumer<Long> callback) {
        mapWidget.setOnBuildingRightClicked(callback);
    }

    public void setOnBuildingRepairClicked(Consumer<Long> callback) {
        mapWidget.setOnBuildingRepairClicked(callback);
    }

    @Override
    protected void onMoved() {
        mapWidget.reposition(x, y + TITLE_BAR_H, width, height - TITLE_BAR_H);
    }

    @Override
    protected void renderContent(GuiGraphics g, int cx, int cy, int cw, int ch,
                                  int mouseX, int mouseY, float delta) {
        g.fill(cx, cy, cx + cw, cy + ch, 0xF51A1A1A);
        mapWidget.render(g, mouseX, mouseY, delta);
    }

    @Override
    protected String getTitle() { return "Map"; }

    @Override
    protected boolean contentMouseClicked(double mouseX, double mouseY, int button) {
        return mapWidget.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean contentMouseDragged(double mX, double mY, int button, double dX, double dY) {
        return mapWidget.mouseDragged(mX, mY, button, dX, dY);
    }

    @Override
    protected boolean contentMouseReleased(double mouseX, double mouseY, int button) {
        return mapWidget.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    protected boolean contentMouseScrolled(double mouseX, double mouseY, double delta) {
        return mapWidget.mouseScrolled(mouseX, mouseY, delta);
    }
}
