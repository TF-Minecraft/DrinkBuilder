package net.tfminecraft.drinkbuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.drinkbuilder.loaders.*;
import net.tfminecraft.drinkbuilder.objects.PermissionGroupDefinition;
import net.tfminecraft.drinkbuilder.entitlements.PermissionGroupService;

class ConfigurationTest {
    @TempDir Path dir;
    private final Map<java.lang.reflect.Field, Object> saved = new HashMap<>();
    @BeforeEach void saveCache() throws Exception {
        for (var f : Cache.class.getFields()) saved.put(f, f.get(null));
    }
    @AfterEach void restoreCache() throws Exception {
        for (var e : saved.entrySet()) e.getKey().set(null, e.getValue());
    }
    private java.io.File yaml(String content) throws Exception {
        return Files.writeString(dir.resolve("input.yml"), content).toFile();
    }
    @Test void configDefaultsAndNormalizedBounds() throws Exception {
        var loader = new ConfigLoader();
        loader.load(yaml("{}"));
        assertEquals("plugins/BreweryX", Cache.breweryxFolder);
        assertEquals(20000, Cache.cmdMin);
        assertEquals(29999, Cache.cmdMax);
        assertEquals(8, Cache.iaReloadDelaySeconds);
        assertEquals(120, Cache.packPollIntervalSeconds);
        assertEquals("06:00", Cache.forceReloadTime);
        loader.load(yaml("""
            paths:
              breweryx-folder: ' brewery '
              itemsadder-tfmc-drinks: ' items '
            cmd: {min: 30, max: 10}
            ia-reload-delay-seconds: -4
            pack-apply: {poll-interval-seconds: -1, force-reload-time: ' 12:30 '}
            """));
        assertEquals("brewery", Cache.breweryxFolder);
        assertEquals("items", Cache.itemsAdderTfmcDrinks);
        assertEquals(10, Cache.cmdMin);
        assertEquals(30, Cache.cmdMax);
        assertEquals(0, Cache.iaReloadDelaySeconds);
        assertEquals(0, Cache.packPollIntervalSeconds);
        assertEquals("12:30", Cache.forceReloadTime);
        loader.load(dir.resolve("missing").toFile());
        assertEquals(10, Cache.cmdMin, "Unreadable config preserves live settings");
    }
    @Test void ingredientsAndBlacklistNormalizeAndRejectInvalidEntries() throws Exception {
        var loader = new IngredientsLoader();
        loader.loadIngredients(yaml("""
            ingredients:
              - {id: ' APPLE ', type: ' VANILLA ', brewery_token: ' APPLE ', label: ' Apple ', category: ' Fruit '}
              - {id: sugar}
              - {id: ''}
              - not-a-map
              - null
            """));
        assertEquals(2, Cache.ingredients.size());
        var apple = Cache.ingredients.getFirst();
        assertEquals("apple", apple.id);
        assertEquals("vanilla", apple.type);
        assertEquals("APPLE", apple.breweryToken);
        assertEquals("Apple", apple.label);
        assertEquals("Fruit", apple.category);
        assertEquals("other", Cache.ingredients.get(1).category);
        assertThrows(UnsupportedOperationException.class, () -> Cache.ingredients.clear());
        loader.loadEffectsBlacklist(yaml("effects: [ SPEED, ' speed ', null, '', POISON, 12]"));
        assertEquals(List.of("speed", "poison", "12"), Cache.effectsBlacklist);
        loader.loadEffectsBlacklist(yaml("effects-blacklist: [JUMP]"));
        assertEquals(List.of("jump"), Cache.effectsBlacklist);
        loader.loadEffectsBlacklist(yaml("{}"));
        assertEquals(List.of(), Cache.effectsBlacklist);
        loader.loadIngredients(yaml("{}"));
        assertEquals(List.of(), Cache.ingredients);
        loader.loadIngredients(dir.resolve("absent").toFile());
        loader.loadEffectsBlacklist(dir.resolve("absent").toFile());
        assertEquals(List.of(), Cache.ingredients);
        assertEquals(List.of(), Cache.effectsBlacklist);
        var empty = new Cache.Ingredient(null, null, null, null, null);
        assertEquals("", empty.id);
        assertEquals("", empty.type);
        assertEquals("", empty.breweryToken);
        assertEquals("", empty.label);
        assertEquals("other", empty.category);
        var blank = new Cache.Ingredient(" id ", "", "", "  ", "  ");
        assertEquals("id", blank.label);
        assertEquals("other", blank.category);
        assertTrue(Cache.newStringList().isEmpty());
        assertTrue(Cache.newIngredientList().isEmpty());
    }
    @Test void sectionIngredientIsAccepted() throws Exception {
        var method = IngredientsLoader.class.getDeclaredMethod("parseIngredient", Object.class);
        method.setAccessible(true);
        var section = new YamlConfiguration();
        section.set("id", "PEAR");
        var result = (Cache.Ingredient) method.invoke(new IngredientsLoader(), section);
        assertEquals("pear", result.id);
        assertEquals("other", result.category);
    }
    @Test void categoriesNormalizeAndFallBackToIds() throws Exception {
        var loader = new CategoriesLoader();
        loader.load(yaml("categories: {' FRUIT ': ' Fruit ', blank: ' ', ' ': ignored, count: 3}"));
        assertEquals(Map.of("fruit", "Fruit", "blank", "blank", "count", "3"), Cache.categories);
        assertThrows(UnsupportedOperationException.class, () -> Cache.categories.clear());
        loader.load(yaml("{}"));
        assertTrue(Cache.categories.isEmpty());
        loader.load(dir.resolve("absent").toFile());
        assertTrue(Cache.categories.isEmpty());
    }
    @Test void permissionGroupsSortAndApplyDefaults() throws Exception {
        var loader = new PermissionGroupsLoader();
        loader.load(yaml("""
            defaults: {name-colour-stops: 2, allow-drink-texture: true}
            groups:
              ignored: text
              missing: {}
              blank: {permission: ' '}
              donor: {permission: ' drinks.donor ', tier: 10, name-colour-stops: -1, allow-drink-texture: false}
              member: {permission: drinks.member}
            """));
        assertEquals(2, Cache.permissionGroups.size());
        var member = Cache.permissionGroups.getFirst();
        assertEquals("member", member.getId());
        assertEquals(0, member.getTier());
        assertEquals(2, member.getNameColourStops());
        assertTrue(member.isAllowDrinkTexture());
        assertFalse(member.hasNameColourStops());
        assertFalse(member.hasAllowDrinkTexture());
        var donor = Cache.permissionGroups.getLast();
        assertEquals("drinks.donor", donor.getPermission());
        assertEquals(0, donor.getNameColourStops());
        assertTrue(donor.hasNameColourStops());
        assertTrue(donor.hasAllowDrinkTexture());
        assertFalse(donor.isAllowDrinkTexture());
        loader.load(yaml("{}"));
        assertEquals(0, Cache.defaultNameColourStops);
        assertFalse(Cache.defaultAllowDrinkTexture);
        assertTrue(Cache.permissionGroups.isEmpty());
        loader.load(yaml("defaults: {name-colour-stops: -3, allow-drink-texture: false}"));
        assertEquals(0, Cache.defaultNameColourStops);
        loader.load(dir.resolve("absent").toFile());
        assertTrue(Cache.permissionGroups.isEmpty());
        assertFalse(Cache.defaultAllowDrinkTexture);
    }
    @Test void entitlementsCombineOnlyGrantedPermissions() {
        var player = mock(Player.class);
        Cache.defaultNameColourStops = 2;
        Cache.defaultAllowDrinkTexture = false;
        var empty = new PermissionGroupDefinition(null, null, 0, 0, false, false, false);
        assertEquals("", empty.getId());
        assertEquals("", empty.getPermission());
        Cache.permissionGroups = Arrays.asList(null, empty,
            new PermissionGroupDefinition("blocked", "blocked", 0, 99, true, true, true),
            new PermissionGroupDefinition("inherit", "inherit", 0, 99, true, false, false),
            new PermissionGroupDefinition("low", "low", 0, 1, false, true, true),
            new PermissionGroupDefinition("high", "high", 0, 4, true, true, true));
        when(player.hasPermission("inherit")).thenReturn(true);
        when(player.hasPermission("low")).thenReturn(true);
        assertEquals(2, PermissionGroupService.getNameColourStops(null));
        assertFalse(PermissionGroupService.getAllowDrinkTexture(null));
        assertEquals(2, PermissionGroupService.getNameColourStops(player));
        assertFalse(PermissionGroupService.getAllowDrinkTexture(player));
        when(player.hasPermission("high")).thenReturn(true);
        assertEquals(4, PermissionGroupService.getNameColourStops(player));
        assertTrue(PermissionGroupService.getAllowDrinkTexture(player));
        Cache.defaultAllowDrinkTexture = true;
        assertTrue(PermissionGroupService.getAllowDrinkTexture(player));
    }
}
