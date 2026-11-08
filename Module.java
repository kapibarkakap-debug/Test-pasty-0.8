package dev.pastaclient;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/** A toggleable feature shown in the ClickGUI. Block finders also carry a block test + colour. */
public class Module {
    public static class IntSetting {
        final String name; final int min, max; int value;
        IntSetting(String name, int min, int max, int value) { this.name = name; this.min = min; this.max = max; this.value = value; }
    }

    final String name, category;
    boolean on, expanded;
    final List<IntSetting> settings = new ArrayList<>();

    // block-finder support (null test = not a finder)
    Predicate<BlockState> test, test2;
    float[] c1, c2;
    int cap = 3000;
    final List<BlockPos> hits = new CopyOnWriteArrayList<>(), hits2 = new CopyOnWriteArrayList<>();

    Module(String category, String name, boolean on) { this.category = category; this.name = name; this.on = on; }

    Module finder(Predicate<BlockState> t, float r, float g, float b) { test = t; c1 = new float[]{r, g, b}; return this; }
    Module priority(Predicate<BlockState> t, float r, float g, float b) { test2 = t; c2 = new float[]{r, g, b}; return this; }
    Module setting(IntSetting s) { settings.add(s); return this; }
    Module cap(int c) { cap = c; return this; }
}
