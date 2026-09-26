package net.tfminecraft.drinkbuilder.api;

import static net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.tfminecraft.drinkbuilder.Cache;

class ProvinceSystemClientTest {
	@Test
	void catalogUsesExplicitOrCachedIngredientCountAndPropagatesGatewayErrors() {
		var saved = Cache.ingredients;
		try (var gateway = mockStatic(GatewayClient.class)) {
			gateway.when(() -> GatewayClient.request("PUT", "/drinks/plugin/catalog", "{}"))
				.thenReturn(GatewayClient.Result.success("{\"updated_at\":\"2026-09-26\"}"));
			var result = pushCatalog("{}", 7);
			assertTrue(result.ok);
			assertEquals(7, result.ingredients);
			assertEquals("2026-09-26", result.updatedAt);
			assertNull(result.error);
			Cache.ingredients = List.of(new Cache.Ingredient("a", "b", "c", "d", "e"));
			assertEquals(1, pushCatalog("{}").ingredients);
			Cache.ingredients = null;
			assertEquals(0, pushCatalog("{}").ingredients);
			gateway.when(() -> GatewayClient.request("PUT", "/drinks/plugin/catalog", "{}"))
				.thenReturn(GatewayClient.Result.success("{}"), GatewayClient.Result.fail("offline"));
			assertNull(pushCatalog("{}", 2).updatedAt);
			result = pushCatalog("{}", 2);
			assertFalse(result.ok);
			assertEquals("offline", result.error);
			assertEquals(0, result.ingredients);
			assertNull(result.updatedAt);
		} finally {
			Cache.ingredients = saved;
		}
	}

