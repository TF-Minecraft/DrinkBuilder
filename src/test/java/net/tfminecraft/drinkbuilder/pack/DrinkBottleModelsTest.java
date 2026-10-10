package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.tfminecraft.drinkbuilder.Cache;

class DrinkBottleModelsTest {
    private static final String NS = "tfmc_drinks";

    @TempDir Path dir;
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Logger log = mock(Logger.class);
    private String savedRoot;
    private Path root, cache, items, potion, empty, models;
    private MockedStatic<DrinksNamespace> namespace;

    @BeforeEach void setUp() throws Exception {
        savedRoot = Cache.itemsAdderTfmcDrinks;
        root = dir.resolve("plugins/ItemsAdder/contents/tfmc_drinks");
        Cache.itemsAdderTfmcDrinks = root.toString();
        Path data = dir.resolve("plugins/DrinkBuilder");
        Files.createDirectories(data);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(log);
        namespace = mockStatic(DrinksNamespace.class);
        namespace.when(DrinksNamespace::current).thenReturn(NS);
        cache = IaDrinksWriter.cmdCache(root.toFile()).toPath();
        items = root.resolve("configs/items.yml");
        potion = root.resolve("resourcepack/minecraft/models/item/potion.json");
        empty = root.resolve("resourcepack/tfmc_drinks/textures/item/bottle/empty.png");
        models = root.resolve("resourcepack/tfmc_drinks/models/item/bottle");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, """
            PAPER:
              tfmc_drinks:paper_only: 10

            POTION:
              other:ale: 20002
              tfmc_drinks:ale: 20001
              tfmc_drinks:mead: 20000
              tfmc_drinks:no_texture: 20003
              tfmc_drinks:retired: 20004
              tfmc_drinks:broken: soon
              tfmc_drinks:no_value
            IRON_SWORD:
              tfmc_drinks:sword: 20009
            \ttfmc_drinks:tab_sword: 20010
            """);
        drinks("ale", "mead", "no_texture", "paper_only", "sword");
        texture("ale");
        texture("mead");
        texture("paper_only");
        texture("sword");
        texture("retired");
    }

    @AfterEach void tearDown() {
        namespace.close();
        Cache.itemsAdderTfmcDrinks = savedRoot;
    }

    @Test void mapsEachTexturedDrinkCmdToAnUntintedBottleModel() throws Exception {
        assertTrue(DrinkBottleModels.sync(plugin));

        assertEquals(List.of(20000, 20001), overrideCmds());
        assertEquals("tfmc_drinks:item/bottle/mead", overrides().get(0).getAsJsonObject().get("model").getAsString());
        JsonObject potionModel = read(potion);
        assertEquals("minecraft:item/potion_overlay",
            potionModel.getAsJsonObject("textures").get("layer0").getAsString());
        JsonObject textures = read(models.resolve("ale.json")).getAsJsonObject("textures");
        assertEquals("tfmc_drinks:item/bottle/empty", textures.get("layer0").getAsString());
        assertEquals("tfmc_drinks:item/ale", textures.get("layer1").getAsString());
        assertEquals("tfmc_drinks:item/ale", textures.get("particle").getAsString());
        assertEquals("minecraft:item/generated", read(models.resolve("ale.json")).get("parent").getAsString());
        byte[] png = Files.readAllBytes(empty);
        assertEquals(0x89, png[0] & 0xff);
        assertEquals("PNG", new String(png, 1, 3));
        try (var listed = Files.list(models)) {
            assertEquals(2, listed.count());
        }

        assertFalse(DrinkBottleModels.sync(plugin));
    }

    @Test void removedDrinksLoseTheirModelAndOverride() throws Exception {
        DrinkBottleModels.sync(plugin);
        Files.writeString(models.resolve("notes.txt"), "kept");
        drinks("mead");

        assertTrue(DrinkBottleModels.sync(plugin));

        assertEquals(List.of(20000), overrideCmds());
        assertFalse(Files.exists(models.resolve("ale.json")));
        assertTrue(Files.exists(models.resolve("mead.json")));
        assertTrue(Files.exists(models.resolve("notes.txt")));
    }

    @Test void noTexturedDrinksRemovesTheGeneratedFiles() throws Exception {
        DrinkBottleModels.sync(plugin);
        Files.writeString(items, "info:\n  namespace: tfmc_drinks\n");

        assertTrue(DrinkBottleModels.sync(plugin));

        assertFalse(Files.exists(potion));
        assertFalse(Files.exists(empty));
        assertFalse(Files.exists(models.resolve("ale.json")));
        assertFalse(DrinkBottleModels.sync(plugin));
    }

    @Test void missingItemsYmlKeepsTheGeneratedFiles() throws Exception {
        DrinkBottleModels.sync(plugin);
        byte[] before = Files.readAllBytes(potion);
        Files.delete(items);

        assertFalse(DrinkBottleModels.sync(plugin));

        assertArrayEquals(before, Files.readAllBytes(potion));
        assertTrue(Files.exists(empty));
        assertTrue(Files.exists(models.resolve("ale.json")));
    }

    @Test void unreadableInputsLeaveExistingFilesAlone() throws Exception {
        DrinkBottleModels.sync(plugin);
        byte[] before = Files.readAllBytes(potion);

        Files.writeString(items, "items: [unclosed\n");
        assertThrows(IOException.class, () -> DrinkBottleModels.sync(plugin));
        Files.delete(cache);
        IOException missing = assertThrows(IOException.class, () -> DrinkBottleModels.sync(plugin));

        assertTrue(missing.getMessage().contains("CMD cache missing"));
        assertArrayEquals(before, Files.readAllBytes(potion));
    }

    @Test void readsOnlyThisNamespacesPotionCmds() throws Exception {
        assertEquals(Map.of("ale", 20001, "mead", 20000, "no_texture", 20003, "retired", 20004),
            DrinkBottleModels.potionCmds(cache, NS));
        assertEquals(Map.of("ale", 20002), DrinkBottleModels.potionCmds(cache, "other"));
    }

    @Test void quietSyncLogsFailuresInsteadOfThrowing() throws Exception {
        assertTrue(DrinkBottleModels.syncQuietly(plugin, log));
        Files.delete(cache);

        assertFalse(DrinkBottleModels.syncQuietly(plugin, log));
        assertFalse(DrinkBottleModels.syncQuietly(plugin, null));
        namespace.when(DrinksNamespace::current).thenThrow(new IllegalStateException("no realm"));
        assertFalse(DrinkBottleModels.syncQuietly(plugin, log));

        verify(log).warning(contains("CMD cache missing"));
        verify(log).warning("[ia] bottle models not updated: no realm");
    }

    @Test void scaffoldReportsOnlyWhenBottleModelsChange() {
        IaDrinksScaffold.ensure(plugin);
        IaDrinksScaffold.ensure(plugin);

        verify(log, times(1)).info("[ia] updated tfmc_drinks bottle models; players get them after the next iazip");
    }

    @Test void removingADrinkAlsoRemovesItsBottleModel() throws Exception {
        DrinkBottleModels.sync(plugin);

        assertTrue(IaDrinksRemover.remove(plugin, "tfmc_drinks:ale", log));
        assertFalse(Files.exists(models.resolve("ale.json")));
        assertEquals(List.of(20000), overrideCmds());

        Files.writeString(models.resolve("leftover.json"), "{}");
        assertTrue(IaDrinksRemover.remove(plugin, "tfmc_drinks:ale", log));
        assertFalse(Files.exists(models.resolve("leftover.json")));
        assertFalse(IaDrinksRemover.remove(plugin, "tfmc_drinks:ale", log));
    }

    private void drinks(String... ids) throws IOException {
        StringBuilder yaml = new StringBuilder("info:\n  namespace: tfmc_drinks\nitems:\n");
        for (String id : ids) {
            yaml.append("  ").append(id).append(":\n    name: ").append(id).append('\n');
        }
        Files.createDirectories(items.getParent());
        Files.writeString(items, yaml);
    }

    private void texture(String id) throws IOException {
        Path png = root.resolve("resourcepack/tfmc_drinks/textures/item/" + id + ".png");
        Files.createDirectories(png.getParent());
        Files.write(png, new byte[]{1});
    }

    private com.google.gson.JsonArray overrides() throws IOException {
        return read(potion).getAsJsonArray("overrides");
    }

    private List<Integer> overrideCmds() throws IOException {
        return overrides().asList().stream()
            .map(o -> o.getAsJsonObject().getAsJsonObject("predicate").get("custom_model_data").getAsInt())
            .toList();
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
