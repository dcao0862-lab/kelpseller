package com.kelpseller;

// Mod Fabric (client-only) cho Minecraft Java 1.21.11, mappings Yarn.
// Bật/tắt bằng lệnh /kelpseller trong chat.
// CHƯA ĐƯỢC BIÊN DỊCH/CHẠY THỬ.

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class KelpSellerClient implements ClientModInitializer {

    // ===== CẤU HÌNH =====
    static final String ORDER_COMMAND = "order kelp"; // lệnh mở menu đơn hàng (không có dấu /)
    static final int ORDER_SLOT = 0;                  // ô đơn hàng đầu tiên (giá cao nhất)
    static final int CONFIRM_SLOT = 16;               // ô xanh "xác nhận giao hàng" (hàng 2, cột 8 của khung 3 hàng)
    static final double REACH = 4.0;                  // bán kính tìm rương quanh người chơi
    static final int TIMEOUT = 80;                    // tick chờ menu mở (20 tick = 1 giây)
    // =====================

    enum State { START, NEXT_CHEST, WAIT_CHEST, TAKE, SEND_CMD, WAIT_ORDERS, CLICK_ORDER, WAIT_DELIVER, DEPOSIT, CLOSE_DEPOSIT, WAIT_CONFIRM, CLICK_CONFIRM }

    private final Random rng = new Random();
    private final Deque<BlockPos> chests = new ArrayDeque<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private boolean running = false;
    private boolean checkLeftover = false;
    private State state = State.START;
    private int wait = 0, timeout = 0, lastSyncId = -1, curSyncId = -1;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // Gõ /kelpseller trong chat để bật/tắt
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("kelpseller").executes(ctx -> {
                    toggle(MinecraftClient.getInstance());
                    return 1;
                })));
    }

    private void toggle(MinecraftClient mc) {
        running = !running;
        state = State.START;
        wait = 0;
        checkLeftover = false;
        say(mc, running ? "KelpSeller: BẬT" : "KelpSeller: TẮT");
    }

    private void stop(MinecraftClient mc, String why) {
        running = false;
        if (mc.player != null && mc.currentScreen != null) mc.player.closeHandledScreen();
        say(mc, "KelpSeller dừng: " + why);
    }

    private void say(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), true);
    }

    private int delay() { return 3 + rng.nextInt(4); } // 3-6 tick giữa các thao tác

    private static boolean isKelp(ItemStack s) { return s.isOf(Items.DRIED_KELP_BLOCK); }

    private static boolean hasKelp(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) if (isKelp(inv.getStack(i))) return true;
        return false;
    }

    private static boolean inventoryFull(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || (isKelp(s) && s.getCount() < s.getMaxCount())) return false;
        }
        return true;
    }

    /** Tìm ô chứa kelp: inChest=true tìm trong phần rương, false tìm trong túi đồ người chơi. */
    private static int findKelp(GenericContainerScreenHandler h, boolean inChest) {
        int top = h.getRows() * 9;
        int from = inChest ? 0 : top;
        int to = inChest ? top : h.slots.size();
        for (int i = from; i < to; i++) if (isKelp(h.getSlot(i).getStack())) return i;
        return -1;
    }

    private void scanChests(MinecraftClient mc) {
        chests.clear();
        visited.clear();
        Vec3d eye = mc.player.getEyePos();
        BlockPos c = mc.player.getBlockPos();
        int r = (int) Math.ceil(REACH) + 1;
        List<BlockPos> found = new ArrayList<>();
        for (int x = -r; x <= r; x++)
            for (int y = -r; y <= r; y++)
                for (int z = -r; z <= r; z++) {
                    BlockPos p = c.add(x, y, z);
                    if (Vec3d.ofCenter(p).squaredDistanceTo(eye) > REACH * REACH) continue;
                    BlockState st = mc.world.getBlockState(p);
                    if (st.isOf(Blocks.CHEST) || st.isOf(Blocks.TRAPPED_CHEST) || st.isOf(Blocks.BARREL))
                        found.add(p.toImmutable());
                }
        found.sort(Comparator.comparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(eye)));
        chests.addAll(found);
    }

    private void tick(MinecraftClient mc) {
        if (!running || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (wait > 0) { wait--; return; }

        ClientPlayerEntity p = mc.player;
        GenericContainerScreenHandler h = (mc.currentScreen instanceof GenericContainerScreen s) ? s.getScreenHandler() : null;

        switch (state) {
            case START -> {
                if (checkLeftover) {
                    checkLeftover = false;
                    if (hasKelp(p)) { stop(mc, "kelp vẫn còn trong túi (đơn có thể đã đầy)"); return; }
                }
                scanChests(mc);
                state = State.NEXT_CHEST;
            }
            case NEXT_CHEST -> {
                if (inventoryFull(p) || chests.isEmpty()) {
                    if (hasKelp(p)) state = State.SEND_CMD;
                    else stop(mc, "không còn kelp trong rương");
                    return;
                }
                BlockPos pos = chests.poll();
                if (visited.contains(pos)) return;
                BlockState st = mc.world.getBlockState(pos);
                visited.add(pos);
                // rương đôi: đánh dấu luôn nửa còn lại để không mở lại
                if (st.getBlock() instanceof ChestBlock && st.contains(ChestBlock.CHEST_TYPE)
                        && st.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                    visited.add(pos.offset(ChestBlock.getFacing(st)));
                }
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                timeout = TIMEOUT;
                state = State.WAIT_CHEST;
            }
            case WAIT_CHEST -> {
                if (h != null) { curSyncId = h.syncId; wait = delay(); state = State.TAKE; }
                else if (--timeout <= 0) state = State.NEXT_CHEST; // rương không mở được, qua rương khác
            }
            case TAKE -> {
                if (h == null || h.syncId != curSyncId) { state = State.NEXT_CHEST; return; }
                int slot = findKelp(h, true);
                if (slot < 0 || inventoryFull(p)) {
                    p.closeHandledScreen();
                    wait = delay();
                    state = State.NEXT_CHEST;
                    return;
                }
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, p);
                wait = delay();
            }
            case SEND_CMD -> {
                if (mc.currentScreen != null) { p.closeHandledScreen(); wait = 5; return; }
                lastSyncId = p.currentScreenHandler.syncId;
                p.networkHandler.sendChatCommand(ORDER_COMMAND);
                timeout = TIMEOUT;
                state = State.WAIT_ORDERS;
            }
            case WAIT_ORDERS -> {
                if (h != null && h.syncId != lastSyncId) { curSyncId = h.syncId; wait = 10; state = State.CLICK_ORDER; }
                else if (--timeout <= 0) stop(mc, "không mở được menu đơn hàng");
            }
            case CLICK_ORDER -> {
                if (h == null || h.syncId != curSyncId) { stop(mc, "menu đơn hàng bị đóng"); return; }
                lastSyncId = h.syncId;
                mc.interactionManager.clickSlot(h.syncId, ORDER_SLOT, 0, SlotActionType.PICKUP, p);
                timeout = TIMEOUT;
                state = State.WAIT_DELIVER;
            }
            case WAIT_DELIVER -> {
                if (h != null && h.syncId != lastSyncId) { curSyncId = h.syncId; wait = 10; state = State.DEPOSIT; }
                else if (--timeout <= 0) stop(mc, "không mở được khung giao hàng");
            }
            case DEPOSIT -> {
                if (h == null || h.syncId != curSyncId) { stop(mc, "khung giao hàng bị đóng"); return; }
                int slot = findKelp(h, false);
                if (slot < 0) { wait = 10; state = State.CLOSE_DEPOSIT; return; }
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, p);
                wait = delay();
            }
            case CLOSE_DEPOSIT -> {
                // đóng khung bỏ đồ, server sẽ mở khung "xác nhận giao hàng"
                lastSyncId = (h != null) ? h.syncId : curSyncId;
                p.closeHandledScreen();
                timeout = TIMEOUT;
                state = State.WAIT_CONFIRM;
            }
            case WAIT_CONFIRM -> {
                if (h != null && h.syncId != lastSyncId) { curSyncId = h.syncId; wait = 10; state = State.CLICK_CONFIRM; }
                else if (--timeout <= 0) stop(mc, "không thấy khung xác nhận giao hàng");
            }
            case CLICK_CONFIRM -> {
                if (h == null || h.syncId != curSyncId) { stop(mc, "khung xác nhận bị đóng"); return; }
                mc.interactionManager.clickSlot(h.syncId, CONFIRM_SLOT, 0, SlotActionType.PICKUP, p);
                wait = 30; // đợi server trả tiền rồi mới kiểm tra
                if (mc.currentScreen != null) p.closeHandledScreen();
                checkLeftover = true;
                state = State.START;
            }
        }
    }
}
