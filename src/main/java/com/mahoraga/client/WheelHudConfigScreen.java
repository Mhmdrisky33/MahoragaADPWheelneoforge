package com.mahoraga.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Simple in-game controls for moving and scaling the adaptation HUD. */
public final class WheelHudConfigScreen extends Screen {
    private final Screen parent;

    public WheelHudConfigScreen(Screen parent) {
        super(Component.translatable("screen.mahoraga.hud.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        WheelHudSettings.loadIfNeeded();
        int cx = this.width / 2;
        int top = this.height / 4;

        addPair(cx, top, "X", "−10", "＋10",
                () -> WheelHudSettings.moveX(-10), () -> WheelHudSettings.moveX(10));
        addPair(cx, top + 34, "Y", "−10", "＋10",
                () -> WheelHudSettings.moveY(-10), () -> WheelHudSettings.moveY(10));
        addPair(cx, top + 68, "Scale", "−", "＋",
                () -> WheelHudSettings.changeScale(-0.1F), () -> WheelHudSettings.changeScale(0.1F));

        addRenderableWidget(Button.builder(iconLabel(), b -> {
            WheelHudSettings.toggleIcon();
            rebuild();
        }).bounds(cx - 100, top + 108, 200, 20).build());

        addRenderableWidget(Button.builder(entriesLabel(), b -> {
            WheelHudSettings.toggleEntries();
            rebuild();
        }).bounds(cx - 100, top + 132, 200, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.mahoraga.hud.reset"), b -> {
            WheelHudSettings.reset();
            rebuild();
        }).bounds(cx - 100, top + 160, 98, 20).build());

        addRenderableWidget(Button.builder(CommonComponentsDone(), b -> onClose())
                .bounds(cx + 2, top + 160, 98, 20).build());
    }

    private void addPair(int cx, int y, String label, String minus, String plus, Runnable dec, Runnable inc) {
        addRenderableWidget(Button.builder(Component.literal(label + " " + minus), b -> {
            dec.run();
            rebuild();
        }).bounds(cx - 100, y, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal(label + " " + plus), b -> {
            inc.run();
            rebuild();
        }).bounds(cx + 4, y, 96, 20).build());
    }

    private Component iconLabel() {
        return Component.translatable("screen.mahoraga.hud.icon",
                WheelHudSettings.showIcon() ? "ON" : "OFF");
    }

    private Component entriesLabel() {
        return Component.translatable("screen.mahoraga.hud.entries",
                WheelHudSettings.showEntries() ? "ON" : "OFF");
    }

    private Component CommonComponentsDone() {
        return Component.translatable("gui.done");
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 4 - 28, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.mahoraga.hud.current", WheelHudSettings.x(),
                        WheelHudSettings.y(), String.format(java.util.Locale.ROOT, "%.1f", WheelHudSettings.scale())),
                this.width / 2, this.height / 4 + 96, 0xD0D0D0);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.mahoraga.hud.hint"),
                this.width / 2, this.height / 4 + 190, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        WheelHudSettings.save();
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
