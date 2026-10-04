package dev.schembuilder;

import net.minecraft.block.*;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.*;

/**
 * Engine build có hỗ trợ hướng + trạng thái block.
 * Với mỗi block, thử các tổ hợp (block đỡ, mặt click, độ cao click, yaw, pitch),
 * mô phỏng Block#getPlacementState ở client, và chỉ dùng tổ hợp cho ra đúng state mong muốn.
 */
public class BuildEngine {
    /** Các property được so khớp (phần còn lại như powered, lit, shape, kết nối hàng rào... do game tự tính). */
    private static final Set<String> RELEVANT = Set.of("facing", "half", "axis", "face", "rotation", "attachment", "type");
    private static final Direction[] DIRS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    private static final float[] YAW4 = {0f, 90f, 180f, -90f};
    private static final float[] YAW16 = new float[16];
    private static final float[] PITCHES = {0f, -80f, 80f};
    static { for (int i = 0; i < 16; i++) YAW16[i] = i * 22.5f - 180f; }

    private record Plan(BlockPos support, Direction clicked, Vec3d hit, float yaw, float pitch, boolean sneak) {}

    public Schematic schematic;
    public BlockPos origin;
    public boolean running;
    public int blocksPerTick = 2;
    public double reach = 4.5;

    private int cursor, placedCount, tickCounter;
    private int[] attempts = new int[0], fails = new int[0];
    private final Map<Integer, Integer> retryAt = new HashMap<>();
    private boolean noSupport;

    // ---- scaffold ----
    public boolean scaffoldEnabled = true;
    public Item scaffoldItem;                       // null = tự chọn từ kho
    private final Map<Long, Integer> scaffolds = new HashMap<>(); // vị trí -> tick đã đặt
    private final Map<Long, Integer> targetIndex = new HashMap<>(); // vị trí đích của schematic -> index
    private BlockPos breaking;
    public boolean cleaning;
    private static final Item[] DEFAULT_SCAFFOLD = {Items.COBBLESTONE, Items.DIRT, Items.NETHERRACK, Items.COBBLED_DEEPSLATE,
            Items.STONE, Items.ANDESITE, Items.GRANITE, Items.DIORITE, Items.OAK_PLANKS};

    public void load(Schematic s) {
        schematic = s; cursor = 0; placedCount = 0; running = false;
        attempts = new int[s.blocks.size()]; fails = new int[s.blocks.size()];
        retryAt.clear();
        scaffolds.clear(); targetIndex.clear(); breaking = null;
    }

    public void start() {
        targetIndex.clear();
        for (int i = 0; i < schematic.blocks.size(); i++) targetIndex.put(origin.add(schematic.blocks.get(i).rel()).asLong(), i);
        running = true;
    }
    public int scaffoldCount() { return scaffolds.size(); }
    public int total() { return schematic == null ? 0 : schematic.blocks.size(); }
    public int placed() { return placedCount; }

    /** Số block bị bỏ qua (nửa trên cửa, đầu giường, chất lỏng... hoặc không thể đặt đúng state). */
    public int skipped() {
        if (schematic == null) return 0;
        int n = 0;
        for (int i = 0; i < schematic.blocks.size(); i++) if (isGivenUp(i)) n++;
        return n;
    }
    private boolean isGivenUp(int i) {
        return unplaceableKind(schematic.blocks.get(i).state()) || attempts[i] >= 3 || fails[i] >= 5;
    }