	@Test
	void invalidUploadsDoNotContactGateway() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			assertEquals("Payload is empty.", pushCatalog(null, 1).error);
			assertEquals("Payload is empty.", pushCatalog("  ", 1).error);
			for (String name : Arrays.asList(null, "", "  ")) {
				assertEquals("asset name and PNG bytes required", putDrinkAsset(name, new byte[]{1}).error);
			}
			assertFalse(putDrinkAsset("a.png", null).ok);
			assertFalse(putDrinkAsset("a.png", new byte[0]).ok);
			gateway.verifyNoInteractions();
		}
	}

	@Test
	void uploadsTrimNamesAndSendPngBytes() {
		byte[] png = {1, 2, 3};
		try (var gateway = mockStatic(GatewayClient.class)) {
			gateway.when(() -> GatewayClient.requestBytes("PUT", "/drinks/plugin/assets/a.png", png, "image/png"))
				.thenReturn(GatewayClient.Result.success("uploaded"), GatewayClient.Result.fail("denied"));
			var result = putDrinkAsset(" a.png ", png);
			assertTrue(result.ok);
			assertEquals("uploaded", result.body);
			assertNull(result.error);
			result = putDrinkAsset("a.png", png);
			assertFalse(result.ok);
			assertEquals("denied", result.error);
			assertNull(result.body);
		}
	}

	@Test
	void downloadsValidateNamesAndUrlEncodeTheBasename() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			for (String id : Arrays.asList(null, "", " ")) {
				assertEquals("submission id and filename are required", downloadSubmissionFile(id, "a.png").error);
			}
			for (String name : Arrays.asList(null, "", " ")) {
				assertFalse(downloadSubmissionFile("drink", name).ok);
			}
			for (String name : List.of("../a.png", "a/b.png", "a\\b.png")) {
				assertEquals("invalid filename", downloadSubmissionFile("drink", name).error);
			}
			gateway.verifyNoInteractions();
			byte[] expected = {1, 2};
			gateway.when(() -> GatewayClient.download("/drinks/plugin/submissions/drink/files/a%20%2B.png"))
				.thenReturn(GatewayClient.BytesDownload.success(expected),
					GatewayClient.BytesDownload.fail("missing"),
					GatewayClient.BytesDownload.success(null), GatewayClient.BytesDownload.success(new byte[0]));
			var result = downloadSubmissionFile(" drink ", " a +.png ");
			assertTrue(result.ok);
			assertArrayEquals(expected, result.data);
			assertNull(result.error);
			result = downloadSubmissionFile("drink", "a +.png");
			assertFalse(result.ok);
			assertNull(result.data);
			assertEquals("missing", result.error);
			assertEquals("Empty file download", downloadSubmissionFile("drink", "a +.png").error);
			assertEquals("Empty file download", downloadSubmissionFile("drink", "a +.png").error);
		}
	}

	@Test
	void textureAssignmentsValidateAndEscapeTheirPayload() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			assertEquals("texture_id is required", assignTextureCmd(null, 12, "item").error);
			assertFalse(assignTextureCmd(" ", 12, "item").ok);
			assertEquals("ia_item_id is required", assignTextureCmd("tex", 12, null).error);
			assertFalse(assignTextureCmd("tex", 12, " ").ok);
			gateway.verifyNoInteractions();
			String body = "{\"cmd\":12,\"ia_item_id\":\"ns:a\\\"b\\\\c\\nd\\re\"}";
			gateway.when(() -> GatewayClient.request("POST", "/drinks/plugin/textures/tex/cmd", body))
				.thenReturn(GatewayClient.Result.success("assigned"));
			assertEquals("assigned", assignTextureCmd(" tex ", 12, " ns:a\"b\\c\nd\re ").body);
			gateway.verify(() -> GatewayClient.request("POST", "/drinks/plugin/textures/tex/cmd", body));
		}
	}

	@Test
	void appliedFiltersEmptyIdsAndParsesPrimitiveResults() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			assertTrue(markApplied(null).applied.isEmpty());
			assertTrue(markApplied(List.of()).ok);
			assertTrue(markApplied(Arrays.asList(null, " ", "")).applied.isEmpty());
			gateway.verifyNoInteractions();
			String body = "{\"submission_ids\":[\"one\",\"t\\\"wo\"]}";
			gateway.when(() -> GatewayClient.request("POST", "/drinks/plugin/applied", body))
				.thenReturn(GatewayClient.Result.success("{\"applied\":[\"one\",null,{},[],2,true]}"),
					GatewayClient.Result.fail("conflict"));
			var result = markApplied(Arrays.asList(null, " one ", " ", "t\"wo"));
			assertTrue(result.ok);
			assertNull(result.error);
			assertEquals(List.of("one", "2", "true"), result.applied);
			assertThrows(UnsupportedOperationException.class, () -> result.applied.add("x"));
			var failed = markApplied(List.of("one", "t\"wo"));
			assertFalse(failed.ok);
			assertEquals("conflict", failed.error);
			assertTrue(failed.applied.isEmpty());
		}
	}

	@Test
	void appliedTreatsAbsentOrMalformedArraysAsEmpty() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			for (String body : List.of("", " ", "{}", "{\"applied\":null}", "{", "{\"applied\":false}")) {
				gateway.when(() -> GatewayClient.request("POST", "/drinks/plugin/applied", "{\"submission_ids\":[\"one\"]}"))
					.thenReturn(GatewayClient.Result.success(body));
				assertTrue(markApplied(List.of("one")).applied.isEmpty(), body);
			}
		}
	}

	@Test
	void pendingListReportsTransportAndParsingErrors() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			gateway.when(() -> GatewayClient.request("GET", "/drinks/plugin/pending-apply", null))
				.thenReturn(GatewayClient.Result.fail("offline"), GatewayClient.Result.success("{"),
					GatewayClient.Result.success("{\"submissions\":[{\"id\":\"one\"}]}"));
			assertEquals("offline", listPendingApply().error);
			assertTrue(listPendingApply().error.startsWith("Bad pending-apply payload: "));
			var result = listPendingApply();
			assertTrue(result.ok);
			assertNull(result.error);
			assertEquals("one", result.submissions.getFirst().id);
			assertThrows(UnsupportedOperationException.class, () -> result.submissions.clear());
		}
	}

	@Test
	void pendingParserHandlesMissingFieldsAndSkipsInvalidEntries() {
		assertTrue(parsePendingDrinks(null).isEmpty());
		assertTrue(parsePendingDrinks(" ").isEmpty());
		assertTrue(parsePendingDrinks("{}").isEmpty());
		var drinks = parsePendingDrinks("""
			{"submissions":[null,2,[],{}, {"id":null},{"id":" "},{"id":{}},
			{"id":"one","recipe":null,"files":{},"texture":null,"new_texture":null},
			{"id":"two"}]}
			""");
		assertEquals(2, drinks.size());
		for (var drink : drinks) {
			assertNull(drink.playerUuid);
			assertNull(drink.slug);
			assertNull(drink.displayName);
			assertNull(drink.status);
			assertNull(drink.textureId);
			assertTrue(drink.recipe.isEmpty());
			assertTrue(drink.files.isEmpty());
			assertNull(drink.texture);
			assertFalse(drink.newTexture);
		}
	}

	@Test
	void pendingParserPreservesRecipeFilesAndTextureMetadata() {
		var drinks = parsePendingDrinks("""
			{"submissions":[{"id":"one","player_uuid":"uuid","slug":"ale","display_name":"Ale",
			"status":"approved","new_texture":true,"texture_id":"tex","recipe":{"name":"Ale","time":5},
			"files":["a.png",null,{},[],42],
			"texture":{"id":"tex","cmd":21000,"ia_item_id":"ns:ale","png_path":"ale.png"}},
			{"id":"two","new_texture":false,"texture":{"cmd":null}},
			{"id":"three","new_texture":1,"texture":{}},
			{"id":"four","new_texture":0},
			{"id":"five","new_texture":"true"},
			{"id":"six","new_texture":{}}]}
			""");
		var drink = drinks.getFirst();
		assertEquals("uuid", drink.playerUuid);
		assertEquals("ale", drink.slug);
		assertEquals("Ale", drink.displayName);
		assertEquals("approved", drink.status);
		assertEquals("tex", drink.textureId);
		assertEquals(Map.of("name", "Ale", "time", 5.0), drink.recipe);
		assertEquals(List.of("a.png", "42"), drink.files);
		assertEquals("tex", drink.texture.id);
		assertEquals(21000, drink.texture.cmd);
		assertEquals("ns:ale", drink.texture.iaItemId);
		assertEquals("ale.png", drink.texture.pngPath);
		assertTrue(drink.newTexture);
		assertFalse(drinks.get(1).newTexture);
		assertTrue(drinks.get(2).newTexture);
		assertFalse(drinks.get(3).newTexture);
		assertFalse(drinks.get(4).newTexture);
		assertFalse(drinks.get(5).newTexture);
		assertNull(drinks.get(1).existingCmd());
		assertNull(drinks.get(2).existingCmd());
	}

	@Test
	void pendingDrinkDeterminesWhetherItemsAdderNeedsAnEntryAndCopiesFiles() {
		assertFalse(pending(null, null).needsIaWrite());
		assertFalse(pending(" ", null).needsIaWrite());
		assertTrue(pending("tex", null).needsIaWrite());
		assertNull(pending("tex", null).existingCmd());
		assertTrue(pending("tex", new TextureInfo("tex", null, null, null)).needsIaWrite());
		var existing = pending("tex", new TextureInfo("tex", 42, "ns:item", "a.png"));
		assertFalse(existing.needsIaWrite());
		assertEquals(42, existing.existingCmd());
		assertEquals(Map.of(), existing.recipe);
		assertEquals(List.of(), existing.files);
		var files = new ArrayList<>(List.of("a.png"));
		var drink = new PendingDrink("one", null, null, null, null, false, null, Map.of("a", 1), files, null);
		files.add("b.png");
		assertEquals(List.of("a.png"), drink.files);
		assertThrows(UnsupportedOperationException.class, () -> drink.files.clear());
	}

	@Test
	void deletableListSkipsInvalidIdsAndPreservesMetadata() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			gateway.when(() -> GatewayClient.request("GET", "/drinks/plugin/drinks/deletable", null))
				.thenReturn(GatewayClient.Result.success("""
					{"drinks":[null,1,{}, {"id":null},{"id":" "},
					{"id":"one","display_name":"Ale","status":"applied"}]}
					"""));
			var result = listDeletableDrinks();
			assertTrue(result.ok);
			assertNull(result.error);
			assertEquals(List.of("one"), result.ids());
			assertEquals("Ale", result.drinks.getFirst().displayName);
			assertEquals("applied", result.drinks.getFirst().status);
			assertThrows(UnsupportedOperationException.class, () -> result.drinks.clear());
			gateway.when(() -> GatewayClient.request("GET", "/drinks/plugin/drinks/deletable", null))
				.thenReturn(GatewayClient.Result.success("{}"), GatewayClient.Result.success("{"), GatewayClient.Result.fail("offline"));
			assertTrue(listDeletableDrinks().drinks.isEmpty());
			assertTrue(listDeletableDrinks().error.startsWith("Bad deletable payload: "));
			assertEquals("offline", listDeletableDrinks().error);
		}
		var result = DeletableListResult.success(Arrays.asList(null, new DeletableDrink(null, null, null),
			new DeletableDrink(" ", null, null), new DeletableDrink("one", null, null)));
		assertEquals(List.of("one"), result.ids());
	}

	@Test
	void getDrinkValidatesIdAndHandlesMalformedPayloads() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			assertEquals("submission id required", getDrink(null).error);
			assertFalse(getDrink(" ").ok);
			gateway.verifyNoInteractions();
			gateway.when(() -> GatewayClient.request("GET", "/drinks/plugin/drinks/one", null))
				.thenReturn(GatewayClient.Result.fail("missing"), GatewayClient.Result.success("{}"),
					GatewayClient.Result.success("{"), GatewayClient.Result.success("{\"id\":\"one\"}"));
			var missing = getDrink("one");
			assertFalse(missing.ok);
			assertNull(missing.drink);
			assertEquals("missing", missing.error);
			assertEquals("Bad drink payload", getDrink("one").error);
			assertTrue(getDrink("one").error.startsWith("Bad drink payload: "));
			var found = getDrink(" one ");
			assertTrue(found.ok);
			assertNull(found.error);
			assertEquals("one", found.drink.id);
		}
	}

	@Test
	void revokeParsesTextureReleaseMetadataAndMissingOptionalFields() {
		try (var gateway = mockStatic(GatewayClient.class)) {
			assertEquals("submission id required", revokeDrink(null).error);
			assertFalse(revokeDrink(" ").ok);
			gateway.verifyNoInteractions();
			gateway.when(() -> GatewayClient.request("POST", "/drinks/plugin/drinks/one/revoke", "{}"))
				.thenReturn(GatewayClient.Result.success("""
					{"texture_freed":true,"texture_id":"tex","ia_item_id":"ns:ale","cmd":42}
					"""));
			var result = revokeDrink(" one ");
			assertTrue(result.ok);
			assertTrue(result.deleted);
			assertTrue(result.textureFreed);
			assertEquals("tex", result.textureId);
			assertEquals("ns:ale", result.iaItemId);
			assertEquals(42, result.cmd);
			assertNull(result.error);
			for (String body : List.of("{}", "{\"texture_freed\":null,\"cmd\":null}", "{\"texture_freed\":false}")) {
				gateway.when(() -> GatewayClient.request("POST", "/drinks/plugin/drinks/one/revoke", "{}"))
					.thenReturn(GatewayClient.Result.success(body));
				result = revokeDrink("one");
				assertTrue(result.ok);
				assertTrue(result.deleted);
				assertFalse(result.textureFreed);
				assertNull(result.textureId);
				assertNull(result.iaItemId);
				assertNull(result.cmd);
			}
			gateway.when(() -> GatewayClient.request("POST", "/drinks/plugin/drinks/one/revoke", "{}"))
				.thenReturn(GatewayClient.Result.success("{"), GatewayClient.Result.fail("denied"));
			assertTrue(revokeDrink("one").error.startsWith("Bad revoke payload: "));
			result = revokeDrink("one");
			assertEquals("denied", result.error);
			assertFalse(result.ok);
			assertFalse(result.deleted);
			assertFalse(result.textureFreed);
			assertNull(result.textureId);
			assertNull(result.iaItemId);
			assertNull(result.cmd);
		}
	}

	@Test
	void resultFactoriesNormalizeNullLists() {
		assertTrue(AppliedResult.success(null).applied.isEmpty());
		assertTrue(ListResult.success(null).submissions.isEmpty());
		assertTrue(DeletableListResult.success(null).drinks.isEmpty());
		assertFalse(DeletableListResult.fail("bad").ok);
		assertTrue(DeletableListResult.fail("bad").drinks.isEmpty());
		assertFalse(ListResult.fail("bad").ok);
		assertTrue(ListResult.fail("bad").submissions.isEmpty());
	}

	@Test
	void jsonStringHandlesMissingNullScalarAndEscapedValues() {
		assertNull(jsonString(null, "a"));
		assertNull(jsonString("{}", null));
		assertNull(jsonString("{}", "a"));
		assertNull(jsonString("\"a\"", "a"));
		assertNull(jsonString("\"a\":", "a"));
		assertNull(jsonString("\"a\":  ", "a"));
		assertNull(jsonString("{\"a\":null}", "a"));
		assertEquals("no", jsonString("{\"a\":no}", "a"));
		assertEquals("42", jsonString("{\"a\": 42,\"b\":3}", "a"));
		assertEquals("true", jsonString("{\"a\":true}", "a"));
		assertEquals("42", jsonString("\"a\":42]", "a"));
		assertEquals("42", jsonString("\"a\":42", "a"));
		assertEquals("a\"b", jsonString("{\"a\":\"a\\\"b\"}", "a"));
		assertEquals("unfinished", jsonString("\"a\":\"unfinished", "a"));
		assertEquals("tail\\", jsonString("\"a\":\"tail\\", "a"));
		assertEquals("", escapeJson(null));
		assertEquals("a\\\\b\\\"c\\nd\\re", escapeJson("a\\b\"c\nd\re"));
	}

	private static PendingDrink pending(String textureId, TextureInfo texture) {
		return new PendingDrink("one", null, null, null, null, false, textureId, null, null, texture);
	}
}
