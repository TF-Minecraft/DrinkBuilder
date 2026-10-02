package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.dre.brewery.configuration.ConfigManager;
import com.dre.brewery.integration.Hook;
import net.tfminecraft.drinkbuilder.Cache;

class BreweryCompatibilityTest {
    @TempDir Path directory;
    String priorFolder;
    JavaPlugin plugin;
    Plugin brewery;
    PluginManager manager;
    BukkitScheduler scheduler;
    Logger log;

    @BeforeEach void setup() {
        priorFolder = Cache.breweryxFolder;
        Cache.breweryxFolder = directory.toString();
        plugin = mock(JavaPlugin.class);
        brewery = mock(Plugin.class);
        when(brewery.isEnabled()).thenReturn(true);
        Server server = mock(Server.class);
        manager = mock(PluginManager.class);
        scheduler = mock(BukkitScheduler.class);
        log = mock(Logger.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(log);
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getScheduler()).thenReturn(scheduler);
        Hook.MMOITEMS.enabled = Hook.ITEMSADDER.enabled = false;
        Hook.MMOITEMS.checked = Hook.ITEMSADDER.checked = true;
        ConfigManager.registrations = ConfigManager.recipeLoads = ConfigManager.cauldronLoads = ConfigManager.reloads = 0;
        ConfigManager.fail = false;
    }
    @AfterEach void restore() { Cache.breweryxFolder = priorFolder; }

    @Test void schedulesOnlyWhenBreweryIsReadyAndRunsAfterPluginStartup() {
        BreweryCompatibility.schedule(plugin);
        verifyNoInteractions(scheduler);
        when(manager.getPlugin("BreweryX")).thenReturn(brewery);
        when(brewery.isEnabled()).thenReturn(false);
        BreweryCompatibility.schedule(plugin);
        verifyNoInteractions(scheduler);
        when(brewery.isEnabled()).thenReturn(true);
        doAnswer(call -> { call.getArgument(1, Runnable.class).run(); return null; })
            .when(scheduler).runTask(eq(plugin), any(Runnable.class));
        BreweryCompatibility.schedule(plugin);
        verify(scheduler).runTask(eq(plugin), any(Runnable.class));
        when(brewery.isEnabled()).thenReturn(false);
        BreweryCompatibility.recover(plugin, brewery);
        assertEquals(0, ConfigManager.recipeLoads);
    }

    @Test void repairsCachedHooksRebuildsCauldronsAndRecipesAndIsIdempotent() {
        Plugin mmo = mock(Plugin.class);
        when(manager.getPlugin("MMOItems")).thenReturn(mmo);
        BreweryCompatibility.recover(plugin, brewery);
        assertEquals(0, ConfigManager.registrations);
        when(mmo.isEnabled()).thenReturn(true);
        when(manager.getPlugin("ItemsAdder")).thenReturn(mmo);
        BreweryCompatibility.recover(plugin, brewery);
        assertTrue(Hook.MMOITEMS.enabled);
        assertFalse(Hook.MMOITEMS.checked);
        assertTrue(Hook.ITEMSADDER.enabled);
        assertEquals(1, ConfigManager.registrations);
        assertEquals(1, ConfigManager.cauldronLoads);
        assertEquals(1, ConfigManager.recipeLoads);
        BreweryCompatibility.recover(plugin, brewery);
        assertEquals(1, ConfigManager.recipeLoads);
        verify(log).info(contains("restored custom-item hooks"));
    }

    @Test void migratesExistingEffectsAndPreservesRecipeDataWithBackup() throws Exception {
        Path file = directory.resolve("recipes.yml");
        String original = "recipes:\n  existing:\n    name: Keep\n    ingredients: [MMOItems:BARK/1]\n    effects: [CONFUSION/1-2/30-60, JUMP/2/30, SPEED/1/10]\n";
        Files.writeString(file, original);
        BreweryCompatibility.recover(plugin, brewery);
        var yaml = YamlConfiguration.loadConfiguration(file.toFile());
        assertEquals(List.of("NAUSEA/1-2/30-60", "JUMP_BOOST/2/30", "SPEED/1/10"),
            yaml.getStringList("recipes.existing.effects"));
        assertEquals("Keep", yaml.getString("recipes.existing.name"));
        assertEquals(List.of("MMOItems:BARK/1"), yaml.getStringList("recipes.existing.ingredients"));
        try (var files = Files.list(directory)) {
            assertEquals(original, Files.readString(files.filter(p -> p.toString().endsWith(".bak")).findFirst().orElseThrow()));
        }
        assertEquals(1, ConfigManager.reloads);
        assertEquals(1, ConfigManager.recipeLoads);
        assertFalse(BreweryCompatibility.migrateEffects(file.toFile()));
    }

    @Test void leavesMissingAndUnrelatedFilesAloneAndRejectsInvalidYaml() throws Exception {
        Path file = directory.resolve("recipes.yml");
        assertFalse(BreweryCompatibility.migrateEffects(file.toFile()));
        Files.writeString(file, "other: true\n");
        assertFalse(BreweryCompatibility.migrateEffects(file.toFile()));
        Files.writeString(file, "recipes:\n  drink:\n    name: No effects\n");
        assertFalse(BreweryCompatibility.migrateEffects(file.toFile()));
        Files.writeString(file, "recipes: [invalid\n");
        assertThrows(IOException.class, () -> BreweryCompatibility.migrateEffects(file.toFile()));
        BreweryCompatibility.recover(plugin, brewery);
        verify(log).warning(contains("compatibility repair failed"));
        assertEquals("recipes: [invalid\n", Files.readString(file));
    }

    @Test void reportsOptionalApiFailures() {
        Plugin mmo = mock(Plugin.class);
        when(manager.getPlugin("MMOItems")).thenReturn(mmo);
        when(mmo.isEnabled()).thenReturn(true);
        ConfigManager.fail = true;
        BreweryCompatibility.recover(plugin, brewery);
        verify(log).warning(contains("compatibility repair failed"));
    }

    @Test void translatesLegacyNamesWithoutChangingEffectRanges() {
        String[] old = {"confusion", "slow", "fast_digging", "slow_digging", "increase_damage", "heal", "harm", "jump", "damage_resistance"};
        String[] modern = {"NAUSEA", "SLOWNESS", "HASTE", "MINING_FATIGUE", "STRENGTH", "INSTANT_HEALTH", "INSTANT_DAMAGE", "JUMP_BOOST", "RESISTANCE"};
        for (int i = 0; i < old.length; i++) {
            assertEquals(modern[i], BreweryCompatibility.effectToken(" " + old[i] + " "));
            assertEquals(modern[i] + "/1-2/30-60", BreweryCompatibility.effectToken(old[i] + "/1-2/30-60"));
        }
    }
}