    /** Block mà đặt một nửa sẽ tự sinh nửa còn lại, hoặc không có item. */
    private static boolean unplaceableKind(BlockState s) {
        if (s.getBlock().asItem() == Items.AIR) return true;
        if (s.contains(Properties.DOUBLE_BLOCK_HALF) && s.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
        if (s.contains(Properties.BED_PART) && s.get(Properties.BED_PART) == BedPart.HEAD) return true;
        return false;
    }

    public static boolean matches(BlockState a, BlockState want) {
        if (a.getBlock() != want.getBlock()) return false;
        for (Property<?> p : want.getProperties()) {
            String n = p.getName();
            if (!RELEVANT.contains(n)) continue;
            if (n.equals("type") && !(want.getBlock() instanceof SlabBlock)) continue;
            if (!a.get(p).equals(want.get(p))) return false;
        }
        return true;
    }

    public void tick(MinecraftClient mc) {
        if ((!running && !cleaning) || mc.player == null || mc.world == null) return;
        ClientPlayerEntity player = mc.player;
        if (mc.interactionManager == null) return;
        tickCounter++;

        if (cleaning) {                       // chỉ dọn scaffold
            boolean busy = handleScaffoldCleanup(mc, true);
            if (!busy && scaffolds.isEmpty()) { cleaning = false; player.sendMessage(Text.literal("[SchemBuilder] Đã dọn xong scaffold."), false); }
            else if (!busy && player.age % 60 == 0) player.sendMessage(Text.literal("[SchemBuilder] Đi lại gần để dọn " + scaffolds.size() + " scaffold còn lại"), true);
            return;
        }
        if (schematic == null || origin == null) return;

        boolean allBuilt = cursor >= schematic.blocks.size();
        if (handleScaffoldCleanup(mc, allBuilt)) return;   // đang đập scaffold thì không đặt block
        if (allBuilt) {
            if (scaffolds.isEmpty()) {
                running = false;
                player.sendMessage(Text.literal("[SchemBuilder] Hoàn thành! Bỏ qua " + skipped() + " block."), false);
            } else if (player.age % 60 == 0) {
                player.sendMessage(Text.literal("[SchemBuilder] Đi lại gần để dọn " + scaffolds.size() + " scaffold còn lại"), true);
            }
            return;
        }

        int done = 0, scanned = 0, i = cursor;
        int outOfReach = 0, noPlan = 0, waiting = 0;
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        boolean contiguous = true;
        for (; i < schematic.blocks.size() && done < blocksPerTick && scanned < 30000; i++, scanned++) {
            var e = schematic.blocks.get(i);
            BlockState want = e.state();
            if (isGivenUp(i)) { if (contiguous) cursor = i + 1; continue; }

            BlockPos target = origin.add(e.rel());
            boolean far = player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) > reach * reach;
            if (far && !contiguous) { outOfReach++; continue; }   // rẻ: bỏ qua sớm, không tra thế giới
            BlockState cur = mc.world.getBlockState(target);
            if (matches(cur, want)) { if (contiguous) cursor = i + 1; continue; }
            contiguous = false;

            if (retryAt.getOrDefault(i, 0) > tickCounter) { waiting++; continue; }
            if (far) { outOfReach++; continue; }

            Plan plan = plan(mc, target, want, cur);
            if (plan == null) {
                if (noSupport && scaffoldEnabled && scaffolds.size() < 300 && placeScaffold(mc, target)) {
                    retryAt.put(i, tickCounter + 3);
                    done++;
                    continue;
                }
                if (!noSupport) fails[i]++;           // có chỗ tựa nhưng không ra được state đúng
                retryAt.put(i, tickCounter + 20);
                noPlan++;
                continue;
            }
            if (!selectItem(mc, want.getBlock().asItem())) {
                missing.add(want.getBlock().getName().getString());
                retryAt.put(i, tickCounter + 20);
                continue;
            }

            place(mc, plan);
            attempts[i]++;
            retryAt.put(i, tickCounter + 4);          // chờ server đồng bộ trước khi kiểm tra lại
            placedCount++;
            done++;
        }

        if (cursor >= schematic.blocks.size()) {
            // xử lý ở tick sau (dọn scaffold rồi báo hoàn thành)
        } else if (done == 0 && player.age % 60 == 0) {
            StringBuilder sb = new StringBuilder("[SchemBuilder] Chưa đặt được. Ngoài tầm với: " + outOfReach
                    + ", chờ thử lại: " + waiting + ", không tìm được cách đặt: " + noPlan
                    + ", thiếu vật liệu: " + (missing.isEmpty() ? "không" : String.join(", ", missing)));
            sb.append(". Còn lại ").append(schematic.blocks.size() - cursor).append(" block kể từ vị trí hiện tại.");
            player.sendMessage(Text.literal(sb.toString()), false);
        }
    }

