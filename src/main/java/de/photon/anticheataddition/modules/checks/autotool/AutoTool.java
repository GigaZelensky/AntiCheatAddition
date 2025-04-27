package de.photon.anticheataddition.modules.checks.autotool;

import de.photon.anticheataddition.modules.ViolationModule;
import de.photon.anticheataddition.user.User;
import de.photon.anticheataddition.user.data.TimeKey;
import de.photon.anticheataddition.util.inventory.InventoryUtil;
import de.photon.anticheataddition.util.minecraft.ping.PingProvider;
import de.photon.anticheataddition.util.violationlevels.Flag;
import de.photon.anticheataddition.util.violationlevels.ViolationLevelManagement;
import de.photon.anticheataddition.util.violationlevels.ViolationManagement;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoTool check – detects instant smart-tool swaps (Meteor etc.).
 */
public final class AutoTool extends ViolationModule implements Listener {

    public static final AutoTool INSTANCE = new AutoTool();
    private AutoTool() { super("AutoTool"); }

    /* ───────────────────────────────────────────────────────────── */

    private record Swap(long time, int fromSlot, int toSlot,
                        ItemStack fromItem, ItemStack toItem) {}
    private record Click(long time, Material block, int slot)    {}
    private record Data(Swap lastSwap, Click lastClick,
                        int streak, long streakStart) {}

    /** per-user runtime data */
    private static final Map<User, Data> STATE = new ConcurrentHashMap<>();

    /* ───────────────────── config helper ───────────────────── */

    private int cfg(String k, int def) { return loadInt(k, def); }

    /* ───────────────────── events ───────────────────── */

    @EventHandler(ignoreCancelled = true)
    public void onHotbarSwap(PlayerItemHeldEvent e) {
        User u = User.getUser(e.getPlayer());
        if (User.isUserInvalid(u, this)) return;

        Swap swap = new Swap(
                System.currentTimeMillis(),
                e.getPreviousSlot(), e.getNewSlot(),
                e.getPlayer().getInventory().getItem(e.getPreviousSlot()),
                e.getPlayer().getInventory().getItem(e.getNewSlot()));

        STATE.merge(u, new Data(swap, null, 0, 0),
                    (oldD, n) -> new Data(swap, oldD.lastClick, oldD.streak, oldD.streakStart));
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock()==null) return;

        User u = User.getUser(e.getPlayer());
        if (User.isUserInvalid(u, this)) return;

        Click click = new Click(System.currentTimeMillis(),
                                e.getClickedBlock().getType(),
                                e.getPlayer().getInventory().getHeldItemSlot());

        STATE.merge(u, new Data(null, click, 0, 0),
                    (oldD, n) -> new Data(oldD.lastSwap, click, oldD.streak, oldD.streakStart));

        evaluateAfterClick(e.getPlayer(), u, click);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDelayedSwap(PlayerItemHeldEvent e) {
        /* after-click path */
        User u = User.getUser(e.getPlayer());
        if (User.isUserInvalid(u, this)) return;

        Data d = STATE.get(u);
        if (d == null || d.lastClick==null) return;

        long delay = System.currentTimeMillis() - d.lastClick.time();
        if (delay > cfg(".min_switch_delay", 150)) return;

        ItemStack was = e.getPlayer().getInventory().getItem(d.lastClick.slot());
        ItemStack now = e.getPlayer().getInventory().getItem(e.getNewSlot());

        handleSuspicion(u, e.getPlayer(), d, was, now, delay, d.lastClick.block());
    }

    /* ───────────── evaluate before-click path ───────────── */

    private void evaluateAfterClick(org.bukkit.entity.Player p, User u, Click click) {
        Data d = STATE.get(u);
        if (d==null || d.lastSwap==null) return;

        long delay = click.time() - d.lastSwap.time();
        if (delay < 0 || delay > cfg(".min_switch_delay", 150)) return;

        ItemStack was = d.lastSwap.fromItem();
        ItemStack now = d.lastSwap.toItem();

        handleSuspicion(u, p, d, was, now, delay, click.block());
    }

    /* ───────────── common suspicion handler ───────────── */

    private void handleSuspicion(User u, org.bukkit.entity.Player p, Data d,
                                 ItemStack wrongTool, ItemStack rightTool,
                                 long delay, Material block) {

        if (wrongTool==null || rightTool==null) return;
        if (!isCorrectTool(block, rightTool)) return;
        if (isCorrectTool(block, wrongTool))  return;
        if (!PingProvider.INSTANCE.atMostMaxPing(p, cfg(".max_ping",400))) return;

        int add = (delay <= 80) ? 35 : 25;

        long now = System.currentTimeMillis();
        long win = cfg(".streak_window", 5000);
        int streak = (now - d.streakStart <= win) ? d.streak + 1 : 1;
        long streakStart = (streak==1) ? now : d.streakStart;

        if (streak >= 4) add += 40;

        STATE.put(u, new Data(d.lastSwap, d.lastClick, streak, streakStart));

        int cancelVl = cfg(".cancel_vl", 60);

        getManagement().flag(
            Flag.of(u)
                .setAddedVl(add)
                .setCancelAction(cancelVl, () -> {
                    InventoryUtil.syncUpdateInventory(p);
                    u.getTimeMap().at(TimeKey.AUTOTOOL_TIMEOUT).update();
                })
        );
    }

    /* ───────────── util ───────────── */

    private static boolean isCorrectTool(Material block, ItemStack tool) {
        if (tool==null) return false;

        Material t = tool.getType();
        String b = block.name();

        if (t==Material.SHEARS)
            return b.contains("WOOL")||b.contains("LEAVES")||b.equals("COBWEB");

        boolean axe=t.name().endsWith("_AXE");
        boolean pick=t.name().endsWith("_PICKAXE");
        boolean shovel=t.name().endsWith("_SHOVEL");
        boolean hoe=t.name().endsWith("_HOE");

        if (axe && (b.endsWith("_LOG")||b.contains("WOOD")||b.contains("BAMBOO"))) return true;
        if (pick&& (b.contains("STONE")||b.contains("ORE")||b.equals("ANCIENT_DEBRIS"))) return true;
        if (shovel&&(b.contains("DIRT") ||b.contains("SAND")||b.contains("GRAVEL")||b.contains("SNOW"))) return true;
        return hoe && (b.contains("HAY") ||b.contains("CROP")||b.contains("WART"));
    }

    /* ─────────── violation mgmt ─────────── */

    @Override protected ViolationManagement createViolationManagement() {
        return ViolationLevelManagement.builder(this)
                                       .loadThresholdsToManagement()
                                       .withDecay(6000L,15)
                                       .build();
    }
}