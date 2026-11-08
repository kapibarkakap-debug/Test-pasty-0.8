package dev.pastaclient;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.*;
import net.minecraft.world.chunk.*;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Pasta Client: base-finding overlays with a ClickGUI. */
public class PastaClient implements ClientModInitializer {
    // ---------- modules (shown in the ClickGUI) ----------
    static final List<Module> MODULES = new ArrayList<>();
    static Module reg(Module m) { MODULES.add(m); return m; }

    static final Module.IntSetting RADIUS = new Module.IntSetting("Scan radius", 2, 24, 6);
    static final Module.IntSetting THRESHOLD = new Module.IntSetting("Threshold", 1, 40, 12);

    static final Module PLAYER_ESP = reg(new Module("Render", "Player ESP", true));
    static final Module TRACERS    = reg(new Module("Render", "Tracers", false));
    static final Module NAMETAGS   = reg(new Module("Render", "Nametags", true));

    static final Module STORAGE  = reg(new Module("Finders", "Storage ESP", true)
            .finder(s -> weight(s) >= 3, 0.2f, 1f, 0.4f).setting(RADIUS));
    static final Module AMETHYST = reg(new Module("Finders", "Amethyst ESP", true)
            .finder(s -> isAmethyst(s) && !s.isOf(Blocks.BUDDING_AMETHYST), 0.6f, 0.3f, 1f)
            .priority(s -> s.isOf(Blocks.BUDDING_AMETHYST), 1f, 0.2f, 1f).cap(1500).setting(RADIUS));
    static final Module CHUNKS   = reg(new Module("Finders", "Sus Chunk Finder", true)
            .setting(THRESHOLD).setting(RADIUS));
    static final Module BLOCK_ESP = reg(new Module("Finders", "Block ESP", false)
            .finder(s -> s.isOf(Blocks.SPAWNER) || s.isOf(Blocks.ANCIENT_DEBRIS) || s.isOf(Blocks.DIAMOND_ORE)
                    || s.isOf(Blocks.DEEPSLATE_DIAMOND_ORE) || s.isOf(Blocks.EMERALD_ORE) || s.isOf(Blocks.DEEPSLATE_EMERALD_ORE),
                    0.2f, 1f, 1f).cap(1000).setting(RADIUS));
    static final Module ENTITY_ESP = reg(new Module("Render", "Entity ESP", true));

    static final Module.IntSetting SLIM = new Module.IntSetting("Slim arms (0/1)", 0, 1, 0);
    static final Module NAME_CHANGER = reg(new Module("Player", "Name Changer", false));
    static final Module SKIN_CHANGER = reg(new Module("Player", "Skin Changer", false).setting(SLIM));

    static final Module SPOTIFY       = reg(new Module("Misc", "Spotify HUD", false));
    static final Module PLAYER_ALERTS = reg(new Module("Misc", "Player Alerts", true));
    static final Module PLAYER_LIST   = reg(new Module("Misc", "Player List", true));

    // ---------- scan results / state ----------
    static final Map<ChunkPos, int[]> flagged = new ConcurrentHashMap<>(); // {score, avgY}
    static final Set<UUID> seen = new HashSet<>();
    static ClientWorld lastWorld;
    static String fakeName = "Pasta";
    KeyBinding kGui;

