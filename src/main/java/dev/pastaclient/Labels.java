package dev.pastaclient;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/** Floating text labels: chunk scores, amethyst geode stats, chest minecarts (Entity ESP). */
public final class Labels {
    /** One cluster of amethyst blocks found by the scanner. */
    public static final class Geode {
        public final Vec3d center; public final int clusters, budding, total;
        Geode(Vec3d c, int clusters, int budding, int total) { this.center = c; this.clusters = clusters; this.budding = budding; this.total = total; }
    }

    static volatile List<Geode> geodes = new ArrayList<>();

    /** Groups amethyst hit positions into geodes (blocks within 8 blocks of each other). */
    static void buildGeodes(MinecraftClient mc, List<BlockPos> amethyst) {
        List<List<BlockPos>> groups = new ArrayList<>();
        outer:
        for (BlockPos p : amethyst) {
            for (List<BlockPos> g : groups) {
                BlockPos ref = g.get(0);
                if (ref.getSquaredDistance(p) <= 64) { g.add(p); continue outer; }
            }
            List<BlockPos> g = new ArrayList<>(); g.add(p); groups.add(g);
        }
        List<Geode> out = new ArrayList<>();
        for (List<BlockPos> g : groups) {
            if (g.size() < 4) continue;
            int clusters = 0, budding = 0; double x = 0, y = 0, z = 0;
            for (BlockPos p : g) {
                var st = mc.world.getBlockState(p);
                if (st.isOf(Blocks.AMETHYST_CLUSTER)) clusters++;
                if (st.isOf(Blocks.BUDDING_AMETHYST)) budding++;
                x += p.getX() + 0.5; y += p.getY() + 0.5; z += p.getZ() + 0.5;
            }
            out.add(new Geode(new Vec3d(x / g.size(), y / g.size(), z / g.size()), clusters, budding, g.size()));
        }
        geodes = out;
    }

    static void drawText(MatrixStack ms, Camera camera, VertexConsumerProvider.Immediate vcp, Vec3d world, String text, int color) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        Vec3d rel = world.subtract(camera.getPos());
        float dist = (float) rel.length();
        float sc = Math.max(0.02f, dist * 0.0025f);
        ms.push();
        ms.translate(rel.x, rel.y, rel.z);
        ms.multiply(camera.getRotation());
        ms.scale(-sc, -sc, sc);
        Text t = Text.literal(text);
        tr.draw(t, -tr.getWidth(t) / 2f, 0, color, false, ms.peek().getPositionMatrix(), vcp,
                TextRenderer.TextLayerType.SEE_THROUGH, 0x66000000, 0xF000F0);
        ms.pop();
    }

    static void draw(MatrixStack ms, Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        VertexConsumerProvider.Immediate vcp = mc.getBufferBuilders().getEntityVertexConsumers();

        if (PastaClient.CHUNKS.on) {
            for (var en : PastaClient.flagged.entrySet()) {
                ChunkPos c = en.getKey(); int[] v = en.getValue();
                drawText(ms, camera, vcp, new Vec3d(c.getStartX() + 8, v[1] + 2, c.getStartZ() + 8),
                        "Chunk " + c.x + "," + c.z + "  score " + v[0], 0xFFFFD21A);
            }
        }
        if (PastaClient.AMETHYST.on) {
            for (Geode g : geodes) {
                int pct = g.total == 0 ? 0 : (int) Math.round(100.0 * g.budding / g.total);
                drawText(ms, camera, vcp, g.center.add(0, 1.5, 0),
                        "Amethyst Geode  clusters " + g.clusters + " | budding " + g.budding, 0xFFFF5555);
            }
        }
        if (PastaClient.ENTITY_ESP.on) {
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof ChestMinecartEntity)) continue;
                int d = (int) mc.player.distanceTo(e);
                drawText(ms, camera, vcp, e.getPos().add(0, e.getHeight() + 0.6, 0),
                        "Minecart with Chest [" + d + "m]", 0xFFFFFFFF);
            }
        }
        vcp.draw();
    }
}
