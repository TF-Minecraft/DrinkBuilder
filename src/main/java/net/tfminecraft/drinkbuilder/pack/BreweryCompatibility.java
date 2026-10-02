package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.Cache;

/** Repairs BreweryX's cached optional hooks after all plugins have enabled. */
public final class BreweryCompatibility {
    private static final Map<String, String> EFFECT_NAMES = Map.ofEntries(
        Map.entry("CONFUSION", "NAUSEA"), Map.entry("SLOW", "SLOWNESS"),
        Map.entry("FAST_DIGGING", "HASTE"), Map.entry("SLOW_DIGGING", "MINING_FATIGUE"),
        Map.entry("INCREASE_DAMAGE", "STRENGTH"), Map.entry("HEAL", "INSTANT_HEALTH"),
        Map.entry("HARM", "INSTANT_DAMAGE"), Map.entry("JUMP", "JUMP_BOOST"),
        Map.entry("DAMAGE_RESISTANCE", "RESISTANCE"));

    private BreweryCompatibility() {}

    public static String effectToken(String token) {
        String normalized = token.trim().toUpperCase(Locale.ROOT);
        int slash = normalized.indexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(0, slash);
        return EFFECT_NAMES.getOrDefault(name, name)
            + (slash < 0 ? "" : normalized.substring(slash));
    }

    public static void schedule(JavaPlugin plugin) {
        // One tick also handles DrinkBuilder being enabled before MMOItems due to dependency cycles.
        Plugin brewery = plugin.getServer().getPluginManager().getPlugin("BreweryX");
        if (brewery != null && brewery.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> recover(plugin, brewery));
        }
    }

    static void recover(JavaPlugin plugin, Plugin brewery) {
        if (!brewery.isEnabled()) {
            return;
        }
        try {
            ClassLoader loader = brewery.getClass().getClassLoader();
            Class<?> hooks = Class.forName("com.dre.brewery.integration.Hook", true, loader);
            boolean repaired = false;
            for (String name : List.of("MMOItems", "ItemsAdder")) {
                Plugin dependency = plugin.getServer().getPluginManager().getPlugin(name);
                if (dependency != null && dependency.isEnabled()) {
                    Object hook = hooks.getField(name.toUpperCase(Locale.ROOT)).get(null);
                    if (!(boolean) hooks.getMethod("isEnabled").invoke(hook)) {
                        hooks.getMethod("setEnabled", boolean.class).invoke(hook, true);
                        hooks.getMethod("setChecked", boolean.class).invoke(hook, false);
                        repaired = true;
                    }
                }
            }
            Class<?> configs = Class.forName("com.dre.brewery.configuration.ConfigManager", true, loader);
            if (repaired) {
                configs.getMethod("registerDefaultPluginItems").invoke(null);
            }
            File recipes = new File(RecipesYmlMerger.resolvePath(plugin, Cache.breweryxFolder), "recipes.yml");
            boolean migrated = migrateEffects(recipes);
            if (migrated) {
                Class<?> recipeFile = Class.forName("com.dre.brewery.configuration.files.RecipesFile", true, loader);
                configs.getMethod("newInstance", Class.class, boolean.class).invoke(null, recipeFile, true);
            }
            if (repaired || migrated) {
                configs.getMethod("loadCauldronIngredients").invoke(null);
                configs.getMethod("loadRecipes").invoke(null);
                plugin.getLogger().info("[brewery] restored custom-item hooks and recipe effects after plugin startup");
            }
        } catch (ReflectiveOperationException | IOException | LinkageError e) {
            plugin.getLogger().warning("[brewery] compatibility repair failed: " + e);
        }
    }

    static boolean migrateEffects(File file) throws IOException {
        if (!file.isFile()) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (org.bukkit.configuration.InvalidConfigurationException e) {
            throw new IOException("Invalid BreweryX recipes; leaving the file unchanged", e);
        }
        ConfigurationSection recipes = yaml.getConfigurationSection("recipes");
        if (recipes == null) {
            return false;
        }
        boolean changed = false;
        for (String key : recipes.getKeys(false)) {
            List<String> effects = recipes.getStringList(key + ".effects");
            List<String> normalized = new ArrayList<>();
            for (String effect : effects) {
                normalized.add(effectToken(effect));
            }
            if (!effects.equals(normalized)) {
                recipes.set(key + ".effects", normalized);
                changed = true;
            }
        }
        if (changed) {
            // Preserve each original, including recipe fields unrelated to the migration.
            Files.copy(file.toPath(), Files.createTempFile(file.toPath().getParent(),
                "recipes-before-effect-migration-", ".bak"), StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Path temporary = Files.createTempFile(file.toPath().getParent(), "recipes-effects-", ".tmp");
            try {
                yaml.save(temporary.toFile());
                Files.move(temporary, file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return changed;
    }
}
