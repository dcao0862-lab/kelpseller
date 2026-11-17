package com.kelpseller;

// Mod Fabric (client-only) cho Minecraft Java 1.21.11, mappings Yarn.
// Bật/tắt bằng lệnh /kelpseller trong chat. Mod chạy liên tục cho tới khi bạn tắt.
// CHƯA ĐƯỢC BIÊN DỊCH/CHẠY THỬ trong game.

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

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
    static final int CONFIRM_SLOT = 16;               // ô xanh "xác nhận giao hàng"
    static final double REACH = 4.5;                  // bán kính tìm rương quanh người chơi
    static final int TIMEOUT = 200;                   // tick chờ một menu đơn hàng mở (20 tick = 1 giây)
    static final int OPEN_TIMEOUT = 40;               // tick chờ một rương mở (2 giây)
    static final int IDLE_TICKS = 0;                  // nghỉ giữa các vòng quét rương (0 = quét liên tục)
    static final int MAX_FAILS = 10;                  // chỉ tự tắt sau ngần này lỗi liên tiếp
    // =====================

    enum State { START, NEXT_CHEST, OPEN_CHEST, WAIT_CHEST, TAKE, SEND_CMD, WAIT_ORDERS, CLICK_ORDER,
                 WAIT_DELIVER, DEPOSIT, CLOSE_DEPOSIT, WAIT_CONFIRM, CLICK_CONFIRM, AFTER_CONFIRM }

    private final Random rng = new Random();
    private final Deque<BlockPos> chests = new ArrayDeque<>();
    private final Set<BlockPos> visited = new HashSet<>();
    private final Set<BlockPos> hiddenLogged = new HashSet<>();
    private boolean running = false;
    private boolean checkLeftover = false;
    private boolean idleLogged = false;
    private State state = State.START;
    private int wait = 0, timeout = 0, lastSyncId = -1, curSyncId = -1;
    private int fails = 0, leftoverRounds = 0, scanned = 0, openedThisRound = 0;
    private BlockHitResult pendingHit;
    private KeyBinding toggleKey;
    private double rx = 1, rz = 0; // hướng "bên phải" của bạn lúc bật mod (cố định, làm tròn theo 4 hướng)

    @Override
    public void onInitializeClient() {
        // Phím bật/tắt: mặc định K, đổi được trong Cài đặt > Điều khiển
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("kelpseller", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.kelpseller.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        // Hoặc gõ /kelpseller trong chat để bật/tắt
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
        fails = 0;
        leftoverRounds = 0;
        checkLeftover = false;
        idleLogged = false;
        hiddenLogged.clear();
        if (running && mc.player != null) {
            // Khóa hướng nhìn lúc bật: "phải" và "trái" tính theo hướng này, không đổi khi nhân vật quay đầu
            int snapped = Math.round(mc.player.getYaw() / 90.0f) * 90;
            double rad = Math.toRadians(snapped);
            rx = -Math.cos(rad);
            rz = -Math.sin(rad);
            if (Math.abs(rx) < 1e-6) rx = 0;
            if (Math.abs(rz) < 1e-6) rz = 0;
        }
        log(mc, running ? "BẬT: kiểm tra rương từ trên xuống dưới, từ phải sang trái (theo hướng bạn đang nhìn). Tắt bằng phím K hoặc lệnh /kelpseller" : "TẮT");
    }

    private void stop(MinecraftClient mc, String why) {
        running = false;
        if (mc.player != null && menuOpen(mc)) mc.player.closeHandledScreen();
        log(mc, "DỪNG: " + why);
    }

    /** Lỗi tạm thời: đóng menu, đợi 3 giây rồi làm lại từ đầu. Chỉ tắt khi lỗi liên tiếp quá nhiều. */
    private void fail(MinecraftClient mc, String why) {
        if (mc.player != null && menuOpen(mc)) mc.player.closeHandledScreen();
        fails++;
        if (fails >= MAX_FAILS) { stop(mc, why + " (lỗi liên tiếp " + fails + " lần)"); return; }
        log(mc, "Lỗi: " + why + ", thử lại (" + fails + "/" + MAX_FAILS + ")");
        wait = 60;
        state = State.START;
    }

    /** Không có việc gì: quét rương lại ngay (hoặc sau minTicks), KHÔNG tắt. */
    private void idle(MinecraftClient mc, String why, int minTicks) {
        if (!idleLogged) { log(mc, why); idleLogged = true; }
        wait = Math.max(IDLE_TICKS, minTicks);
        state = State.START;
    }

    private void log(MinecraftClient mc, String msg) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("[KelpSeller] " + msg), false);
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

    /** Có menu dạng rương/GUI đang mở không. KHÔNG tính khung chat hay menu tạm dừng, để bạn luôn gõ lệnh được. */
    private static boolean menuOpen(MinecraftClient mc) {
        return mc.currentScreen instanceof HandledScreen<?>;
    }

    /** Màn hình đang mở có phải rương/thùng thật không (tiêu đề "Large Chest", "Chest", "Barrel"). */
    private static boolean isStorage(MinecraftClient mc) {
        if (mc.currentScreen == null) return false;
        String t = mc.currentScreen.getTitle().getString().toLowerCase();
        return t.contains("chest") || t.contains("barrel") || t.contains("rương") || t.contains("thùng");
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
        // Thứ tự: hàng trên cùng trước; trong mỗi hàng đi từ phải sang trái; hòa thì rương gần hơn trước
        found.sort(Comparator
                .comparingInt((BlockPos b) -> -b.getY())
                .thenComparingDouble(b -> -((b.getX() + 0.5) * rx + (b.getZ() + 0.5) * rz))
                .thenComparingDouble(b -> Vec3d.ofCenter(b).squaredDistanceTo(eye)));
        chests.addAll(found);
        scanned = found.size();
    }

    /**
     * Tìm một điểm trên rương mà tia nhìn thật từ mắt bạn chạm đúng rương đó (không bị rương/khối khác che).
     * Trả về chính cú nhấp phải mà người chơi thật sẽ tạo ra (đúng mặt, đúng vị trí). null nếu không có điểm nào.
     */
    private BlockHitResult findVisibleHit(MinecraftClient mc, ClientPlayerEntity p, BlockPos pos, BlockPos partner) {
        Vec3d eye = p.getEyePos();
        double[][] offs = {
                {0.5, 0.45, 0.5}, {0.5, 0.8, 0.5}, {0.5, 0.15, 0.5},
                {0.25, 0.45, 0.5}, {0.75, 0.45, 0.5}, {0.5, 0.45, 0.25}, {0.5, 0.45, 0.75},
                {0.2, 0.8, 0.2}, {0.8, 0.8, 0.8}, {0.2, 0.15, 0.8}, {0.8, 0.15, 0.2}
        };
        for (double[] o : offs) {
            Vec3d pt = new Vec3d(pos.getX() + o[0], pos.getY() + o[1], pos.getZ() + o[2]);
            Vec3d dir = pt.subtract(eye);
            if (dir.length() < 0.01) continue;
            Vec3d end = eye.add(dir.normalize().multiply(REACH + 1.0));
            BlockHitResult r = mc.world.raycast(new RaycastContext(
                    eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
            if (r.getType() != HitResult.Type.BLOCK) continue;
            BlockPos hp = r.getBlockPos();
            if ((hp.equals(pos) || (partner != null && hp.equals(partner))) && eye.distanceTo(r.getPos()) <= REACH) {
                return r;
            }
        }
        return null;
    }

    /** Quay đầu nhìn vào điểm đó như người chơi thật. */
    private void aimAt(ClientPlayerEntity p, Vec3d point) {
        Vec3d d = point.subtract(p.getEyePos());
        double flat = Math.sqrt(d.x * d.x + d.z * d.z);
        p.setYaw((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        p.setPitch((float) -Math.toDegrees(Math.atan2(d.y, flat)));
    }

    private void tick(MinecraftClient mc) {
        if (toggleKey.wasPressed()) {
            while (toggleKey.wasPressed()) { /* bỏ các lần bấm dồn */ }
            toggle(mc);
        }
        if (!running || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        // Đang gõ chat: mod tạm dừng hẳn để bạn gõ lệnh /kelpseller
        if (mc.currentScreen instanceof ChatScreen) return;
        // Bấm ESC/Back mở menu tạm dừng: dừng mod luôn
        if (mc.currentScreen instanceof GameMenuScreen) { stop(mc, "bạn mở menu tạm dừng"); return; }
        if (wait > 0) { wait--; return; }

        ClientPlayerEntity p = mc.player;
        GenericContainerScreenHandler h = (mc.currentScreen instanceof GenericContainerScreen s) ? s.getScreenHandler() : null;

        switch (state) {
            case START -> {
                // Luôn đóng mọi menu còn sót (ví dụ menu đơn hàng server tự mở lại) trước khi quét rương
                if (menuOpen(mc)) { p.closeHandledScreen(); wait = 5; return; }
                if (checkLeftover) {
                    checkLeftover = false;
                    if (hasKelp(p)) {
                        leftoverRounds++;
                        if (leftoverRounds >= 3) { stop(mc, "kelp vẫn còn trong túi sau 3 lần giao (đơn có thể đã đầy)"); return; }
                        log(mc, "Còn kelp trong túi, giao lại");
                        state = State.SEND_CMD;
                        return;
                    }
                    leftoverRounds = 0;
                }
                openedThisRound = 0;
                scanChests(mc);
                state = State.NEXT_CHEST;
            }
            case NEXT_CHEST -> {
                if (menuOpen(mc)) { p.closeHandledScreen(); wait = 5; return; }
                boolean full = inventoryFull(p);
                if (full || chests.isEmpty()) {
                    if (hasKelp(p)) { state = State.SEND_CMD; return; }
                    if (full) { idle(mc, "Túi đầy nhưng không có kelp, hãy dọn túi", 40); return; }
                    if (scanned == 0) { idle(mc, "Không thấy rương nào trong " + REACH + " ô quanh bạn, hãy đứng gần rương hơn", 40); return; }
                    if (openedThisRound == 0) { idle(mc, "Không mở được rương nào (xa quá hoặc bị che), hãy đứng gần hơn", 40); return; }
                    idle(mc, "Rương chưa có kelp, đang đợi farm bổ sung", 0);
                    return;
                }
                BlockPos pos = chests.poll();
                if (visited.contains(pos)) return;
                BlockState st = mc.world.getBlockState(pos);
                visited.add(pos);
                BlockPos partner = null;
                // rương đôi: đánh dấu luôn nửa còn lại để không mở lại
                if (st.getBlock() instanceof ChestBlock && st.contains(ChestBlock.CHEST_TYPE)
                        && st.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
                    partner = pos.offset(ChestBlock.getFacing(st));
                    visited.add(partner);
                }
                BlockHitResult hit = findVisibleHit(mc, p, pos, partner);
                if (hit == null) {
                    if (hiddenLogged.add(pos))
                        log(mc, "Rương (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") bị che hoặc quá xa, bỏ qua");
                    return;
                }
                pendingHit = hit;
                aimAt(p, hit.getPos());
                lastSyncId = p.currentScreenHandler.syncId;
                wait = 3; // đợi gói quay đầu được gửi lên server
                state = State.OPEN_CHEST;
            }
            case OPEN_CHEST -> {
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, pendingHit);
                timeout = OPEN_TIMEOUT;
                state = State.WAIT_CHEST;
            }
            case WAIT_CHEST -> {
                if (h != null) {
                    if (h.syncId == lastSyncId || !isStorage(mc)) { // không phải rương, đóng lại để khỏi bấm nhầm
                        p.closeHandledScreen();
                        wait = 5;
                        state = State.NEXT_CHEST;
                        return;
                    }
                    curSyncId = h.syncId;
                    openedThisRound++;
                    wait = delay();
                    state = State.TAKE;
                } else if (--timeout <= 0) state = State.NEXT_CHEST; // rương không mở được, qua rương khác
            }
            case TAKE -> {
                if (h == null || h.syncId != curSyncId || !isStorage(mc)) { state = State.NEXT_CHEST; return; }
                int slot = findKelp(h, true);
                if (slot < 0 || inventoryFull(p)) {
                    p.closeHandledScreen();
                    wait = delay();
                    state = State.NEXT_CHEST;
                    return;
                }
                idleLogged = false;
                mc.interactionManager.clickSlot(h.syncId, slot, 0, SlotActionType.QUICK_MOVE, p);
                wait = delay();
            }
            case SEND_CMD -> {
                if (menuOpen(mc)) { p.closeHandledScreen(); wait = 5; return; }
                lastSyncId = p.currentScreenHandler.syncId;
                p.networkHandler.sendChatCommand(ORDER_COMMAND);
                timeout = TIMEOUT;
                state = State.WAIT_ORDERS;
            }
            case WAIT_ORDERS -> {
                if (h != null && h.syncId != lastSyncId) { curSyncId = h.syncId; wait = 10; state = State.CLICK_ORDER; }
                else if (--timeout <= 0) fail(mc, "không mở được menu đơn hàng");
            }
            case CLICK_ORDER -> {
                if (h == null || h.syncId != curSyncId) { fail(mc, "menu đơn hàng bị đóng hoặc làm mới"); return; }
                lastSyncId = h.syncId;
                mc.interactionManager.clickSlot(h.syncId, ORDER_SLOT, 0, SlotActionType.PICKUP, p);
                timeout = TIMEOUT;
                state = State.WAIT_DELIVER;
            }
            case WAIT_DELIVER -> {
                if (h != null && h.syncId != lastSyncId) { curSyncId = h.syncId; wait = 10; state = State.DEPOSIT; }
                else if (--timeout <= 0) fail(mc, "không mở được khung giao hàng");
            }
            case DEPOSIT -> {
                if (h == null || h.syncId != curSyncId) { fail(mc, "khung giao hàng bị đóng"); return; }
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
                else if (--timeout <= 0) fail(mc, "không thấy khung xác nhận giao hàng");
            }
            case CLICK_CONFIRM -> {
                if (h == null || h.syncId != curSyncId) { fail(mc, "khung xác nhận bị đóng"); return; }
                mc.interactionManager.clickSlot(h.syncId, CONFIRM_SLOT, 0, SlotActionType.PICKUP, p);
                fails = 0;
                idleLogged = false;
                wait = 10; // cho server xử lý xác nhận
                state = State.AFTER_CONFIRM;
            }
            case AFTER_CONFIRM -> {
                // đóng mọi menu còn mở sau khi bán (kể cả menu server tự mở lại), rồi kiểm tra kelp còn dư
                if (menuOpen(mc)) p.closeHandledScreen();
                wait = 20;
                checkLeftover = true;
                state = State.START;
            }
        }
    }
}
