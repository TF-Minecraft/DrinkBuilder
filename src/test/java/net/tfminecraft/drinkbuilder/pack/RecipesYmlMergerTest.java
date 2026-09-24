package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class RecipesYmlMergerTest {

	@Test
	void mapsNamesAndNumbersToBreweryCodes() throws IOException {
		assertEquals(0, RecipesYmlMerger.woodCode("any"));
		assertEquals(1, RecipesYmlMerger.woodCode("birch"));
		assertEquals(2, RecipesYmlMerger.woodCode("oak"));
		assertEquals(2, RecipesYmlMerger.woodCode("Oak"));
		assertEquals(6, RecipesYmlMerger.woodCode("dark oak"));
		assertEquals(12, RecipesYmlMerger.woodCode("cut_copper"));
		assertEquals(13, RecipesYmlMerger.woodCode("pale_oak"));
		assertEquals(13, RecipesYmlMerger.woodCode("Pale Oak"));
		assertEquals(0, RecipesYmlMerger.woodCode(0));
		assertEquals(0, RecipesYmlMerger.woodCode(0.0d));
		assertEquals(0, RecipesYmlMerger.woodCode("0.0"));
		assertEquals(4, RecipesYmlMerger.woodCode("4"));
		assertEquals(4, RecipesYmlMerger.woodCode(4.0d));
		assertNull(RecipesYmlMerger.woodCode(null));
		assertNull(RecipesYmlMerger.woodCode(""));
		assertNull(RecipesYmlMerger.woodCode("   "));
	}

	@Test
	void rejectsUnknownWood() {
		assertThrows(IOException.class, () -> RecipesYmlMerger.woodCode("mahogany"));
		assertThrows(IOException.class, () -> RecipesYmlMerger.woodCode(14));
		assertThrows(IOException.class, () -> RecipesYmlMerger.woodCode(1.5d));
	}
}