    /** Liệt kê vật liệu cần / đang có. */
    public java.util.List<String> materials(MinecraftClient mc) {
        java.util.Map<Item, Integer> need = new java.util.LinkedHashMap<>();
        if (schematic != null) for (int i = 0; i < schematic.blocks.size(); i++) {
            if (unplaceableKind(schematic.blocks.get(i).state())) continue;
            BlockState st = schematic.blocks.get(i).state();
            int n = (st.getBlock() instanceof SlabBlock && st.get(SlabBlock.TYPE) == SlabType.DOUBLE) ? 2 : 1;
            need.merge(st.getBlock().asItem(), n, Integer::sum);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        need.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(en -> {
            int have = 0;
            if (mc.player != null) {
                var inv = mc.player.getInventory();
                for (int k = 0; k < 36; k++) if (inv.getStack(k).isOf(en.getKey())) have += inv.getStack(k).getCount();
            }
            out.add(en.getKey().getName().getString() + ": cần " + en.getValue() + ", có " + have);
        });
        return out;
    }

    private Plan plan(MinecraftClient mc, BlockPos target, BlockState want, BlockState cur) {
        ClientPlayerEntity p = mc.player;
        World w = mc.world;
        Block block = want.getBlock();
        ItemStack stack = new ItemStack(block.asItem());
        float oy = p.getYaw(), op = p.getPitch();
        noSupport = true;
        try {
            // Slab đôi: đặt thêm một slab cùng loại vào slab đơn đang có
            if (block instanceof SlabBlock && want.get(SlabBlock.TYPE) == SlabType.DOUBLE
                    && cur.isOf(block) && cur.get(SlabBlock.TYPE) != SlabType.DOUBLE) {
                noSupport = false;
                Direction side = cur.get(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.UP : Direction.DOWN;
                return new Plan(target, side, Vec3d.ofCenter(target), oy, op, false);
            }
            if (!cur.isReplaceable()) { noSupport = false; return null; }

            float[] yaws = want.contains(Properties.ROTATION) ? YAW16 : YAW4;
            for (int pass = 0; pass < 2; pass++) for (Direction d : DIRS) {
                BlockPos sp = target.offset(d);
                BlockState ss = w.getBlockState(sp);
                if (ss.isReplaceable() || ss.getCollisionShape(w, sp).isEmpty()) continue;
                if (scaffolds.containsKey(sp.asLong()) != (pass == 1)) continue; // ưu tiên block thật, scaffold để sau
                noSupport = false;
                Direction clicked = d.getOpposite();
                Vec3d c = Vec3d.ofCenter(sp);
                double[] fracs = clicked.getAxis().isHorizontal() ? new double[]{0.25, 0.75} : new double[]{0.5};
                for (double f : fracs) {
                    Vec3d hit = clicked.getAxis().isHorizontal()
                            ? new Vec3d(c.x + clicked.getOffsetX() * 0.5, sp.getY() + f, c.z + clicked.getOffsetZ() * 0.5)
                            : new Vec3d(c.x, sp.getY() + (clicked == Direction.UP ? 1.0 : 0.0), c.z);
                    BlockHitResult bhr = new BlockHitResult(hit, clicked, sp, false);
                    for (float yaw : yaws) for (float pitch : PITCHES) {
                        p.setYaw(yaw); p.setPitch(pitch);
                        ItemPlacementContext ctx = new ItemPlacementContext(w, p, Hand.MAIN_HAND, stack, bhr);
                        if (!ctx.canPlace() || !ctx.getBlockPos().equals(target)) continue;
                        BlockState st = block.getPlacementState(ctx);
                        if (st == null || !matches(st, want) || !st.canPlaceAt(w, target)) continue;
                        if (!w.canPlace(st, target, ShapeContext.of(p))) continue;
                        return new Plan(sp, clicked, hit, yaw, pitch, needsSneak(ss));
                    }
                }
            }
            return null;
        } finally {
            p.setYaw(oy); p.setPitch(op);
        }
    }

    // ======================= SCAFFOLD =======================
    private boolean hasItem(MinecraftClient mc, Item it) {
        var inv = mc.player.getInventory();
        for (int k = 0; k < 36; k++) if (inv.getStack(k).isOf(it)) return true;
        return false;
    }

    private Item pickScaffoldItem(MinecraftClient mc) {
        boolean creative = mc.player.isCreative();
        if (scaffoldItem != null && (creative || hasItem(mc, scaffoldItem))) return scaffoldItem;
        for (Item it : DEFAULT_SCAFFOLD) if (hasItem(mc, it)) return it;
        return creative ? Items.COBBLESTONE : null;
    }

    private static boolean solid(World w, BlockPos p) {
        BlockState st = w.getBlockState(p);
        return !st.isReplaceable() && !st.getCollisionShape(w, p).isEmpty();
    }

    private boolean hasSolidNeighbor(World w, BlockPos p) {
        for (Direction d : DIRS) if (solid(w, p.offset(d))) return true;
        return false;
    }

    /** Tìm đường cell trống từ target tới một cell có chỗ tựa, rồi đặt scaffold cell kế tiếp. */
    private boolean placeScaffold(MinecraftClient mc, BlockPos target) {
        World w = mc.world;
        ClientPlayerEntity p = mc.player;
        Item item = pickScaffoldItem(mc);
        if (item == null || !(item instanceof BlockItem bi)) return false;

        Map<Long, BlockPos> parent = new HashMap<>();
        Map<Long, Integer> dist = new HashMap<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        dist.put(target.asLong(), 0);
        q.add(target);
        BlockPos found = null;
        while (!q.isEmpty() && found == null) {
            BlockPos c = q.poll();
            int dc = dist.get(c.asLong());
            if (dc >= 6) continue;
            for (Direction d : DIRS) {
                BlockPos n = c.offset(d);
                long k = n.asLong();
                if (dist.containsKey(k)) continue;
                if (!w.getBlockState(n).isReplaceable()) continue;
                if (targetIndex.containsKey(k)) continue;           // chỗ này dành cho schematic
                dist.put(k, dc + 1); parent.put(k, c); q.add(n);
                if (hasSolidNeighbor(w, n)) { found = n; break; }
            }
        }
        if (found == null) return false;

        // đường đi: found (sát đất) -> ... -> ô kề target
        for (BlockPos c = found; c != null && !c.equals(target); c = parent.get(c.asLong())) {
            if (!w.getBlockState(c).isReplaceable()) continue;      // đã có rồi
            if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(c)) > reach * reach) return false;
            for (Direction d : DIRS) {
                BlockPos sp = c.offset(d);
                if (!solid(w, sp)) continue;
                if (!w.canPlace(bi.getBlock().getDefaultState(), c, ShapeContext.of(p))) return false; // đang đứng trong ô đó
                if (!selectItem(mc, item)) return false;
                Direction clicked = d.getOpposite();
                Vec3d cc = Vec3d.ofCenter(sp);
                Vec3d hit = clicked.getAxis().isHorizontal()
                        ? new Vec3d(cc.x + clicked.getOffsetX() * 0.5, sp.getY() + 0.5, cc.z + clicked.getOffsetZ() * 0.5)
                        : new Vec3d(cc.x, sp.getY() + (clicked == Direction.UP ? 1.0 : 0.0), cc.z);
                place(mc, new Plan(sp, clicked, hit, p.getYaw(), p.getPitch(), needsSneak(w.getBlockState(sp))));
                scaffolds.put(c.asLong(), tickCounter);
                placedCount++;
                return true;
            }
        }
        return false;
    }

