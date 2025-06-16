package com.Lino.blocksGenerator;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
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
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class BlocksGenerator extends JavaPlugin implements Listener, TabCompleter {

    private final Map<String, List<Material>> generators = new HashMap<>();
    private final Map<Location, GeneratorData> activeGenerators = new ConcurrentHashMap<>();
    private NamespacedKey generatorKey;
    private Connection database;
    private BukkitRunnable monitorTask;

    private static class GeneratorData {
        final String type;

        GeneratorData(String type) {
            this.type = type;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadGenerators();
        initDatabase();
        generatorKey = new NamespacedKey(this, "generator");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("blocksgen").setTabCompleter(this);
        loadGeneratorsFromDB();
        startMonitorTask();
    }

    @Override
    public void onDisable() {
        if (monitorTask != null) monitorTask.cancel();
        activeGenerators.clear();
        try {
            if (database != null && !database.isClosed()) database.close();
        } catch (SQLException e) {
            getLogger().severe("Failed to close database: " + e.getMessage());
        }
    }

    private void initDatabase() {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }

            File dbFile = new File(getDataFolder(), "generators.db");
            database = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement stmt = database.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS generators (" +
                        "world TEXT NOT NULL," +
                        "x INTEGER NOT NULL," +
                        "y INTEGER NOT NULL," +
                        "z INTEGER NOT NULL," +
                        "type TEXT NOT NULL," +
                        "PRIMARY KEY (world, x, y, z)" +
                        ")");
            }
        } catch (SQLException e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
        }
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

    private void loadGeneratorsFromDB() {
        try (Statement stmt = database.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM generators")) {

            while (rs.next()) {
                String worldName = rs.getString("world");
                if (Bukkit.getWorld(worldName) == null) continue;

                Location loc = new Location(
                        Bukkit.getWorld(worldName),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z")
                );

                String type = rs.getString("type");
                if (generators.containsKey(type)) {
                    Block block = loc.getBlock();
                    if (block.getType() == Material.SEA_LANTERN) {
                        activeGenerators.put(loc, new GeneratorData(type));
                    }
                }
            }

            getLogger().info("Loaded " + activeGenerators.size() + " generators from database");
        } catch (SQLException e) {
            getLogger().severe("Failed to load generators: " + e.getMessage());
        }
    }

    private void startMonitorTask() {
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Map.Entry<Location, GeneratorData> entry : activeGenerators.entrySet()) {
                    Location genLoc = entry.getKey();
                    GeneratorData data = entry.getValue();

                    if (!genLoc.getChunk().isLoaded()) continue;

                    Block genBlock = genLoc.getBlock();
                    if (genBlock.getType() != Material.SEA_LANTERN) {
                        activeGenerators.remove(genLoc);
                        removeFromDB(genLoc);
                        continue;
                    }

                    Location blockLoc = genLoc.clone().add(0, 1, 0);
                    Block block = blockLoc.getBlock();

                    if (block.getType() == Material.AIR) {
                        regenerateBlock(blockLoc, data);
                    }
                }
            }
        };
        monitorTask.runTaskTimer(this, 0L, 2L);
    }

    private void regenerateBlock(Location loc, GeneratorData genData) {
        List<Material> materials = generators.get(genData.type);
        if (materials == null || materials.isEmpty()) return;

        Material newMaterial = materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
        loc.getBlock().setType(newMaterial);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("blocksgen")) return false;

        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.RED + "Usage: /blocksgen give <player> <generator>");
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("give").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            return generators.keySet().stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private ItemStack createGeneratorBlock(String generatorName) {
        ItemStack item = new ItemStack(Material.SEA_LANTERN);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.YELLOW + generatorName.substring(0, 1).toUpperCase() + generatorName.substring(1) + " Generator");
        meta.setLore(Arrays.asList(ChatColor.GRAY + "Place this block to create", ChatColor.GRAY + "an infinite block above it"));
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
        if (item.getType() != Material.SEA_LANTERN || !item.hasItemMeta()) return;

        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();

        if (container.has(generatorKey, PersistentDataType.STRING)) {
            String generatorName = container.get(generatorKey, PersistentDataType.STRING);
            if (generators.containsKey(generatorName)) {
                Location loc = event.getBlock().getLocation();

                activeGenerators.put(loc, new GeneratorData(generatorName));
                saveToDB(loc, generatorName);

                // Genera il primo blocco sopra
                Location blockLoc = loc.clone().add(0, 1, 0);
                List<Material> materials = generators.get(generatorName);
                if (!materials.isEmpty()) {
                    Material initialMat = materials.get(ThreadLocalRandom.current().nextInt(materials.size()));
                    blockLoc.getBlock().setType(initialMat);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        GeneratorData data = activeGenerators.get(loc);

        if (data != null) {
            if (event.getPlayer().isSneaking()) {
                activeGenerators.remove(loc);
                removeFromDB(loc);
                event.setDropItems(false);
                event.getBlock().getWorld().dropItemNaturally(loc, createGeneratorBlock(data.type));
            } else {
                event.setCancelled(true);
                event.getPlayer().sendMessage(ChatColor.RED + "Sneak to break the generator!");
            }
        }
    }

    private void saveToDB(Location loc, String type) {
        try (PreparedStatement stmt = database.prepareStatement(
                "INSERT OR REPLACE INTO generators (world, x, y, z, type) VALUES (?, ?, ?, ?, ?)")) {
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockY());
            stmt.setInt(4, loc.getBlockZ());
            stmt.setString(5, type);
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Failed to save generator: " + e.getMessage());
        }
    }

    private void removeFromDB(Location loc) {
        try (PreparedStatement stmt = database.prepareStatement(
                "DELETE FROM generators WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
            stmt.setString(1, loc.getWorld().getName());
            stmt.setInt(2, loc.getBlockX());
            stmt.setInt(3, loc.getBlockY());
            stmt.setInt(4, loc.getBlockZ());
            stmt.executeUpdate();
        } catch (SQLException e) {
            getLogger().severe("Failed to remove generator: " + e.getMessage());
        }
    }
}