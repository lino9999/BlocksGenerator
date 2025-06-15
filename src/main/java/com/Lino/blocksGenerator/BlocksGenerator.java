package com.Lino.blocksGenerator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class BlocksGenerator extends JavaPlugin implements Listener {

    private final Map<String, List<Material>> generators = new HashMap<>();
    private final Map<Location, String> activeGenerators = new ConcurrentHashMap<>();
    private NamespacedKey generatorKey;
    private boolean hasAutoMiner = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadGenerators();
        generatorKey = new NamespacedKey(this, "generator");
        getServer().getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            hasAutoMiner = Bukkit.getPluginManager().isPluginEnabled("AutoMiner");
            if (hasAutoMiner) {
                getLogger().info("AutoMiner detected - Enhanced compatibility enabled!");
            }
        }, 20L);
    }

    @Override
    public void onDisable() {
        activeGenerators.clear();
    }

    private void loadGenerators() {
        ConfigurationSection section = getConfig().getConfigurationSection("generators");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            List<String> blockNames = section.getStringList(key + ".blocks");
            List<Material> materials = new ArrayList<>();

            for (String blockName : blockNames) {
                try {
                    Material mat = Material.valueOf(blockName.toUpperCase());
                    if (mat.isBlock()) {
                        materials.add(mat);
                    }
                } catch (IllegalArgumentException ignored) {}
            }

            if (!materials.isEmpty()) {
                generators.put(key.toLowerCase(), materials);
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("blockgenerator")) return false;

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Usage: /blockgenerator give <player> <generator>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found!");
            return true;
        }

        String generatorName = args[2].toLowerCase();
        if (!generators.containsKey(generatorName)) {
            sender.sendMessage(ChatColor.RED + "Generator not found!");
            return true;
        }

        ItemStack generatorBlock = createGeneratorBlock(generatorName);
        target.getInventory().addItem(generatorBlock);
        sender.sendMessage(ChatColor.GREEN + "Generator given successfully!");
        return true;
    }

    private ItemStack createGeneratorBlock(String generatorName) {
        ItemStack item = new ItemStack(Material.BEDROCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + generatorName.substring(0, 1).toUpperCase() + generatorName.substring(1) + " Generator");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Place this block to create", ChatColor.GRAY + "an infinite block generator"));
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(generatorKey, PersistentDataType.STRING, generatorName);

        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.BEDROCK || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();

        if (container.has(generatorKey, PersistentDataType.STRING)) {
            String generatorName = container.get(generatorKey, PersistentDataType.STRING);
            if (generators.containsKey(generatorName)) {
                Location loc = event.getBlock().getLocation();
                activeGenerators.put(loc, generatorName);

                List<Material> materials = generators.get(generatorName);
                Material randomMaterial = materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
                event.getBlock().setType(randomMaterial);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        String generatorName = activeGenerators.get(loc);

        if (generatorName != null) {
            List<Material> materials = generators.get(generatorName);
            if (materials != null && !materials.isEmpty()) {
                event.setDropItems(true);

                int delay = 1;
                if (hasAutoMiner && isNearAutoMiner(loc)) {
                    delay = getConfig().getInt("autominer-regen-delay", 1);
                }

                final int finalDelay = delay;
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    if (loc.getBlock().getType() == Material.AIR) {
                        Material randomMaterial = materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
                        loc.getBlock().setType(randomMaterial);
                    }
                }, finalDelay);
            }
        }
    }

    private boolean isNearAutoMiner(Location loc) {
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    if (block.getType() == Material.END_ROD) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}