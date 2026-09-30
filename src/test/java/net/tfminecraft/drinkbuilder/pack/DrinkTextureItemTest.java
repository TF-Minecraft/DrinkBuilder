package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DrinkTextureItemTest {

	@Test
	void textureItemDoesNotUsePotionTint() {
		YamlConfiguration yaml = new YamlConfiguration();
		ConfigurationSection item = yaml.createSection("drink");
		item.set("display_name", "old");
		item.set("resource.material", "POTION");
		item.set("resource.model_id", 20000);
		item.set("specific_properties.potion.color", "BLUE");

		DrinkTextureItem.apply(item, "Stonebrook Whiskey", "item/mrenzo99_stonebrook_whiskey");

		assertEquals("Stonebrook Whiskey", item.getString("name"));
		assertEquals("POTION", item.getString("material"));
		assertEquals("item/mrenzo99_stonebrook_whiskey", item.getString("graphics.texture"));
		assertEquals("WHITE", item.getString("graphics.color"));
		assertNull(item.getString("display_name"));
		assertFalse(item.isConfigurationSection("resource"));
		assertFalse(item.isConfigurationSection("specific_properties"));
	}

	@Test
	void pinsExistingPotionCmdWithoutRewritingOtherIds(@TempDir Path dir) throws Exception {
		Path cache = dir.resolve("items_ids_cache.yml");
		Files.writeString(cache, """
			PAPER:
			  other:icon: 10000
			POTION:
			  tfmc_drinks:kept: 20010
			  tfmc_drinks:drink: 19999
			IRON_SWORD:
			  other:sword: 10005
			""", StandardCharsets.UTF_8);

		DrinkTextureItem.pinPotionCmd(cache, "tfmc_drinks:drink", 20001);

		String text = Files.readString(cache);
		assertEquals("""
			PAPER:
			  other:icon: 10000
			POTION:
			  tfmc_drinks:kept: 20010
			  tfmc_drinks:drink: 20001
			IRON_SWORD:
			  other:sword: 10005
			""", text.replace("\r\n", "\n"));
	}

	@Test
	void insertsMissingPotionCmdBeforeNextMaterial(@TempDir Path dir) throws Exception {
		Path cache = dir.resolve("items_ids_cache.yml");
		Files.writeString(cache, """
			POTION:
			  tfmc_drinks:kept: 20010
			IRON_SWORD:
			  other:sword: 10005
			""", StandardCharsets.UTF_8);

		DrinkTextureItem.pinPotionCmd(cache, "tfmc_drinks:new_drink", 20011);

		String text = Files.readString(cache);
		assertEquals("""
			POTION:
			  tfmc_drinks:kept: 20010
			  tfmc_drinks:new_drink: 20011
			IRON_SWORD:
			  other:sword: 10005
			""", text.replace("\r\n", "\n"));
	}

    @Test
    void rejectsInvalidIdsAndMissingPotionCache(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache.yml");
        for (String id : java.util.Arrays.asList(null, "", " ", "no_namespace")) {
            assertThrows(java.io.IOException.class, () -> DrinkTextureItem.pinPotionCmd(cache, id, 1));
        }
        assertThrows(java.io.IOException.class, () -> DrinkTextureItem.pinPotionCmd(cache, "ns:id", 1));
        Files.writeString(cache, "PAPER:\n  ns:id: 1\n");
        assertThrows(java.io.IOException.class, () -> DrinkTextureItem.pinPotionCmd(cache, "ns:id", 1));
    }

    @Test
    void preservesMatchingCacheAndAppendsAtEnd(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache.yml");
        String content = "POTION:\n\n\tns:old: 1\n";
        Files.writeString(cache, content);
        DrinkTextureItem.pinPotionCmd(cache, "ns:old", 1);
        assertEquals(content, Files.readString(cache));
        DrinkTextureItem.pinPotionCmd(cache, " ns:new ", 2);
        assertEquals(content + "  ns:new: 2\n", Files.readString(cache).replace("\r\n", "\n"));
    }
}