    /** Dọn scaffold không còn cần. Trả về true nếu đang đập block (tick này không đặt gì khác). */
    private boolean handleScaffoldCleanup(MinecraftClient mc, boolean force) {
        ClientPlayerInteractionManager im = mc.interactionManager;
        World w = mc.world;
        ClientPlayerEntity p = mc.player;

        if (breaking != null) {
            if (w.getBlockState(breaking).isReplaceable()) { scaffolds.remove(breaking.asLong()); breaking = null; return false; }
            if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(breaking)) > reach * reach) { im.cancelBlockBreaking(); breaking = null; return false; }
            im.updateBlockBreakingProgress(breaking, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
            return true;
        }
        scaffolds.entrySet().removeIf(en -> tickCounter - en.getValue() > 10
                && w.getBlockState(BlockPos.fromLong(en.getKey())).isReplaceable());
        if (scaffolds.isEmpty()) return false;
        if (!force && tickCounter % 10 != 0) return false;

        Set<Long> needed = new HashSet<>();
        if (!force) {
            for (long key : scaffolds.keySet()) {
                BlockPos s = BlockPos.fromLong(key);
                for (Direction d : DIRS) {
                    BlockPos n = s.offset(d);
                    Integer idx = targetIndex.get(n.asLong());
                    if (idx != null && !isGivenUp(idx) && !matches(w.getBlockState(n), schematic.blocks.get(idx).state())) { needed.add(key); break; }
                }
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (long key : scaffolds.keySet()) {
                    if (needed.contains(key)) continue;
                    BlockPos s = BlockPos.fromLong(key);
                    for (Direction d : DIRS) if (needed.contains(s.offset(d).asLong())) { needed.add(key); changed = true; break; }
                }
            }
        }
        for (long key : scaffolds.keySet()) {
            if (needed.contains(key)) continue;
            BlockPos s = BlockPos.fromLong(key);
            BlockState st = w.getBlockState(s);
            if (st.isReplaceable()) continue;
            if (p.getEyePos().squaredDistanceTo(Vec3d.ofCenter(s)) > reach * reach) continue;
            var inv = p.getInventory();
            int best = inv.getSelectedSlot(); float bs = 0;
            for (int i = 0; i < 9; i++) { float sp = inv.getStack(i).getMiningSpeedMultiplier(st); if (sp > bs) { bs = sp; best = i; } }
            inv.setSelectedSlot(best);
            im.attackBlock(s, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
            breaking = s;
            return true;
        }
        return false;
    }

