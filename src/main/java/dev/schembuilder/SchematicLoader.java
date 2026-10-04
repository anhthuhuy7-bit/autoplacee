package dev.schembuilder;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class SchematicLoader {
    private SchematicLoader() {}

    public static Schematic load(Path file) throws IOException {
        NbtCompound root;
        try (InputStream in = Files.newInputStream(file)) {
            root = NbtIo.readCompressed(in, NbtSizeTracker.ofUnlimitedBytes());
        }
        String n = file.getFileName().toString().toLowerCase();
        Schematic s = new Schematic(file.getFileName().toString());
        if (n.endsWith(".litematic")) loadLitematic(root, s);
        else if (n.endsWith(".schem")) loadSponge(root, s);
        else throw new IOException("Chỉ hỗ trợ .litematic và .schem");
        // build từ dưới lên, từng lớp
        s.blocks.sort(Comparator.<Schematic.Entry>comparingInt(e -> e.rel().getY())
                .thenComparingInt(e -> e.rel().getX()).thenComparingInt(e -> e.rel().getZ()));
        return s;
    }

    // ---------- Litematica ----------
    private static void loadLitematic(NbtCompound root, Schematic out) {
        NbtCompound regions = root.getCompoundOrEmpty("Regions");
        for (String key : regions.getKeys()) {
            NbtCompound r = regions.getCompoundOrEmpty(key);
            NbtCompound pos = r.getCompoundOrEmpty("Position");
            NbtCompound size = r.getCompoundOrEmpty("Size");
            int sx = size.getInt("x", 0), sy = size.getInt("y", 0), sz = size.getInt("z", 0);
            int ax = Math.abs(sx), ay = Math.abs(sy), az = Math.abs(sz);
            int bx = pos.getInt("x", 0) + (sx < 0 ? sx + 1 : 0);
            int by = pos.getInt("y", 0) + (sy < 0 ? sy + 1 : 0);
            int bz = pos.getInt("z", 0) + (sz < 0 ? sz + 1 : 0);

            NbtList pal = r.getListOrEmpty("BlockStatePalette");
            BlockState[] palette = new BlockState[pal.size()];
            for (int i = 0; i < pal.size(); i++) palette[i] = fromPaletteTag(pal.getCompoundOrEmpty(i));

            long[] arr = r.getLongArray("BlockStates").orElse(new long[0]);
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.length - 1)));
            long mask = (1L << bits) - 1;
            for (int y = 0; y < ay; y++) for (int z = 0; z < az; z++) for (int x = 0; x < ax; x++) {
                long idx = ((long) y * az + z) * ax + x;
                long start = idx * bits;
                int a = (int) (start >>> 6), b = (int) ((start + bits - 1) >>> 6), off = (int) (start & 63);
                if (b >= arr.length) continue;
                long v = a == b ? (arr[a] >>> off) & mask : ((arr[a] >>> off) | (arr[b] << (64 - off))) & mask;
                if (v >= palette.length) continue;
                BlockState st = palette[(int) v];
                if (st.isAir()) continue;
                out.blocks.add(new Schematic.Entry(new BlockPos(bx + x, by + y, bz + z), st));
            }
        }
        normalize(out);
    }

    private static BlockState fromPaletteTag(NbtCompound tag) {
        Block b = Registries.BLOCK.get(Identifier.of(tag.getString("Name", "minecraft:air")));
        BlockState st = b.getDefaultState();
        NbtCompound props = tag.getCompoundOrEmpty("Properties");
        for (String k : props.getKeys()) st = withProp(st, k, props.getString(k, ""));
        return st;
    }

    // ---------- Sponge .schem (v2 + v3) ----------
    private static void loadSponge(NbtCompound root, Schematic out) {
        NbtCompound s = root.contains("Schematic") ? root.getCompoundOrEmpty("Schematic") : root;
        int w = s.getShort("Width", (short) 0), h = s.getShort("Height", (short) 0), l = s.getShort("Length", (short) 0);
        NbtCompound palTag; byte[] data;
        if (s.contains("Blocks")) { // v3
            NbtCompound blocks = s.getCompoundOrEmpty("Blocks");
            palTag = blocks.getCompoundOrEmpty("Palette");
            data = blocks.getByteArray("Data").orElse(new byte[0]);
        } else { // v2
            palTag = s.getCompoundOrEmpty("Palette");
            data = s.getByteArray("BlockData").orElse(new byte[0]);
        }
        java.util.Map<Integer, BlockState> palette = new java.util.HashMap<>();
        for (String key : palTag.getKeys()) palette.put(palTag.getInt(key, 0), parseStateString(key));

        int i = 0, idx = 0;
        while (i < data.length && idx < w * h * l) {
            int value = 0, shift = 0;
            while (true) {
                int b = data[i++];
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            BlockState st = palette.get(value);
            if (st != null && !st.isAir()) {
                int y = idx / (w * l), rem = idx % (w * l), z = rem / w, x = rem % w;
                out.blocks.add(new Schematic.Entry(new BlockPos(x, y, z), st));
            }
            idx++;
        }
        normalize(out);
    }

    private static BlockState parseStateString(String s) {
        int br = s.indexOf('[');
        String name = br < 0 ? s : s.substring(0, br);
        BlockState st = Registries.BLOCK.get(Identifier.of(name)).getDefaultState();
        if (br >= 0) {
            for (String kv : s.substring(br + 1, s.length() - 1).split(",")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2) st = withProp(st, p[0], p[1]);
            }
        }
        return st;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState withProp(BlockState st, String name, String value) {
        Property p = st.getBlock().getStateManager().getProperty(name);
        if (p == null) return st;
        return (BlockState) p.parse(value).map(v -> st.with(p, (Comparable) v)).orElse(st);
    }

    /** Dời để góc nhỏ nhất của schematic về (0,0,0). */
    private static void normalize(Schematic s) {
        if (s.blocks.isEmpty()) return;
        int mx = Integer.MAX_VALUE, my = mx, mz = mx;
        for (var e : s.blocks) { mx = Math.min(mx, e.rel().getX()); my = Math.min(my, e.rel().getY()); mz = Math.min(mz, e.rel().getZ()); }
        for (int i = 0; i < s.blocks.size(); i++) {
            var e = s.blocks.get(i);
            s.blocks.set(i, new Schematic.Entry(e.rel().add(-mx, -my, -mz), e.state()));
        }
    }
}