    @Override
    public void onInitializeClient() {
        kGui = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.pastaclient.gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, "key.categories.pastaclient"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (kGui.wasPressed()) mc.setScreen(new PastaClientScreen());
            if (mc.world == null || mc.player == null) return;
            detectPlayers(mc);
            SkinChanger.update(mc);
            SpotifyHud.tick();
            if (mc.world.getTime() % 60 == 0) scan(mc);
        });
        WorldRenderEvents.LAST.register(ctx -> render(ctx.matrixStack(), ctx.camera(), ctx.tickCounter().getTickDelta(false)));
        HudRenderCallback.EVENT.register(PastaClient::drawHud);

        // pause-menu button: touch-friendly way to open the GUI
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen)
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Pasta Client"),
                    b -> client.setScreen(new PastaClientScreen())).dimensions(w / 2 - 50, 4, 100, 20).build());
        });
    }

    // ---------- player detection ----------
    static void detectPlayers(MinecraftClient mc) {
        if (mc.world != lastWorld) { seen.clear(); lastWorld = mc.world; }
        Map<UUID, PlayerEntity> now = new HashMap<>();
        for (PlayerEntity p : mc.world.getPlayers()) if (p != mc.player) now.put(p.getUuid(), p);

        if (PLAYER_ALERTS.on) {
            for (var e : now.entrySet()) {
                if (seen.contains(e.getKey())) continue;
                PlayerEntity p = e.getValue();
                say(mc, "§e[Pasta Client] §f" + p.getName().getString() + " §7entered range at §f"
                        + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ()
                        + " §7(" + (int) mc.player.distanceTo(p) + "m)");
                mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.2f, 1f));
            }
            for (UUID id : seen) if (!now.containsKey(id)) say(mc, "§e[Pasta Client] §7a player left range");
        }
        seen.clear();
        seen.addAll(now.keySet());
    }

    static void say(MinecraftClient mc, String msg) { mc.inGameHud.getChatHud().addMessage(Text.literal(msg)); } // local only

    static void drawHud(DrawContext ctx, RenderTickCounter tc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        SpotifyHud.draw(ctx, mc);
        if (!PLAYER_LIST.on || mc.world == null || mc.player == null || mc.options.hudHidden) return;
        int y = 4;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            String s = p.getName().getString() + "  " + (int) mc.player.distanceTo(p) + "m";
            ctx.drawTextWithShadow(mc.textRenderer, s, ctx.getScaledWindowWidth() - mc.textRenderer.getWidth(s) - 4, y, 0xFFFF5555);
            y += 10;
        }
    }

    // ---------- scanning ----------
    /** "Player-made" signal for the suspicious-chunk score. 0 = ignore. */
    static int weight(BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) return 3;
        if (b == Blocks.ENDER_CHEST) return 4;
        if (s.isIn(BlockTags.SHULKER_BOXES)) return 5;
        if (b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER || b == Blocks.HOPPER) return 2;
        if (s.isIn(BlockTags.BEDS) || b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE) return 2;
        if (b == Blocks.GLASS || b == Blocks.GLASS_PANE || b == Blocks.BRICKS || b == Blocks.STONE_BRICKS) return 1;
        return 0;
    }

    static boolean isAmethyst(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER
            || b == Blocks.LARGE_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD;
    }

    void scan(MinecraftClient mc) {
        final boolean chunksOn = CHUNKS.on;
        final List<Module> active = new ArrayList<>();
        for (Module m : MODULES) if (m.on && m.test != null) active.add(m);

        if (active.isEmpty() && !chunksOn) {
            flagged.clear();
            for (Module m : MODULES) { m.hits.clear(); m.hits2.clear(); }
            return;
        }

        final Map<Module, List<BlockPos>> a = new IdentityHashMap<>(), b = new IdentityHashMap<>();
        for (Module m : active) { a.put(m, new ArrayList<>()); b.put(m, new ArrayList<>()); }
        Map<ChunkPos, int[]> newFlagged = new HashMap<>();

        var world = mc.world;
        ChunkPos pc = mc.player.getChunkPos();
        int r = RADIUS.value;

        for (int cx = pc.x - r; cx <= pc.x + r; cx++) {
            for (int cz = pc.z - r; cz <= pc.z + r; cz++) {
                Chunk chunk = world.getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                int score = 0, n = 0; long ySum = 0;
                ChunkSection[] secs = chunk.getSectionArray();
                for (int i = 0; i < secs.length; i++) {
                    ChunkSection sec = secs[i];
                    if (sec == null || sec.isEmpty() || !sec.hasAny(s -> interesting(s, chunksOn, active))) continue;
                    int baseY = chunk.getBottomY() + i * 16;
                    for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                        BlockState bs = sec.getBlockState(x, y, z);
                        int w = chunksOn ? weight(bs) : 0;
                        BlockPos pos = null;
                        for (Module m : active) {
                            boolean h1 = m.test.test(bs);
                            boolean h2 = !h1 && m.test2 != null && m.test2.test(bs);
                            if (!h1 && !h2) continue;
                            if (pos == null) pos = new BlockPos(cx * 16 + x, baseY + y, cz * 16 + z);
                            List<BlockPos> l = (h1 ? a : b).get(m);
                            if (l.size() < m.cap) l.add(pos);
                        }
                        if (w > 0) { score += w; ySum += baseY + y; n++; }
                    }
                }
                if (chunksOn && score >= THRESHOLD.value)
                    newFlagged.put(new ChunkPos(cx, cz), new int[]{score, (int) (ySum / Math.max(n, 1))});
            }
        }
        flagged.clear(); flagged.putAll(newFlagged);
        for (Module m : MODULES) {
            m.hits.clear(); m.hits2.clear();
            if (a.containsKey(m)) { m.hits.addAll(a.get(m)); m.hits2.addAll(b.get(m)); }
        }
        if (AMETHYST.on) {
            List<BlockPos> all = new ArrayList<>(AMETHYST.hits);
            all.addAll(AMETHYST.hits2);
            Labels.buildGeodes(mc, all);
        } else Labels.geodes = new ArrayList<>();
    }

    static boolean interesting(BlockState s, boolean chunksOn, List<Module> active) {
        if (chunksOn && weight(s) > 0) return true;
        for (Module m : active) if (m.test.test(s) || (m.test2 != null && m.test2.test(s))) return true;
        return false;
    }

    // ---------- rendering ----------
    void render(MatrixStack ms, Camera camera, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || ms == null) return;
        Vec3d cam = camera.getPos();

        RenderSystem.disableDepthTest(); // through walls
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        Matrix4f m = ms.peek().getPositionMatrix();

        if (PLAYER_ESP.on || TRACERS.on) {
            Vec3d look = Vec3d.fromPolar(camera.getPitch(), camera.getYaw()).multiply(0.5);
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                double dx = MathHelper.lerp(delta, p.lastRenderX, p.getX()) - p.getX();
                double dy = MathHelper.lerp(delta, p.lastRenderY, p.getY()) - p.getY();
                double dz = MathHelper.lerp(delta, p.lastRenderZ, p.getZ()) - p.getZ();
                Box box = p.getBoundingBox().offset(dx, dy, dz);
                if (PLAYER_ESP.on) box(bb, m, box, cam, 1f, 0.2f, 0.2f);
                if (TRACERS.on) {
                    Vec3d c = box.getCenter().subtract(cam);
                    line(bb, m, (float) look.x, (float) look.y, (float) look.z, (float) c.x, (float) c.y, (float) c.z, 1f, 0.3f, 0.75f);
                }
            }
        }
        if (ENTITY_ESP.on) {
            for (net.minecraft.entity.Entity e : mc.world.getEntities()) {
                if (e instanceof net.minecraft.entity.vehicle.ChestMinecartEntity)
                    box(bb, m, e.getBoundingBox(), cam, 1f, 1f, 1f);
            }
        }
        for (Module mod : MODULES) {
            if (!mod.on || mod.test == null) continue;
            for (BlockPos p : mod.hits) box(bb, m, new Box(p), cam, mod.c1[0], mod.c1[1], mod.c1[2]);
            if (mod.c2 != null) for (BlockPos p : mod.hits2) box(bb, m, new Box(p), cam, mod.c2[0], mod.c2[1], mod.c2[2]);
        }
        if (CHUNKS.on) {
            for (var en : flagged.entrySet()) {
                ChunkPos c = en.getKey(); int y = en.getValue()[1];
                box(bb, m, new Box(c.getStartX(), y, c.getStartZ(), c.getStartX() + 16, y + 1, c.getStartZ() + 16), cam, 1f, 0.85f, 0.1f);
            }
        }
        BuiltBuffer built = bb.endNullable();
        if (built != null) BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.disableBlend();

        if (NAMETAGS.on) {
            TextRenderer tr = mc.textRenderer;
            VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                Vec3d lp = p.getLerpedPos(delta).add(0, p.getHeight() + 0.5, 0).subtract(cam);
                float dist = (float) lp.length();
                float sc = Math.max(0.025f, dist * 0.0035f);   // keeps tags readable at range
                ms.push();
                ms.translate(lp.x, lp.y, lp.z);
                ms.multiply(camera.getRotation());              // face the camera
                ms.scale(-sc, -sc, sc);
                float hp = p.getHealth() + p.getAbsorptionAmount();
                Formatting hc = hp > 14 ? Formatting.GREEN : hp > 7 ? Formatting.YELLOW : Formatting.RED;
                Text t = Text.literal(p.getName().getString() + " ")
                        .append(Text.literal(String.format("%.1f", hp)).formatted(hc))
                        .append(Text.literal(" " + (int) dist + "m").formatted(Formatting.GRAY));
                tr.draw(t, -tr.getWidth(t) / 2f, 0, 0xFFFFFFFF, false, ms.peek().getPositionMatrix(), vcp,
                        TextRenderer.TextLayerType.SEE_THROUGH, 0x66000000, 0xF000F0);
                ms.pop();
            }
            vcp.draw();
        }
        Labels.draw(ms, camera);
        RenderSystem.enableDepthTest();
    }

    static void line(BufferBuilder bb, Matrix4f m, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b) {
        bb.vertex(m, x1, y1, z1).color(r, g, b, 1f);
        bb.vertex(m, x2, y2, z2).color(r, g, b, 1f);
    }

    static void box(BufferBuilder bb, Matrix4f m, Box b, Vec3d cam, float r, float g, float bl) {
        float x1 = (float) (b.minX - cam.x), y1 = (float) (b.minY - cam.y), z1 = (float) (b.minZ - cam.z);
        float x2 = (float) (b.maxX - cam.x), y2 = (float) (b.maxY - cam.y), z2 = (float) (b.maxZ - cam.z);
        float[][] v = {{x1,y1,z1},{x2,y1,z1},{x2,y1,z2},{x1,y1,z2},{x1,y2,z1},{x2,y2,z1},{x2,y2,z2},{x1,y2,z2}};
        int[][] edges = {{0,1},{1,2},{2,3},{3,0},{4,5},{5,6},{6,7},{7,4},{0,4},{1,5},{2,6},{3,7}};
        for (int[] e : edges) line(bb, m, v[e[0]][0], v[e[0]][1], v[e[0]][2], v[e[1]][0], v[e[1]][1], v[e[1]][2], r, g, bl);
    }

    /** Name Changer: swaps your real username for fakeName in chat. Local display only; other players still see your real name. */
    public static Text applyNameChanger(Text t) {
        if (!NAME_CHANGER.on || fakeName == null || fakeName.isBlank()) return t;
        MinecraftClient mc = MinecraftClient.getInstance();
        String real = mc.getSession().getUsername();
        if (real == null || real.isEmpty() || !t.getString().contains(real)) return t;
        MutableText out = Text.empty();
        t.visit((style, str) -> { out.append(Text.literal(str.replace(real, fakeName)).setStyle(style)); return Optional.empty(); }, Style.EMPTY);
        return out;
    }
}
