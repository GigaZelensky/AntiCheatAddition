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
 * AutoTool – detects instant smart-tool swaps (Meteor, etc.).
 * Flags both swap-before-hit and swap-after-hit patterns.
 */
public final class AutoTool extends ViolationModule implements Listener {

    public static final AutoTool INSTANCE = new AutoTool();
    private AutoTool() { super("AutoTool"); }

    /* ──────────────────────── records ──────────────────────── */

    private record Swap(long time, int fromSlot, int toSlot,
                        ItemStack fromItem, ItemStack toItem) {}

    private record Click(long time, Material block, int slot,
                         ItemStack heldAtClick) {}

    private record Data(Swap lastSwap, Click lastClick,
                        int streak, long streakStart) {}

    /* ─────────────────────── state map ─────────────────────── */

    private static final Map<User, Data> STATE = new ConcurrentHashMap<>();

    /* ─────────────────────── helpers ─────────────────────── */

    private int cfg(String k, int def) { return loadInt(k, def); }

    /* ─────────────────────── events ─────────────────────── */

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
                (old, n) -> new Data(swap, old.lastClick, old.streak, old.streakStart));
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeftClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_BLOCK || e.getClickedBlock() == null) return;

        User u = User.getUser(e.getPlayer());
        if (User.isUserInvalid(u, this)) return;

        ItemStack held = e.getPlayer().getInventory()
                          .getItem(e.getPlayer().getInventory().getHeldItemSlot());

        Click click = new Click(System.currentTimeMillis(),
                                e.getClickedBlock().getType(),
                                e.getPlayer().getInventory().getHeldItemSlot(),
                                held);

        STATE.merge(u, new Data(null, click, 0, 0),
                (old, n) -> new Data(old.lastSwap, click, old.streak, old.streakStart));

        evaluateBeforeHitPath(u, e.getPlayer(), click);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapAfterClick(PlayerItemHeldEvent e) {
        User u = User.getUser(e.getPlayer());
        if (User.isUserInvalid(u, this)) return;

        Data d = STATE.get(u);
        if (d == null || d.lastClick == null) return;

        long delay = System.currentTimeMillis() - d.lastClick.time();
        if (delay > cfg(".min_switch_delay", 150)) return;

        ItemStack was = d.lastClick.heldAtClick();
        ItemStack now = e.getPlayer().getInventory().getItem(e.getNewSlot());

        evaluateSuspicion(u, e.getPlayer(), d, was, now, delay, d.lastClick.block());
    }

    /* ───────── swap-before-hit evaluation ───────── */

    private void evaluateBeforeHitPath(User u, org.bukkit.entity.Player p, Click click) {
        Data d = STATE.get(u);
        if (d == null || d.lastSwap == null) return;

        long delay = click.time() - d.lastSwap.time();      // swap → click
        if (delay < 0 || delay > cfg(".min_switch_delay", 150)) return;

        evaluateSuspicion(u, p, d,
                d.lastSwap.fromItem(), d.lastSwap.toItem(), delay, click.block());
    }

    /* ───────── core suspicion logic ───────── */

    private void evaluateSuspicion(User u, org.bukkit.entity.Player p, Data d,
                                   ItemStack wrongTool, ItemStack rightTool,
                                   long delay, Material block) {

        if (wrongTool == null || rightTool == null) return;
        if (!isCorrectTool(block, rightTool)) return;
        if (isCorrectTool(block, wrongTool))  return;
        if (!PingProvider.INSTANCE.atMostMaxPing(p, cfg(".max_ping", 400))) return;

        int add = (delay <= 80) ? 35 : 25;

        long now   = System.currentTimeMillis();
        long win   = cfg(".streak_window", 5000);
        int streak = (now - d.streakStart <= win) ? d.streak + 1 : 1;
        long sStart = (streak == 1) ? now : d.streakStart;
        if (streak >= 4) add += 40;

        STATE.put(u, new Data(d.lastSwap, d.lastClick, streak, sStart));

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

    /* ───────── meteor-proof block↔tool matcher ───────── */

    private static boolean isCorrectTool(Material block, ItemStack tool) {
        if (tool == null) return false;

        Material t = tool.getType();
        String  b = block.name();                 // UPPER_SNAKE_CASE

        /* Shears */
        if (t == Material.SHEARS)
            return b.contains("LEAVES") || b.contains("WOOL") || b.equals("COBWEB");

        /* Sword (Meteor quirk) */
        if (t.name().endsWith("_SWORD"))
            return b.equals("BAMBOO") || b.equals("BAMBOO_SHOOT");

        boolean axe    = t.name().endsWith("_AXE");
        boolean pick   = t.name().endsWith("_PICKAXE");
        boolean shovel = t.name().endsWith("_SHOVEL");
        boolean hoe    = t.name().endsWith("_HOE");

        /* Pickaxe */
        if (pick && (
                b.contains("STONE") || b.contains("DEEPSLATE") || b.contains("ORE")
             || b.contains("TERRACOTTA") || b.endsWith("_BLOCK")
             || b.equals("OBSIDIAN") || b.equals("CRYING_OBSIDIAN")
             || b.equals("NETHERRACK") || b.equals("END_STONE")
             || b.equals("RAW_IRON_BLOCK") || b.equals("RAW_GOLD_BLOCK")
             || b.equals("RAW_COPPER_BLOCK") || b.equals("ANCIENT_DEBRIS")
        )) return true;

        /* Axe */
        if (axe && (
                b.contains("WOOD") || b.endsWith("_LOG") || b.contains("PLANKS")
             || b.contains("BAMBOO") || b.contains("CHEST") || b.equals("BARREL")
             || b.contains("BOOKSHELF") || b.equals("LADDER")
             || b.contains("SIGN") || b.contains("CAMPFIRE")
             || b.equals("NOTE_BLOCK") || b.endsWith("_TABLE")
        )) return true;

        /* Shovel */
        if (shovel && (
                b.contains("DIRT") || b.contains("GRAVEL") || b.contains("SAND")
             || b.contains("SNOW") || b.contains("MUD") || b.contains("CLAY")
             || b.equals("GRASS_BLOCK") || b.equals("PODZOL")
             || b.equals("ROOTED_DIRT") || b.endsWith("CONCRETE_POWDER")
             || b.equals("SOUL_SAND") || b.equals("SOUL_SOIL")
        )) return true;

        /* Hoe */
        if (hoe && (
                b.contains("HAY") || b.contains("CROP") || b.contains("WART")
             || b.contains("LEAVES") || b.contains("MOSS") || b.equals("DRIED_KELP_BLOCK")
             || b.equals("TARGET")
        )) return true;

        return false;
    }

    /* ───────── violation management ───────── */

    @Override
    protected ViolationManagement createViolationManagement() {
        return ViolationLevelManagement.builder(this)
                                       .loadThresholdsToManagement()
                                       .withDecay(6000L, 15)
                                       .build();
    }
}
