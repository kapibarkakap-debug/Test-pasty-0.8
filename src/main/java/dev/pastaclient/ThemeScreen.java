package dev.pastaclient;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/** Theme tab: drag the Spotify HUD preview anywhere (saved automatically) and pick the accent colour. */
public class ThemeScreen extends Screen {
    static final int W = 150, H = 28, PINK = 0xFFFF4FC3;
    final Screen parent;
    boolean dragging;
    int dragDx, dragDy;

    public ThemeScreen(Screen parent) {
        super(Text.literal("Theme"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int sw = Math.min(200, width - 24), sx = width / 2 - sw / 2;
        addDrawableChild(new SettingSlider(sx, 22, sw, Visuals.GLOW_HUE));
        addDrawableChild(new SettingSlider(sx, 46, sw, Visuals.RAINBOW));
        addDrawableChild(ButtonWidget.builder(Text.literal("Reset position"), b -> {
            Visuals.spotX = 0.5f; Visuals.spotY = 0.01f; Visuals.savePos();
        }).dimensions(width / 2 - 105, height - 48, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                .dimensions(width / 2 + 5, height - 48, 100, 20).build());
        Tabs.build(4, width, height, w -> addDrawableChild(w));
    }

    int px() { return (int) (Visuals.spotX * (width - W)); }
    int py() { return (int) (Visuals.spotY * (height - H)); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, "Theme: drag the Spotify HUD to move it", width / 2, height / 2 - 10, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Position is saved automatically", width / 2, height / 2 + 2, 0xFFAAAAAA);

        int x = px(), y = py();
        ctx.fill(x, y, x + W, y + H, 0xCC000000);
        ctx.drawBorder(x, y, W, H, PINK);
        ctx.drawTextWithShadow(textRenderer, "Song title", x + 4, y + 3, 0xFFFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Artist", x + 4, y + 13, 0xFFAAAAAA);
        ctx.fill(x + 4, y + 24, x + W - 4, y + 26, 0x55FFFFFF);
        ctx.fill(x + 4, y + 24, x + 4 + (W - 8) * 2 / 5, y + 26, PINK);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = px(), y = py();
        if (mx >= x && mx < x + W && my >= y && my < y + H) {
            dragging = true; dragDx = (int) mx - x; dragDy = (int) my - y;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            Visuals.spotX = (float) MathHelper.clamp((mx - dragDx) / (double) Math.max(1, width - W), 0.0, 1.0);
            Visuals.spotY = (float) MathHelper.clamp((my - dragDy) / (double) Math.max(1, height - H), 0.0, 1.0);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) { dragging = false; Visuals.savePos(); }
        return super.mouseReleased(mx, my, button);
    }

    @Override public void removed() { Config.save(); }
    @Override public void close() { client.setScreen(parent); }
    @Override public boolean shouldPause() { return false; }
}
