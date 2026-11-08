package dev.pastaclient;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Arm;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;

/**
 * Cosmetic visuals (Hand Glow, Custom Crosshair, Watermark), friend ESP boxes,
 * the bottom tab bar on the main GUI, and config load/save hooks.
 * Registers its modules into the ClickGUI (Render panel).
 */
public class Visuals implements ClientModInitializer {
    static final Module.IntSetting GLOW_POWER = new Module.IntSetting("Intensity", 1, 10, 6);
    static final Module.IntSetting GLOW_SIZE  = new Module.IntSetting("Size", 40, 160, 90);
    static final Module.IntSetting GLOW_HUE   = new Module.IntSetting("Hue (0-360)", 0, 360, 320);
    static final Module.IntSetting RAINBOW    = new Module.IntSetting("Rainbow (0/1)", 0, 1, 0);

    static final Module.IntSetting XH_SIZE = new Module.IntSetting("Size", 2, 14, 6);
    static final Module.IntSetting XH_GAP  = new Module.IntSetting("Gap", 0, 6, 2);

    static final Module HAND_GLOW = PastaClient.reg(new Module("Render", "Hand Glow", true)
            .setting(GLOW_POWER).setting(GLOW_SIZE).setting(GLOW_HUE).setting(RAINBOW));
    static final Module CROSSHAIR = PastaClient.reg(new Module("Render", "Custom Crosshair", false)
            .setting(XH_SIZE).setting(XH_GAP).setting(GLOW_HUE));
    static final Module WATERMARK = PastaClient.reg(new Module("Render", "Watermark", true));

    // Spotify HUD lyrics options (shown in the Spotify HUD module's settings)
    static final Module.IntSetting LYRICS      = new Module.IntSetting("Lyrics (0/1)", 0, 1, 1);
    static final Module.IntSetting LYRICS_NEXT = new Module.IntSetting("Next line (0/1)", 0, 1, 0);
    static { PastaClient.SPOTIFY.setting(LYRICS).setting(LYRICS_NEXT); }

    // Spotify HUD position as a fraction of the free screen area (0..1), edited in the Theme tab
    static float spotX = 0.5f, spotY = 0.01f;

    static void savePos() { Config.save(); }

    @Override
    public void onInitializeClient() {
        Config.snapshotDefaults();
        Config.load();

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> draw(ctx));
        WorldRenderEvents.LAST.register(ctx -> renderFriends(ctx.matrixStack(), ctx.camera(), ctx.tickCounter().getTickDelta(false)));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Config.save());

        // bottom tab bar on the main (Modules) screen, and auto-save when it closes
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof PastaClientScreen) {
                Tabs.build(0, w, h, widget -> Screens.getButtons(screen).add(widget));
                ScreenEvents.remove(screen).register(s -> Config.save());
            }
        });
    }

    static int color() {
        float hue = RAINBOW.value == 1
                ? (System.currentTimeMillis() % 6000L) / 6000f
                : GLOW_HUE.value / 360f;
        return Color.HSBtoRGB(hue, 0.75f, 1f) & 0xFFFFFF;
    }

    // ---------- friends: green ESP box drawn over the red one ----------
    static void renderFriends(MatrixStack ms, Camera camera, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (ms == null || mc.world == null || Friends.LIST.isEmpty() || !PastaClient.PLAYER_ESP.on) return;

        RenderSystem.disableDepthTest();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        Matrix4f m = ms.peek().getPositionMatrix();
        Vec3d cam = camera.getPos();
        int n = 0;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !Friends.is(p.getName().getString())) continue;
            Vec3d lp = p.getLerpedPos(delta);
            Box box = p.getBoundingBox().offset(lp.x - p.getX(), lp.y - p.getY(), lp.z - p.getZ());
            PastaClient.box(bb, m, box, cam, 0.2f, 1f, 0.3f);
            n++;
        }
        if (n > 0) {
            BuiltBuffer built = bb.endNullable();
            if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        }
        RenderSystem.enableDepthTest();
    }

    // ---------- HUD visuals ----------
    static void draw(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        int rgb = color();

        if (WATERMARK.on) {
            ctx.drawTextWithShadow(mc.textRenderer, "Pasta Client | " + mc.getCurrentFps() + " fps",
                    4, h - 12, 0xFF000000 | rgb);
        }

        if (mc.currentScreen != null || !mc.options.getPerspective().isFirstPerson()) return;

        if (CROSSHAIR.on) {
            int cx = w / 2, cy = h / 2, s = XH_SIZE.value, g = XH_GAP.value, c = 0xFF000000 | rgb;
            ctx.fill(cx - g - s, cy, cx - g, cy + 1, c);
            ctx.fill(cx + g + 1, cy, cx + g + 1 + s, cy + 1, c);
            ctx.fill(cx, cy - g - s, cx + 1, cy - g, c);
            ctx.fill(cx, cy + g + 1, cx + 1, cy + g + 1 + s, c);
        }

        if (HAND_GLOW.on) {
            boolean left = mc.options.getMainArm().getValue() == Arm.LEFT;
            int cx = left ? (int) (w * 0.18) : (int) (w * 0.82);
            int cy = (int) (h * 0.88);
            float pulse = 0.65f + 0.35f * (float) Math.sin(System.currentTimeMillis() * 0.004);
            glow(ctx, cx, cy, GLOW_SIZE.value, rgb, GLOW_POWER.value / 10f * pulse);
        }
    }

    /** Soft round glow made of a few stacked translucent discs (brighter toward the middle). */
    static void glow(DrawContext ctx, int cx, int cy, int size, int rgb, float power) {
        int layers = 8;
        for (int i = 0; i < layers; i++) {
            float t = i / (float) (layers - 1);
            int r = (int) (size * (1f - t * 0.8f));
            int a = (int) (MathHelper.clamp(power * (0.06f + 0.14f * t), 0f, 1f) * 255);
            int col = (a << 24) | rgb;
            for (int dy = -r; dy < r; dy += 3) {
                int hw = (int) Math.sqrt((double) r * r - (double) dy * dy);
                ctx.fill(cx - hw, cy + dy, cx + hw, cy + dy + 3, col);
            }
        }
    }
}
