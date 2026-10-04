package dev.schembuilder;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** Danh sách block tương đối (so với origin) của một schematic. */
public class Schematic {
    public record Entry(BlockPos rel, BlockState state) {}
    public final String name;
    public final List<Entry> blocks = new ArrayList<>();
    public Schematic(String name) { this.name = name; }
}
