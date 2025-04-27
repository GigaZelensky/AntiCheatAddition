package de.photon.anticheataddition.modules.checks.scaffold;

import de.photon.anticheataddition.modules.ModuleLoader;
import de.photon.anticheataddition.modules.ViolationModule;
import de.photon.anticheataddition.user.User;
import de.photon.anticheataddition.user.data.TimeKey;
import de.photon.anticheataddition.user.data.batch.ScaffoldBatch;
import de.photon.anticheataddition.util.inventory.InventoryUtil;
import de.photon.anticheataddition.util.log.Log;
import de.photon.anticheataddition.util.minecraft.ping.PingProvider;            // ▼ NEW
import de.photon.anticheataddition.util.minecraft.world.WorldUtil;
import de.photon.anticheataddition.util.minecraft.world.material.MaterialUtil;
import de.photon.anticheataddition.util.violationlevels.Flag;
import de.photon.anticheataddition.util.violationlevels.ViolationLevelManagement;
import de.photon.anticheataddition.util.violationlevels.ViolationManagement;
import lombok.Getter;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;                              // ▼ NEW

import java.util.Map;                                                           // ▼ NEW
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;                                  // ▼ NEW

@Getter
public final class Scaffold extends ViolationModule implements Listener
{
    public static final Scaffold INSTANCE = new Scaffold();

    private final int cancelVl       = loadInt(".cancel_vl", 110);
    private final int timeout        = loadInt(".timeout", 1000);
    private final int placementDelay = loadInt(".placement_delay", 238);

    /* ───────── Hot-bar-switch sub-check (similar to AutoTool) ───────── */

    private final int hotbarMinSwitchDelay = loadInt(".parts.Hotbar.min_switch_delay", 150);
    private final int hotbarStreakWindow   = loadInt(".parts.Hotbar.streak_window",    5000);
    private final int hotbarMaxPing        = loadInt(".parts.Hotbar.max_ping",         400);

    private record Swap(long time, int fromSlot, int toSlot) {}
    private record SwapData(Swap lastSwap, int streak, long streakStart) {}
    private static final Map<User, SwapData> SWAP_STATE = new ConcurrentHashMap<>();

    /* ─────────────────────────────────────────────────────────────────── */

    private Scaffold()
    {
        super("Scaffold", ScaffoldAngle.INSTANCE,
              ScaffoldFace.INSTANCE,
              ScaffoldJumping.INSTANCE,
              ScaffoldPosition.INSTANCE,
              ScaffoldRotation.INSTANCE,
              ScaffoldSafewalkEdge.INSTANCE,
              ScaffoldSafewalkTiming.INSTANCE,
              ScaffoldSprinting.INSTANCE);
    }

    // --------------------------------- Hot-bar swap listener --------------------------------- //

    @EventHandler(ignoreCancelled = true)
    public void onHotbarSwap(PlayerItemHeldEvent event)
    {
        final var user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;

        long now = System.currentTimeMillis();
        var old = SWAP_STATE.get(user);
        SWAP_STATE.put(user, new SwapData(new Swap(now,
                                                   event.getPreviousSlot(),
                                                   event.getNewSlot()),
                                          old == null ? 0 : old.streak,
                                          old == null ? now : old.streakStart));
    }

