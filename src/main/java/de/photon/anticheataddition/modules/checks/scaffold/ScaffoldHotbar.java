package de.photon.anticheataddition.modules.checks.scaffold;

import de.photon.anticheataddition.modules.Module;
import de.photon.anticheataddition.user.User;
import de.photon.anticheataddition.user.data.TimeKey;
import de.photon.anticheataddition.util.inventory.InventoryUtil;
import de.photon.anticheataddition.util.minecraft.ping.PingProvider;
import de.photon.anticheataddition.util.violationlevels.Flag;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ScaffoldHotbar – flags block‑placements that happen suspiciously soon after a hot‑bar swap
 * (≤ <i>min_switch_delay</i> ms). Inspired by AutoTool but scoped to building blocks.
 */
public final class ScaffoldHotbar extends Module implements Listener {

    public static final ScaffoldHotbar INSTANCE = new ScaffoldHotbar();
    private ScaffoldHotbar() { super("Scaffold.parts.Hotbar"); }

    /* per‑player swap state */
    private static final class Swap { final long time; final int slot; Swap(long t,int s){time=t;slot=s;} }
    private static final Map<User, Swap> LAST_SWAP = new ConcurrentHashMap<>();

    /* config helpers – values live under Scaffold.parts.Hotbar in config.yml */
    private int cfg(String key,int def){return loadInt(key,def);} // inherited helper

    @EventHandler(ignoreCancelled = true)
    public void onHotbarSwap(PlayerItemHeldEvent e){
        Player p = e.getPlayer();
        ItemStack newItem = p.getInventory().getItem(e.getNewSlot());
        if (newItem == null || newItem.getType()==Material.AIR || !newItem.getType().isBlock()) return; // only blocks
        LAST_SWAP.put(User.getUser(p), new Swap(System.currentTimeMillis(), e.getNewSlot()));
    }

    @EventHandler(ignoreCancelled = true, priority = org.bukkit.event.EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent e){
        User u = User.getUser(e.getPlayer());
        if (User.isUserInvalid(u, this)) return; // dead, creative, etc.

        Swap s = LAST_SWAP.get(u);
        if (s == null) return;
        long delay = System.currentTimeMillis() - s.time;
        if (delay < 0 || delay > cfg(".min_switch_delay",150)) return;
        if (!PingProvider.INSTANCE.atMostMaxPing(e.getPlayer(), cfg(".max_ping",400))) return;

        int addVl = (delay <= 80) ? 20 : 10;
        int streakWin = cfg(".streak_window", 5000);
        int cancelVl = cfg(".cancel_vl", 110);

        /* simple streak handling: store in User.TimeMap */
        long now = System.currentTimeMillis();
        long lastFlag = u.getTimeMap().at(TimeKey.SCAFFOLD_HOTBAR_FLAG).passedTime();
        if (lastFlag <= streakWin) addVl += 30; // second (or more) rapid swap → bonus
        u.getTimeMap().at(TimeKey.SCAFFOLD_HOTBAR_FLAG).update();

        Scaffold.INSTANCE.getManagement().flag(
                Flag.of(u)
                    .setAddedVl(addVl)
                    .setCancelAction(cancelVl, ()->{
                        InventoryUtil.syncUpdateInventory(e.getPlayer());
                        u.getTimeMap().at(TimeKey.SCAFFOLD_TIMEOUT).update();
                    }));
    }
}