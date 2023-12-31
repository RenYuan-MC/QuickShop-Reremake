/*
 * This file is a part of project QuickShop, the name is ShopProtectionListener.java
 *  Copyright (C) PotatoCraft Studio and contributors
 *
 *  This program is free software: you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the
 *  Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package org.maxgamer.quickshop.listener;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.maxgamer.quickshop.Cache;
import org.maxgamer.quickshop.QuickShop;
import org.maxgamer.quickshop.api.shop.Shop;
import org.maxgamer.quickshop.util.MsgUtil;
import org.maxgamer.quickshop.util.Util;
import org.maxgamer.quickshop.util.logging.container.ShopRemoveLog;
import org.maxgamer.quickshop.util.reload.ReloadResult;
import org.maxgamer.quickshop.util.reload.ReloadStatus;

import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.logging.Level;

public class ShopProtectionListener extends AbstractProtectionListener {

    private boolean useEnhanceProtection;

    private boolean sendProtectionAlert;

    public ShopProtectionListener(@NotNull QuickShop plugin, @Nullable Cache cache) {
        super(plugin, cache);
        init();
    }

    private void init() {
        this.sendProtectionAlert = plugin.getConfig().getBoolean("send-shop-protection-alert", false);
        useEnhanceProtection = plugin.getConfig().getBoolean("shop.enchance-shop-protect", true);
        scanAndFixPaperListener();
    }

    @Override
    public ReloadResult reloadModule() {
        init();
        return ReloadResult.builder().status(ReloadStatus.SUCCESS).build();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        scanAndFixPaperListener();
    }

    public void scanAndFixPaperListener() {
        if (!plugin.getConfig().getBoolean("protect.hopper")) {
            return;
        }
        if (!Util.isClassAvailable("com.destroystokyo.paper.PaperWorldConfig")) {
            return;
        }
        Util.debugLog("QuickShop is scanning all worlds settings about disableHopperMoveEvents disabled worlds");
        plugin.getServer().getWorlds().forEach(world -> {
            if (plugin.getShopManager().getShopsInWorld(world).isEmpty()) {
                return;
            }
            //Checking and changing memory value and file
            //Value for store results
            boolean disableHopperMoveEvents;
            //Reading and changing memory value
            try {
                Field worldServerF = world.getClass().getDeclaredField("world");
                worldServerF.setAccessible(true);
                Object worldServer = worldServerF.get(world);
                Field paperConfigF = worldServer.getClass().getSuperclass().getDeclaredField("paperConfig");
                paperConfigF.setAccessible(true);
                Object paperWorldConfig = paperConfigF.get(worldServer);
                Field disableHopperMoveEventsF;
                //Old version
                try {
                    disableHopperMoveEventsF = paperWorldConfig.getClass().getDeclaredField("disableHopperMoveEvents");
                    disableHopperMoveEventsF.setAccessible(true);
                    disableHopperMoveEvents = disableHopperMoveEventsF.getBoolean(paperWorldConfig);
                    if (disableHopperMoveEvents) {
                        disableHopperMoveEventsF.setBoolean(paperWorldConfig, false);
                    }
                    // New version
                } catch (NoSuchFieldException e) {
                    Field hopperF = paperWorldConfig.getClass().getDeclaredField("hopper");
                    hopperF.setAccessible(true);
                    Object hopperConfig = hopperF.get(paperWorldConfig);
                    disableHopperMoveEventsF = hopperConfig.getClass().getDeclaredField("disableMoveEvent");
                    disableHopperMoveEventsF.setAccessible(true);
                    disableHopperMoveEvents = disableHopperMoveEventsF.getBoolean(hopperConfig);
                    if (disableHopperMoveEvents) {
                        disableHopperMoveEventsF.setBoolean(hopperConfig, false);
                    }
                }

                //Printing warning
                if (disableHopperMoveEvents) {
                    plugin.getMorePaperLib().scheduling().globalRegionalScheduler().run(() -> {
                        plugin.getLogger()
                                .warning("World " + world.getName()
                                        + " have shops and Hopper protection is enabled. But we detected" +
                                        " \"disableHopperMoveEvents\" options in \"paper.yml\" is activated, so QuickShop already automatic disabled it.");
                        plugin.getLogger()
                                .warning("If you still want keep enable disableHopperMoveEvents enables " +
                                        "in this world, please disable Hopper protection or make sure no shops in this world.");

                    });
                }
                //File related changes
                if (disableHopperMoveEvents) {
                    //Current work dir
                    File serverRoot = new File(".");
                    File paperConfigYaml = new File(serverRoot, "paper.yml");
                    //Old version
                    if (paperConfigYaml.exists()) {
                        YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration(paperConfigYaml);
                        ConfigurationSection worldsSection = yamlConfiguration.getConfigurationSection("world-settings");
                        ConfigurationSection defaultWorldSection;
                        ConfigurationSection perWorldSection;
                        if (worldsSection != null) {
                            perWorldSection = worldsSection.getConfigurationSection(world.getName());
                            defaultWorldSection = worldsSection.getConfigurationSection("default");
                            if (checkAndEnableMoveEvent(perWorldSection) || checkAndEnableMoveEvent(defaultWorldSection)) {
                                yamlConfiguration.save(paperConfigYaml);
                            }
                        }
                        //New version
                    } else {
                        File paperConfigYamlNew = serverRoot.toPath().resolve("config").resolve("paper-world-defaults.yml").toFile();
                        if (paperConfigYamlNew.exists()) {
                            YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration(paperConfigYamlNew);
                            if (checkAndEnableMoveEvent(yamlConfiguration)) {
                                yamlConfiguration.save(paperConfigYamlNew);
                            }
                        }
                        File paperConfigPerWorldYamlNew = serverRoot.toPath().resolve(world.getName()).resolve("paper-world.yml").toFile();
                        if (paperConfigPerWorldYamlNew.exists()) {
                            YamlConfiguration yamlConfiguration = YamlConfiguration.loadConfiguration(paperConfigPerWorldYamlNew);
                            if (checkAndEnableMoveEvent(yamlConfiguration)) {
                                yamlConfiguration.save(paperConfigPerWorldYamlNew);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Failed to automatic disable disable-move-event for world [" + world.getName() + "], please disable it by yourself or player can steal items from shops.", ex);
            }
        });
    }

    private boolean checkAndEnableMoveEvent(ConfigurationSection section) {
        if (section != null && section.isSet("hopper.disable-move-event") && section.getBoolean("hopper.disable-move-event")) {
            section.set("hopper.disable-move-event", false);
            section.set("hopper.disable-move-event-quickshop-tips", "QuickShop automatic disabled this due it will allow other players steal items from shop. This notice only shown when have shops in current world and hopper protection is on and also disable-move-event turned on.");
            return true;
        }
        return false;
    }

    //Prevent skulker box or shop sign being pushed, see https://github.com/PotatoCraft-Studio/QuickShop-Reremake/issues/248
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBeingPushed(BlockPistonExtendEvent event) {
        if (!useEnhanceProtection) {
            return;
        }
        for (org.bukkit.block.Block block : event.getBlocks()) {
            Shop shop = getShopRedstone(block.getLocation(), true);
            if (shop != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        for (int i = 0, a = e.blockList().size(); i < a; i++) {
            final Block b = e.blockList().get(i);
            Shop shop = getShopNature(b.getLocation(), true);
            if (shop == null) {
                shop = getShopNextTo(b.getLocation());
            }
            if (shop != null) {
                if (plugin.getConfig().getBoolean("protect.explode")) {
                    e.setCancelled(true);
                } else {
                    plugin.logEvent(new ShopRemoveLog(Util.getNilUniqueId(), "BlockBreak(explode)", shop.saveToInfoStorage()));
                    shop.delete();
                }
            }
        }
    }

    /**
     * Gets the shop a sign is attached to
     *
     * @param loc The location of the sign
     * @return The shop
     */
    @Nullable
    private Shop getShopNextTo(@NotNull Location loc) {
        final Block b = Util.getAttached(loc.getBlock());
        // Util.getAttached(b)
        if (b == null) {
            return null;
        }

        return getShopNature(b.getLocation(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent e) {
        if (!useEnhanceProtection) {
            return;
        }

        final Shop shop = getShopNature(e.getToBlock().getLocation(), true);

        if (shop == null) {
            return;
        }

        e.setCancelled(true);
    }

    // Protect Redstone active shop
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockRedstoneChange(BlockRedstoneEvent event) {
        if (!useEnhanceProtection) {
            return;
        }

        final Shop shop = getShopRedstone(event.getBlock().getLocation(), true);

        if (shop == null) {
            return;
        }

        event.setNewCurrent(event.getOldCurrent());
        // plugin.getLogger().warning("[Exploit Alert] a Redstone tried to active of " + shop);
        // Util.debugLog(ChatColor.RED + "[QuickShop][Exploit alert] Redstone was activated on the
        // following shop " + shop);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent e) {
        if (!useEnhanceProtection) {
            return;
        }

        final Block newBlock = e.getNewState().getBlock();
        final Shop thisBlockShop = getShopNature(newBlock.getLocation(), true);

        if (thisBlockShop == null) {
            return;
        }
        final Shop underBlockShop =
                getShopNature(newBlock.getRelative(BlockFace.DOWN).getLocation(), true);
        if (underBlockShop == null) {
            return;
        }
        e.setCancelled(true);
    }

    /*
     * Handles shops breaking through explosions
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {

        for (int i = 0, a = e.blockList().size(); i < a; i++) {
            final Block b = e.blockList().get(i);
            final Shop shop = getShopNature(b.getLocation(), true);

            if (shop == null) {
                continue;
            }
            if (plugin.getConfig().getBoolean("protect.explode")) {
                e.setCancelled(true);
            } else {
                plugin.logEvent(new ShopRemoveLog(Util.getNilUniqueId(), "BlockBreak(explode)", shop.saveToInfoStorage()));
                shop.delete();
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (!plugin.getConfig().getBoolean("protect.hopper")) {
            return;
        }
        final Location loc = event.getSource().getLocation();

        if (loc == null) {
            return;
        }
        final Shop shop = getShopRedstone(loc, true);

        if (shop == null) {
            return;
        }

        event.setCancelled(true);

        final Location location = event.getInitiator().getLocation();

        if (location == null) {
            return;
        }

        final InventoryHolder holder = event.getInitiator().getHolder();

        if (holder instanceof Entity) {
            ((Entity) holder).remove();
        } else if (holder instanceof Block) {
            location.getBlock().breakNaturally();
        } else {
            Util.debugLog("Unknown location = " + loc);
        }

        if (sendProtectionAlert) {
            MsgUtil.sendGlobalAlert("[DisplayGuard] Defend a item steal action at" + location);
        }
    }

    // Protect Entity pickup shop
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMobChangeBlock(EntityChangeBlockEvent event) {
        final Shop shop = getShopNature(event.getBlock().getLocation(), true);

        if (shop == null) {
            return;
        }

        if (plugin.getConfig().getBoolean("protect.entity")) {
            event.setCancelled(true);
            return;
        }
        plugin.logEvent(new ShopRemoveLog(Util.getNilUniqueId(), "EntityChanges", shop.saveToInfoStorage()));
        shop.delete();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        if (!useEnhanceProtection) {
            return;
        }

        for (BlockState blockstate : event.getBlocks()) {
            final Shop shop = getShopNature(blockstate.getLocation(), true);

            if (shop == null) {
                continue;
            }

            event.setCancelled(true);
            return;
            // plugin.getLogger().warning("[Exploit Alert] a StructureGrowing tried to break the shop of "
            // + shop);
            // Util.sendMessageToOps(ChatColor.RED + "[QuickShop][Exploit alert] A StructureGrowing tried
            // to break the shop of " + shop);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSponging(SpongeAbsorbEvent event) {
        if (!useEnhanceProtection) {
            return;
        }
        List<BlockState> blocks = event.getBlocks();
        for (BlockState block : blocks) {
            if (getShopNature(block.getLocation(), true) != null) {
                event.setCancelled(true);
            }
        }
    }

}