    private static boolean needsSneak(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof NoteBlock || b instanceof RepeaterBlock
                || b instanceof ComparatorBlock || b instanceof BedBlock;
    }

    private void place(MinecraftClient mc, Plan plan) {
        ClientPlayerEntity p = mc.player;
        float oy = p.getYaw(), op = p.getPitch();
        p.setYaw(plan.yaw()); p.setPitch(plan.pitch());
        p.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(plan.yaw(), plan.pitch(), p.isOnGround(), p.horizontalCollision));

        PlayerInput prev = p.input.playerInput;
        boolean wasSneaking = p.isSneaking();
        if (plan.sneak()) { // tránh mở GUI của block đỡ (rương, cửa, nút...)
            p.networkHandler.sendPacket(new PlayerInputC2SPacket(new PlayerInput(prev.forward(), prev.backward(), prev.left(), prev.right(), prev.jump(), true, prev.sprint())));
            p.setSneaking(true);
        }
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(plan.hit(), plan.clicked(), plan.support(), false));
        p.swingHand(Hand.MAIN_HAND);
        if (plan.sneak()) {
            p.setSneaking(wasSneaking);
            p.networkHandler.sendPacket(new PlayerInputC2SPacket(prev));
        }
        p.setYaw(oy); p.setPitch(op);
    }

    private boolean selectItem(MinecraftClient mc, Item item) {
        ClientPlayerEntity p = mc.player;
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) if (inv.getStack(i).isOf(item)) { inv.setSelectedSlot(i); return true; }
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(item)) {
                mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, i, inv.getSelectedSlot(), SlotActionType.SWAP, p);
                return true;
            }
        }
        if (p.isCreative()) {
            mc.interactionManager.clickCreativeStack(new ItemStack(item, 64), 36 + inv.getSelectedSlot());
            return true;
        }
        return false;
    }
}
