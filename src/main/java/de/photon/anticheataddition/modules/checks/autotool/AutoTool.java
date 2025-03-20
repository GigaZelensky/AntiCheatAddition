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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;

public final class AutoTool extends ViolationModule implements Listener {
    
    public static final AutoTool INSTANCE = new AutoTool();
    
    private final int cancelVl = loadInt(".cancel_vl", 60);
    private final int timeout = loadInt(".timeout", 3000);
    private final int maxPing = loadInt(".max_ping", 400);
    // Minimum time in ms that should reasonably pass between starting to break a block and switching tools
    private final int minSwitchDelay = loadInt(".min_switch_delay", 150);
    
    private AutoTool() {
        super("AutoTool");
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamage(BlockDamageEvent event) {
        final var user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;
        
        // Record the time when the player starts breaking a block
        user.getTimeMap().at(TimeKey.AUTOTOOL_BLOCK_BREAK_START).update();
        // Store the block type for later tool efficiency checking
        user.getData().object.autoToolTargetBlock = event.getBlock();
    }
    
    @EventHandler(ignoreCancelled = true)
    public void onItemSwitch(PlayerItemHeldEvent event) {
        final var user = User.getUser(event.getPlayer());
        if (User.isUserInvalid(user, this)) return;
        
        // Skip if the player's ping is too high
        if (PingProvider.INSTANCE.getPing(user.getPlayer()) > maxPing) return;
        
        // Timeout mechanism
        if (user.getTimeMap().at(TimeKey.AUTOTOOL_TIMEOUT).recentlyUpdated(timeout)) {
            event.setCancelled(true);
            InventoryUtil.syncUpdateInventory(user.getPlayer());
            return;
        }
        
        // Check if player recently started breaking a block
        if (user.getTimeMap().at(TimeKey.AUTOTOOL_BLOCK_BREAK_START).recentlyUpdated(1000)) {
            // If they switched tools too quickly after starting to break
            long timeSinceBreakStart = user.getTimeMap().at(TimeKey.AUTOTOOL_BLOCK_BREAK_START).passedTime();
            
            if (timeSinceBreakStart < minSwitchDelay) {
                // Check if they switched to the optimal tool
                final Block targetBlock = user.getData().object.autoToolTargetBlock;
                
                // Make sure we have a valid target block
                if (targetBlock != null && targetBlock.getType() != Material.AIR) {
                    final int newSlot = event.getNewSlot();
                    final ItemStack newTool = user.getPlayer().getInventory().getItem(newSlot);
                    
                    // Check if the new tool is optimal for the target block
                    if (newTool != null && isOptimalToolForBlock(newTool, targetBlock)) {
                        // Calculate VL based on how suspiciously quick the switch was
                        int vl = (int) (30 * (1.0 - (timeSinceBreakStart / (double) minSwitchDelay)));
                        
                        this.getManagement().flag(Flag.of(user)
                                .setAddedVl(Math.max(vl, 5))
                                .setDebug(() -> "AutoTool-Debug | Player: " + user.getPlayer().getName() + 
                                        " | TimeSinceBreak: " + timeSinceBreakStart + 
                                        " | SwitchedTo: " + newTool.getType())
                                .setCancelAction(cancelVl, () -> {
                                    // Cancel the switch by switching back
                                    user.getPlayer().getInventory().setHeldItemSlot(event.getPreviousSlot());
                                    InventoryUtil.syncUpdateInventory(user.getPlayer());
                                    user.getTimeMap().at(TimeKey.AUTOTOOL_TIMEOUT).update();
                                }));
                    }
                }
            }
        }
    }
    
    private boolean isOptimalToolForBlock(ItemStack tool, Block block) {
        Material toolType = tool.getType();
        Material blockType = block.getType();
        
        // Check if the tool is the optimal one for the block
        String blockName = blockType.name().toLowerCase();
        
        // Stone, ores, etc. - Pickaxe is optimal
        if (blockName.contains("stone") || blockName.contains("ore") || 
            blockName.contains("cobblestone") || blockName.contains("brick") ||
            blockName.contains("obsidian")) {
            return toolType.name().contains("PICKAXE");
        }
        
        // Dirt, grass, sand, etc. - Shovel is optimal
        if (blockName.contains("dirt") || blockName.contains("grass") || 
            blockName.contains("sand") || blockName.contains("gravel") ||
            blockName.contains("soul_sand") || blockName.contains("clay") ||
            blockName.contains("mycelium") || blockName.contains("podzol")) {
            return toolType.name().contains("SHOVEL");
        }
        
        // Wood, logs, planks, etc. - Axe is optimal
        if (blockName.contains("wood") || blockName.contains("log") || 
            blockName.contains("plank") || blockName.contains("fence") ||
            blockName.contains("leaves") || blockName.contains("bookshelf")) {
            return toolType.name().contains("AXE");
        }
        
        // Certain crops - Hoe is optimal (though rarely used for breaking)
        if (blockName.contains("crop") || blockName.contains("farmland") ||
            blockName.contains("hay")) {
            return toolType.name().contains("HOE");
        }
        
        // For certain specialized blocks - Sword can be optimal (like cobwebs)
        if (blockName.contains("web") || blockName.contains("bamboo")) {
            return toolType.name().contains("SWORD");
        }
        
        return false;
    }
    
    @Override
    protected ViolationManagement createViolationManagement() {
        return ViolationLevelManagement.builder(this)
                .loadThresholdsToManagement()
                .withDecay(120, 30)
                .build();
    }
}