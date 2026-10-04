package dev.schembuilder;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class SchemBuilderClient implements ClientModInitializer {
    public static final BuildEngine ENGINE = new BuildEngine();

    @Override
    public void onInitializeClient() {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try { Files.createDirectories(dir); } catch (Exception ignored) {}

        ClientTickEvents.END_CLIENT_TICK.register(ENGINE::tick);

        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> d.register(
            ClientCommandManager.literal("sbuild")
                .then(ClientCommandManager.literal("list").executes(c -> {
                    try (Stream<Path> s = Files.list(dir)) {
                        s.map(p -> p.getFileName().toString())
                         .filter(n -> n.endsWith(".litematic") || n.endsWith(".schem"))
                         .forEach(n -> c.getSource().sendFeedback(Text.literal(" - " + n)));
                    } catch (Exception e) { c.getSource().sendError(Text.literal(e.toString())); }
                    return 1;
                }))
                .then(ClientCommandManager.literal("load").then(ClientCommandManager.argument("file", StringArgumentType.greedyString()).executes(c -> {
                    String f = StringArgumentType.getString(c, "file");
                    try {
                        Schematic s = SchematicLoader.load(dir.resolve(f));
                        ENGINE.load(s);
                        c.getSource().sendFeedback(Text.literal("Đã load " + s.name + ": " + s.blocks.size() + " block"));
                    } catch (Exception e) { c.getSource().sendError(Text.literal("Lỗi: " + e)); }
                    return 1;
                })))
                .then(ClientCommandManager.literal("origin").executes(c -> {
                    ENGINE.origin = BlockPos.ofFloored(c.getSource().getPosition());
                    c.getSource().sendFeedback(Text.literal("Origin = " + ENGINE.origin.toShortString()));
                    return 1;
                }))
                .then(ClientCommandManager.literal("start").executes(c -> {
                    if (ENGINE.schematic == null || ENGINE.origin == null) {
                        c.getSource().sendError(Text.literal("Cần /sbuild load và /sbuild origin trước"));
                        return 0;
                    }
                    ENGINE.start();
                    c.getSource().sendFeedback(Text.literal("Bắt đầu build"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("materials").executes(c -> {
                    var list = ENGINE.materials(net.minecraft.client.MinecraftClient.getInstance());
                    list.stream().limit(25).forEach(l -> c.getSource().sendFeedback(Text.literal(" - " + l)));
                    if (list.size() > 25) c.getSource().sendFeedback(Text.literal("... và " + (list.size() - 25) + " loại khác"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("clean").executes(c -> {
                    ENGINE.running = false; ENGINE.cleaning = true;
                    c.getSource().sendFeedback(Text.literal("Đang dọn scaffold (" + ENGINE.scaffoldCount() + ")"));
                    return 1;
                }))
                .then(ClientCommandManager.literal("scaffold")
                    .then(ClientCommandManager.literal("on").executes(c -> { ENGINE.scaffoldEnabled = true; c.getSource().sendFeedback(Text.literal("Scaffold: BẬT")); return 1; }))
                    .then(ClientCommandManager.literal("off").executes(c -> { ENGINE.scaffoldEnabled = false; c.getSource().sendFeedback(Text.literal("Scaffold: TẮT")); return 1; }))
                    .then(ClientCommandManager.literal("item").executes(c -> {
                        var mc = net.minecraft.client.MinecraftClient.getInstance();
                        var st = mc.player.getMainHandStack();
                        if (st.isEmpty() || !(st.getItem() instanceof net.minecraft.item.BlockItem)) { c.getSource().sendError(Text.literal("Cầm một block trên tay trước")); return 0; }
                        ENGINE.scaffoldItem = st.getItem();
                        c.getSource().sendFeedback(Text.literal("Block scaffold: " + st.getName().getString()));
                        return 1;
                    })))
                .then(ClientCommandManager.literal("stop").executes(c -> { ENGINE.running = false; return 1; }))
                .then(ClientCommandManager.literal("speed").then(ClientCommandManager.argument("n", IntegerArgumentType.integer(1, 10)).executes(c -> {
                    ENGINE.blocksPerTick = IntegerArgumentType.getInteger(c, "n"); return 1;
                })))
                .then(ClientCommandManager.literal("reach").then(ClientCommandManager.argument("r", DoubleArgumentType.doubleArg(2, 6)).executes(c -> {
                    ENGINE.reach = DoubleArgumentType.getDouble(c, "r"); return 1;
                })))
                .then(ClientCommandManager.literal("status").executes(c -> {
                    c.getSource().sendFeedback(Text.literal("Đã đặt " + ENGINE.placed() + " lần, tổng " + ENGINE.total() + " block, bỏ qua " + ENGINE.skipped() + ", scaffold đang có " + ENGINE.scaffoldCount() + (ENGINE.running ? " (đang chạy)" : " (dừng)")));
                    return 1;
                }))
        ));
    }
}
