package net.tfminecraft.drinkbuilder.catalog;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import dev.lone.itemsadder.api.CustomStack;
import net.Indyuce.mmoitems.MMOItems;
import net.tfminecraft.drinkbuilder.*;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.*;

class CatalogServicesTest {
    @TempDir Path dir;
    DrinkBuilder plugin;
    Logger log;
    final Map<java.lang.reflect.Field,Object> saved = new HashMap<>();
    DrinkBuilder prior;
    @BeforeEach void setup() throws Exception {
        for (var f : Cache.class.getFields()) saved.put(f, f.get(null));
        prior = DrinkBuilder.plugin;
        plugin = mock(DrinkBuilder.class); log = mock(Logger.class);
        when(plugin.getDataFolder()).thenReturn(dir.toFile());
        when(plugin.getLogger()).thenReturn(log);
        Cache.ingredients = List.of(); Cache.categories = Map.of(); Cache.effectsBlacklist = List.of();
    }
    @AfterEach void restore() throws Exception {
        for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
        DrinkBuilder.plugin = prior; MMOItems.plugin = null;
    }
    Cache.Ingredient ingredient(String type, String token) {
        return new Cache.Ingredient("id",type,token,"label","other");
    }
    @Test void payloadFiltersMissingIngredientsAndEscapesJson() {
        var first = new Cache.Ingredient("apple","vanilla","APPLE","Apple\n\r\t\"\\","fruit");
        var second = ingredient("", "SUGAR");
        Cache.ingredients = Arrays.asList(null,ingredient("", ""),new Cache.Ingredient("", "", "APPLE", "", ""), first,second);
        var categories = new LinkedHashMap<String,String>();
        categories.put(null,"skip"); categories.put(" ","skip"); categories.put("fruit","Fruit"); categories.put("null",null); categories.put("blank"," ");
        Cache.categories = categories;
        Cache.effectsBlacklist = Arrays.asList(null," "," SPEED ","POISON");
        Cache.catalogVersion = 7;
        try (var exists = mockStatic(IngredientExistenceChecker.class)) {
            exists.when(() -> IngredientExistenceChecker.existsInGame(first)).thenReturn(true);
            exists.when(() -> IngredientExistenceChecker.existsInGame(second)).thenReturn(true);
            var payload = CatalogSyncService.buildPayload(log);
            var json = JsonParser.parseString(payload.json).getAsJsonObject();
            assertEquals(2, payload.ingredientCount);
            assertEquals(2, json.getAsJsonArray("ingredients").size());
            assertEquals(first.label, json.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("label").getAsString());
            assertFalse(json.getAsJsonArray("ingredients").get(1).getAsJsonObject().has("type"));
            assertEquals("null", json.getAsJsonObject("categories").get("null").getAsString());
            assertEquals("blank", json.getAsJsonObject("categories").get("blank").getAsString());
            assertEquals("speed", json.getAsJsonArray("effects_blacklist").get(0).getAsString());
            assertEquals(7,json.get("version").getAsInt());
        }
        Cache.ingredients = null; Cache.categories = null; Cache.effectsBlacklist = null;
        assertEquals(0, CatalogSyncService.buildPayload().ingredientCount);
        IngredientExistenceChecker.logSkip(null,first);
        IngredientExistenceChecker.logSkip(log,null);
        IngredientExistenceChecker.logSkip(log,first);
        verify(log).info(contains("skipping missing ingredient apple"));
    }
    @Test void catalogSchedulingAndCallbacksReportBothOutcomes() {
        var scheduler = mock(BukkitScheduler.class);
        doAnswer(inv -> { inv.getArgument(1,Runnable.class).run(); return null; }).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        doAnswer(inv -> { inv.getArgument(1,Runnable.class).run(); return null; }).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));
        try (var bukkit = mockStatic(Bukkit.class); var api = mockStatic(ProvinceSystemClient.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            assertFalse(CatalogSyncService.pushNow(null).ok);
            CatalogSyncService.pushAsync(null); DrinkBuilder.plugin = null; CatalogSyncService.pushAsyncFromPlugin();
            var success = CatalogPushResult.success(2,"today");
            api.when(() -> ProvinceSystemClient.pushCatalog(anyString(),anyInt())).thenReturn(success);
            assertSame(success, CatalogSyncService.pushNow());
            var callback = new AtomicReference<CatalogPushResult>();
            CatalogSyncService.pushAsync(plugin,callback::set);
            assertSame(success,callback.get());
            CatalogSyncService.pushAsync(plugin);
            verify(log).info(contains("updated_at=today"));
            api.when(() -> ProvinceSystemClient.pushCatalog(anyString(),anyInt())).thenReturn(CatalogPushResult.success(0,null));
            DrinkBuilder.plugin = plugin; CatalogSyncService.pushAsyncFromPlugin();
            verify(log).info("[catalog] synced to ProvinceSystem: ingredients=0");
            api.when(() -> ProvinceSystemClient.pushCatalog(anyString(),anyInt())).thenReturn(CatalogPushResult.fail("offline"));
            CatalogSyncService.pushAsync(plugin);
            verify(log).warning(contains("sync failed: offline"));
        }
    }
    @Test void assetSyncValidatesBytesAndReportsPartialOrCompleteSuccess() throws Exception {
        var scheduler = mock(BukkitScheduler.class);
        doAnswer(inv -> { inv.getArgument(1,Runnable.class).run(); return null; }).when(scheduler).runTaskAsynchronously(eq(plugin),any(Runnable.class));
        var assets = Files.createDirectories(dir.resolve("assets"));
        byte[] png = {(byte)0x89,0x50,0x4e,0x47,0,0,0,0};
        try (var bukkit = mockStatic(Bukkit.class); var api = mockStatic(ProvinceSystemClient.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            api.when(() -> ProvinceSystemClient.putDrinkAsset(anyString(),any())).thenReturn(SimpleResult.success("ok"));
            AssetSyncService.pushAsync(null); DrinkBuilder.plugin = null; AssetSyncService.pushAsyncFromPlugin();
            AssetSyncService.pushAsync(plugin);
            verify(log).warning(contains("missing" + " " + assets.resolve(AssetSyncService.OVERLAY)));
            Files.write(assets.resolve(AssetSyncService.BOTTLE),png);
            Files.write(assets.resolve(AssetSyncService.OVERLAY),new byte[0]);
            AssetSyncService.pushAsync(plugin);
            verify(log).warning("[assets] synced 1/2 potion assets");
            var invalid = new ArrayList<byte[]>(); invalid.add(new byte[4]);
            for (int i=0; i<4; i++) { var copy=png.clone();copy[i]=0;invalid.add(copy); }
            for (var bytes:invalid) { Files.write(assets.resolve(AssetSyncService.OVERLAY),bytes); AssetSyncService.pushAsync(plugin); }
            verify(log, times(5)).warning("[assets] sync failed for potion_overlay.png: not a PNG");
            Files.write(assets.resolve(AssetSyncService.OVERLAY),png);
            DrinkBuilder.plugin = plugin; AssetSyncService.pushAsyncFromPlugin();
            verify(log).info(contains("synced potion_overlay.png + glass_bottle.png"));
            api.when(() -> ProvinceSystemClient.putDrinkAsset(anyString(),any())).thenThrow(new IllegalStateException("broken"));
            AssetSyncService.pushAsync(plugin);
            verify(log).warning("[assets] sync failed for potion_overlay.png: broken");
        }
    }
    @Test void ingredientChecksRejectMissingAndResolveVanilla() {
        assertFalse(IngredientExistenceChecker.existsInGame(null));
        assertFalse(IngredientExistenceChecker.existsInGame(ingredient(null,null)));
        assertFalse(IngredientExistenceChecker.existsInGame(ingredient("vanilla","missing-material")));
        // Material.isItem is a server registry operation on modern Paper.
        try (var materials = mockStatic(Material.class)) {
            var material = mock(Material.class);
            materials.when(() -> Material.matchMaterial("APPLE")).thenReturn(material);
            when(material.isItem()).thenReturn(true);
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("vanilla","APPLE")));
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("unknown","APPLE")));
            when(material.isItem()).thenReturn(false);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("vanilla","APPLE")));
            materials.when(() -> Material.matchMaterial("A B")).thenReturn(material);
            when(material.isItem()).thenReturn(true);
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("vanilla","A_B")));
            materials.when(() -> Material.valueOf("LAST")).thenReturn(material);
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("vanilla","LAST")));
        }
    }
    @Test void itemsAdderChecksPresenceItemAndFailures() {
        var manager = mock(PluginManager.class);
        try (var bukkit = mockStatic(Bukkit.class); var stacks = mockStatic(CustomStack.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("itemsadder","food:apple")));
            when(manager.getPlugin("ItemsAdder")).thenReturn(mock(Plugin.class));
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("itemsadder","itemsadder: ")));
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("itemsadder","food:apple")));
            var stack = mock(CustomStack.class); stacks.when(() -> CustomStack.getInstance("food:apple")).thenReturn(stack);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("", "itemsadder:food:apple")));
            var item = mock(ItemStack.class); when(stack.getItemStack()).thenReturn(item);
            when(item.getType()).thenReturn(Material.AIR);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("itemsadder","food:apple")));
            when(item.getType()).thenReturn(Material.APPLE);
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("itemsadder","food:apple")));
            stacks.when(() -> CustomStack.getInstance("food:apple")).thenThrow(new LinkageError("unavailable"));
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("itemsadder","food:apple")));
        }
    }
    @Test void mmoItemsReflectionChecksManagersTypesAndCaseFallback() {
        var manager = mock(PluginManager.class); var mmo = mock(Plugin.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(manager);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            when(manager.getPlugin("MMOItems")).thenReturn(mmo);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            when(mmo.isEnabled()).thenReturn(true);
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","MMOItems: ")));
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            var bridge = new MMOItems(); MMOItems.plugin=bridge;
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            bridge.items.existing="APPLE";
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("", "mmoitems:apple")));
            assertTrue(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","APPLE")));
            bridge.items.fail=true;
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","APPLE")));
            bridge.items=null;
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            bridge.items=new MMOItems.Items(); bridge.types=null;
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            bridge.types=new MMOItems.Types(); bridge.types.all="invalid";
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
            bridge.types.all=List.of();
            assertFalse(IngredientExistenceChecker.existsInGame(ingredient("mmoitems","apple")));
        }
    }
}