    // ------------------------------------------- BlockPlace Handling ---------------------------------------------- //

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreBlockPlace(final BlockPlaceEvent event)
    {
        final var user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;

        // To prevent too fast scaffolding -> Timeout
        if (user.getTimeMap().at(TimeKey.SCAFFOLD_TIMEOUT).recentlyUpdated(timeout)) {
            event.setCancelled(true);
            InventoryUtil.syncUpdateInventory(user.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event)
    {
        final var user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;

        int hotbarVl = getHotbarVl(user);                                       // ▼ NEW

        final var blockPlaced = event.getBlockPlaced();
        final var face = event.getBlock().getFace(event.getBlockAgainst());

        Log.finer(() -> "Scaffold-Debug | Player: %s placed block: %s against: %s on face: %s".formatted(user.getPlayer().getName(), blockPlaced.getType(), event.getBlockAgainst().getType(), face));
        Log.finer(() -> "Scaffold-Debug | Assumptions | Dist: %b, Fly: %b, Y: %b, Solid: %b, L/V: %b, Around: %d, Horizontal: %d"
                .formatted(WorldUtil.INSTANCE.areLocationsInRange(user.getPlayer().getLocation(), blockPlaced.getLocation(), 4D),
                           !user.getPlayer().isFlying(),
                           user.getPlayer().getLocation().getY() > blockPlaced.getY(),
                           blockPlaced.getType().isSolid(),
                           event.getBlockPlaced().getType() != Material.LADDER && event.getBlockPlaced().getType() != Material.VINE,
                           WorldUtil.INSTANCE.countBlocksAround(blockPlaced, WorldUtil.ALL_FACES, MaterialUtil.INSTANCE.getLiquids()),
                           WorldUtil.INSTANCE.countBlocksAround(blockPlaced, WorldUtil.HORIZONTAL_FACES, MaterialUtil.INSTANCE.getLiquids())));

        // Short distance between player and the block (at most 4 Blocks)
        if (WorldUtil.INSTANCE.areLocationsInRange(user.getPlayer().getLocation(), blockPlaced.getLocation(), 4D) &&
            // Not flying
            !user.getPlayer().isFlying() &&
            // Above the block
            user.getPlayer().getLocation().getY() > blockPlaced.getY() &&
            // Check if this check applies to the block
            blockPlaced.getType().isSolid() &&
            // Ladders and Vines are prone to false positives as they can be used to place blocks immediately after placing
            // them, therefore almost doubling the placement speed. However, they can only be placed one at a time, which
            // allows simply ignoring them.
            event.getBlockPlaced().getType() != Material.LADDER && event.getBlockPlaced().getType() != Material.VINE &&
            // Check if the block is placed against one block face only and that is horizontal.
            // Only one block that is not a liquid is allowed (the one which the Block is placed against).
            WorldUtil.INSTANCE.countBlocksAround(blockPlaced, WorldUtil.ALL_FACES, MaterialUtil.INSTANCE.getLiquids()) == 1L &&
            WorldUtil.INSTANCE.countBlocksAround(blockPlaced, WorldUtil.HORIZONTAL_FACES, MaterialUtil.INSTANCE.getLiquids()) == 1L) {

            int vl = ScaffoldFace.INSTANCE.getVl(user, event);

            // In between check to make sure it is somewhat a scaffold movement as the buffering does not work.
            // Check that the player is not placing blocks up / down as that is not scaffolding.
            if (WorldUtil.HORIZONTAL_FACES.contains(face)) vl += handleHorizontalChecks(event, user, face);

            vl += hotbarVl;                                                     // ▼ NEW – add hot-bar VL

            if (vl > 0) {
                this.getManagement().flag(Flag.of(event.getPlayer()).setAddedVl(vl).setCancelAction(cancelVl, () -> {
                    event.setCancelled(true);
                    user.getTimeMap().at(TimeKey.SCAFFOLD_TIMEOUT).update();
                    InventoryUtil.syncUpdateInventory(user.getPlayer());
                }));
            }
        }
    }

    /* ---------------- hot-bar swap → place delay check (returns added VL) ---------------- */

    private int getHotbarVl(User user)
    {
        var data = SWAP_STATE.get(user);
        if (data == null) return 0;

        long now = System.currentTimeMillis();
        long delay = now - data.lastSwap.time();
        if (delay < 0 || delay > hotbarMinSwitchDelay) return 0;
        if (!PingProvider.INSTANCE.atMostMaxPing(user.getPlayer(), hotbarMaxPing)) return 0;

        int add = (delay <= 80) ? 20 : 10;

        int streak;
        long streakStart;
        if (now - data.streakStart <= hotbarStreakWindow) {
            streak = data.streak + 1;
            streakStart = data.streakStart;
        } else {
            streak = 1;
            streakStart = now;
        }
        if (streak >= 4) add += 30;

        SWAP_STATE.put(user, new SwapData(data.lastSwap, streak, streakStart));
        return add;
    }

    // ------------------------------------ existing helper ------------------------------------ //

    private static int handleHorizontalChecks(BlockPlaceEvent event, User user, BlockFace face)
    {
        final var lastScaffoldBlock = user.getScaffoldBatch().peekLastAdded().block();
        // This checks if the block was placed against the expected block for scaffolding.
        final var newScaffoldLocation = !Objects.equals(lastScaffoldBlock, event.getBlockAgainst()) || !WorldUtil.INSTANCE.isNext(lastScaffoldBlock, event.getBlockPlaced(), WorldUtil.HORIZONTAL_FACES);

        // ---------------------------------------------- Average ---------------------------------------------- //

        if (newScaffoldLocation) user.getScaffoldBatch().clear();

        user.getScaffoldBatch().addDataPoint(new ScaffoldBatch.ScaffoldBlockPlace(event.getBlockPlaced(), face, user));

        // --------------------------------------------- Rotations ---------------------------------------------- //

        int vl = ScaffoldAngle.INSTANCE.getVl(user, event);
        vl += ScaffoldPosition.INSTANCE.getVl(event);

        // All these checks may have false positives in new situations.
        if (!newScaffoldLocation) {
            // Do not check jumping for new locations as of wall-building / jumping.
            vl += ScaffoldJumping.INSTANCE.getVl(user, event);
            vl += ScaffoldRotation.INSTANCE.getVl(user);
            vl += ScaffoldSafewalkEdge.INSTANCE.getVl(user, event);
            vl += ScaffoldSafewalkTiming.INSTANCE.getVl(user);
            vl += ScaffoldSprinting.INSTANCE.getVl(user);
        } else {
            ScaffoldJumping.INSTANCE.newScaffoldLocation(user, event, lastScaffoldBlock);
        }
        return vl;
    }

    @Override
    protected ModuleLoader createModuleLoader()
    {
        final var batchProcessor = new ScaffoldAverageBatchProcessor(this);
        return ModuleLoader.builder(this)
                           .batchProcessor(batchProcessor)
                           .build();
    }

    @Override
    protected ViolationManagement createViolationManagement()
    {
        return ViolationLevelManagement.builder(this).loadThresholdsToManagement().withDecay(80, 1).build();
    }
}