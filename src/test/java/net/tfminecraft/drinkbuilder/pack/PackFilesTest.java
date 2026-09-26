package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.*;

class PackFilesTest {
    @TempDir Path dir;
    JavaPlugin plugin;
    Logger log;
    String oldRoot;
    int oldMin, oldMax;
    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class);
        log = mock(Logger.class);
        when(plugin.getDataFolder()).thenReturn(dir.resolve("plugins/DrinkBuilder").toFile());
        when(plugin.getLogger()).thenReturn(log);
        oldRoot = Cache.itemsAdderTfmcDrinks;
        oldMin = Cache.cmdMin; oldMax = Cache.cmdMax;
        Cache.itemsAdderTfmcDrinks = dir.resolve("plugins/ItemsAdder/contents/tfmc_drinks").toString();
        Cache.cmdMin = 100; Cache.cmdMax = 102;
    }
    @AfterEach void restore() {
        Cache.itemsAdderTfmcDrinks = oldRoot;
        Cache.cmdMin = oldMin; Cache.cmdMax = oldMax;
    }
    @Test void allocatorPersistsRecyclesAndExhausts() {
        var allocator = new CmdAllocator(plugin);
        assertEquals(100, allocator.peekNext());
        assertEquals(100, allocator.allocate());
        assertEquals(101, allocator.allocate());
        allocator.free(99); allocator.free(103); allocator.free(102);
        allocator.free(100); allocator.free(100);
        assertEquals(100, allocator.peekNext());
        allocator = new CmdAllocator(plugin);
        assertEquals(100, allocator.allocate());
        assertEquals(102, allocator.allocate());
        assertThrows(IllegalStateException.class, allocator::allocate);
        allocator.free(101);
        assertEquals(101, allocator.allocate());
    }
    @Test void allocatorCleansLegacyStateAndChangedBounds() throws Exception {
        var file = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "next: 102\nfreed: [99, 100, ' 101 ', nonsense, null, 102, 103]\n");
        var allocator = new CmdAllocator(plugin);
        assertEquals(100, allocator.allocate());
        assertEquals(101, allocator.allocate());
        allocator.free(100); allocator.free(101);
        Cache.cmdMin = 101; Cache.cmdMax = 101;
        allocator.reloadBounds();
        assertEquals(101, allocator.allocate());
        Cache.cmdMin = 200; Cache.cmdMax = 201;
        allocator.reloadBounds();
        assertEquals(200, allocator.peekNext());
        Cache.cmdMin = 50; Cache.cmdMax = 51;
        allocator.reloadBounds();
        assertThrows(IllegalStateException.class, allocator::allocate);
        Files.writeString(file, "next: 1\n");
        assertEquals(50, new CmdAllocator(plugin).peekNext());
    }
    @Test void allocatorRefusesUnreadableExistingState() throws Exception {
        Files.createDirectories(plugin.getDataFolder().toPath().resolve("cmd-state.yml"));
        assertTrue(assertThrows(IllegalStateException.class, () -> new CmdAllocator(plugin))
            .getMessage().contains("Could not load drink CMD state"));
    }
    @Test void allocatorRefusesMalformedOrMissingExistingCountersWithoutOverwritingThem() throws Exception {
        Path state = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        Files.createDirectories(state.getParent());
        for (String content : List.of("next: [", "{}", "", "next: invalid", "next: 100.5")) {
            Files.writeString(state, content);
            assertThrows(IllegalStateException.class, () -> new CmdAllocator(plugin), content);
            assertEquals(content, Files.readString(state));
        }
    }
    @Test void allocatorPreservesCommittedBytesIfStateReplacementFails() throws Exception {
        var allocator = new CmdAllocator(plugin);
        assertEquals(100, allocator.allocate());
        Path state = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        for (boolean recycled : List.of(false, true)) {
            if (recycled) allocator.free(100);
            byte[] committed = Files.readAllBytes(state);
            try (var files = mockStatic(Files.class, invocation -> {
                if (invocation.getMethod().getName().equals("move") && state.equals(invocation.getArgument(1))) {
                    throw new IOException("replacement blocked");
                }
                return invocation.callRealMethod();
            })) {
                assertThrows(IllegalStateException.class, allocator::allocate);
            }
            assertArrayEquals(committed, Files.readAllBytes(state));
            assertEquals(recycled ? 100 : 101, allocator.peekNext());
            assertEquals(recycled ? 100 : 101, new CmdAllocator(plugin).peekNext());
            try (var paths = Files.list(state.getParent())) {
                assertEquals(List.of("cmd-state.yml"), paths.map(path -> path.getFileName().toString()).toList());
            }
        }
        verify(log, times(2)).warning("[cmd] could not save cmd-state.yml: replacement blocked");
    }
    @Test void allocatorPreservesCommittedBytesIfWritingTemporaryStateFails() throws Exception {
        var allocator = new CmdAllocator(plugin);
        assertEquals(100, allocator.allocate());
        Path state = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        byte[] committed = Files.readAllBytes(state);
        try (var yaml = mockConstruction(YamlConfiguration.class, withSettings().defaultAnswer(CALLS_REAL_METHODS),
                (configuration, context) -> {
                    doNothing().when(configuration).set(anyString(), any());
                    doAnswer(invocation -> {
                        Files.writeString(invocation.getArgument(0, File.class).toPath(), "next:");
                        throw new IOException("disk full during write");
                    }).when(configuration).save(any(File.class));
                })) {
            assertThrows(IllegalStateException.class, allocator::allocate);
        }
        assertArrayEquals(committed, Files.readAllBytes(state));
        assertEquals(101, allocator.peekNext());
        assertEquals(101, new CmdAllocator(plugin).allocate());
    }
    @Test void allocatorFallsBackWhenAtomicReplacementIsUnsupported() {
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("move")
                && ((CopyOption[]) invocation.getRawArguments()[2]).length == 2) {
                throw new AtomicMoveNotSupportedException("temp", "state", "test filesystem");
            }
            return invocation.callRealMethod();
        })) {
            var allocator = new CmdAllocator(plugin);
            assertEquals(100, allocator.allocate());
            assertEquals(101, new CmdAllocator(plugin).peekNext());
        }
    }
    @Test void pendingQueueNormalizesDeduplicatesAndSurvivesReload() throws Exception {
        var queue = new PendingReloadQueue(plugin);
        queue.load(); assertTrue(queue.isEmpty());
        queue.enqueue(null); queue.enqueue(List.of());
        queue.enqueue(Arrays.asList(null, "", " ", " a ", "a", "b"));
        assertEquals(List.of("a", "b"), queue.snapshot());
        queue.enqueue(List.of("a"));
        assertEquals(2, queue.size());
        queue.snapshot().clear(); assertEquals(2, queue.size());
        var reloaded = new PendingReloadQueue(plugin); reloaded.load();
        assertEquals(queue.snapshot(), reloaded.snapshot());
        queue.clear(null); queue.clear(List.of());
        queue.clear(Arrays.asList(null, "missing"));
        queue.clear(List.of(" a "));
        assertEquals(List.of("b"), queue.snapshot());
        queue.clear(List.of("b")); assertTrue(queue.isEmpty());
        Files.writeString(plugin.getDataFolder().toPath().resolve("pending-reload.yml"), "submission-ids: [' c ', '', ' ', c]\n");
        queue.load(); assertEquals(List.of("c"), queue.snapshot());
    }
    @Test void queueLogsUnwritableState() throws Exception {
        Files.createDirectories(plugin.getDataFolder().toPath().resolve("pending-reload.yml"));
        var queue = new PendingReloadQueue(plugin);
        queue.enqueue(List.of("pending"));
        assertEquals(List.of("pending"), queue.snapshot());
        verify(log).warning(contains("could not save pending-reload.yml"));
    }
    @Test void scaffoldIsIdempotentAndRemoverProtectsOtherNamespaces() throws Exception {
        IaDrinksScaffold.ensure(null);
        IaDrinksScaffold.ensure(plugin);
        var root = Path.of(Cache.itemsAdderTfmcDrinks);
        var itemsFile = root.resolve("configs/items.yml");
        var items = YamlConfiguration.loadConfiguration(itemsFile.toFile());
        assertEquals("tfmc_drinks", items.getString("info.namespace"));
        items.set("items.apple.name", "Apple"); items.save(itemsFile.toFile());
        IaDrinksScaffold.ensure(plugin);
        assertEquals("Apple", YamlConfiguration.loadConfiguration(itemsFile.toFile()).getString("items.apple.name"));
        var png = root.resolve("resourcepack/tfmc_drinks/textures/item/apple.png");
        Files.write(png, new byte[]{1});
        assertFalse(IaDrinksRemover.remove(plugin, null, log));
        assertFalse(IaDrinksRemover.remove(plugin, " ", null));
        assertThrows(IOException.class, () -> IaDrinksRemover.remove(plugin, "other:apple", log));
        assertThrows(IOException.class, () -> IaDrinksRemover.remove(plugin, "other:apple", null));
        for (var bad : List.of("tfmc_drinks:", "..", "a/b", "a\\b"))
            assertThrows(IOException.class, () -> IaDrinksRemover.remove(plugin, bad, log));
        assertTrue(IaDrinksRemover.remove(plugin, " TFMC_DRINKS:apple ", log));
        assertFalse(Files.exists(png));
        assertNull(YamlConfiguration.loadConfiguration(itemsFile.toFile()).get("items.apple"));
        assertFalse(IaDrinksRemover.remove(plugin, "apple", log));
        items.set("items.pear.name", "Pear"); items.save(itemsFile.toFile());
        Files.write(root.resolve("resourcepack/tfmc_drinks/textures/item/pear.png"), new byte[]{2});
        assertTrue(IaDrinksRemover.remove(plugin, "pear", null));
        Files.writeString(itemsFile, "{}");
        assertFalse(IaDrinksRemover.remove(plugin, "missing", null));
        Files.delete(itemsFile);
        assertFalse(IaDrinksRemover.remove(plugin, "missing", null));
    }
    @Test void scaffoldReportsBlockedDirectories() throws Exception {
        var root = Path.of(Cache.itemsAdderTfmcDrinks);
        Files.createDirectories(root);
        Files.writeString(root.resolve("configs"), "block");
        IaDrinksScaffold.ensure(plugin);
        verify(log).warning(contains("scaffold failed"));
        Files.delete(root.resolve("configs"));
        Files.writeString(root.resolve("resourcepack"), "block");
        IaDrinksScaffold.ensure(plugin);
        verify(log).warning(contains("could not create textures dir"));
        Cache.itemsAdderTfmcDrinks = root.resolve("resourcepack/blocked").toString();
        IaDrinksScaffold.ensure(plugin);
        verify(log).warning(contains("could not create configs dir"));
    }
    private PendingDrink drink(String id, String texture, String name) {
        return new PendingDrink(id, "player", "slug", name, "accepted", true, texture, null, null, null);
    }
    @Test void writerPublishesPngDefinitionCacheAndAssignment() throws Exception {
        var allocator = new CmdAllocator(plugin);
        var root = Path.of(Cache.itemsAdderTfmcDrinks);
        var cache = IaDrinksWriter.cmdCache(root.toFile()).toPath();
        Files.createDirectories(cache.getParent()); Files.writeString(cache, "POTION:\n");
        try (var api = mockStatic(ProvinceSystemClient.class)) {
            api.when(() -> ProvinceSystemClient.downloadSubmissionFile(anyString(), eq("texture.png")))
                .thenReturn(DownloadResult.success(new byte[]{1,2,3}));
            api.when(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()))
                .thenReturn(SimpleResult.success("ok"));
            var result = IaDrinksWriter.write(plugin, drink(" apple ", " texture ", " Apple "), allocator, log);
            assertEquals(100, result.cmd); assertEquals("tfmc_drinks:apple", result.iaItemId);
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(root.resolve("resourcepack/tfmc_drinks/textures/item/apple.png")));
            var yaml = YamlConfiguration.loadConfiguration(root.resolve("configs/items.yml").toFile());
            assertEquals("Apple", yaml.getString("items.apple.name"));
            assertEquals("WHITE", yaml.getString("items.apple.graphics.color"));
            assertTrue(Files.readString(cache).contains("tfmc_drinks:apple: 100"));
            api.verify(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:apple"));
            Files.writeString(root.resolve("configs/items.yml"), "{}");
            IaDrinksWriter.write(plugin, drink("pear", "texture", null), allocator, null);
            assertEquals("pear", YamlConfiguration.loadConfiguration(root.resolve("configs/items.yml").toFile()).getString("items.pear.name"));
            api.when(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()))
                .thenReturn(SimpleResult.fail("offline"));
            assertTrue(assertThrows(IOException.class, () -> IaDrinksWriter.write(plugin, drink("plum", "texture", " "), allocator, log)).getMessage().contains("assign CMD"));
        }
    }
    @Test void writerRejectsInvalidRequestsAndDownloadFailures() {
        var allocator = mock(CmdAllocator.class);
        for (var d : Arrays.asList(null, drink(null,"t","n"), drink(" ","t","n"), drink("a",null,"n"), drink("a"," ","n")))
            assertThrows(IOException.class, () -> IaDrinksWriter.write(plugin,d,allocator,log));
        try (var api = mockStatic(ProvinceSystemClient.class)) {
            api.when(() -> ProvinceSystemClient.downloadSubmissionFile("a", "texture.png"))
                .thenReturn(DownloadResult.fail("offline"));
            assertTrue(assertThrows(IOException.class, () -> IaDrinksWriter.write(plugin, drink("a","t","n"),allocator,log)).getMessage().contains("download texture.png"));
        }
        verifyNoInteractions(allocator);
    }
    @Test void pathResolutionHasSafeFallbacks() {
        assertEquals(Path.of(Cache.itemsAdderTfmcDrinks).toFile(), IaDrinksWriter.resolveDrinksRoot(plugin));
        assertEquals(dir.resolve("relative").toFile(), IaDrinksWriter.resolvePath(plugin,"relative"));
        assertEquals(dir.resolve("plugins/ItemsAdder/contents/tfmc_drinks").toFile(), IaDrinksWriter.resolvePath(plugin,null));
        assertEquals(IaDrinksWriter.resolvePath(plugin,null), IaDrinksWriter.resolvePath(plugin," "));
        when(plugin.getDataFolder()).thenReturn(new File("only"));
        assertEquals(new File("relative"), IaDrinksWriter.resolvePath(plugin,"relative"));
        when(plugin.getDataFolder()).thenReturn(new File("parent/only"));
        assertEquals(new File("relative"), IaDrinksWriter.resolvePath(plugin,"relative"));
        assertEquals(new File("plugins/ItemsAdder/storage/items_ids_cache.yml"), IaDrinksWriter.cmdCache(null));
        assertEquals(IaDrinksWriter.cmdCache(null), IaDrinksWriter.cmdCache(new File("root")));
        assertEquals(IaDrinksWriter.cmdCache(null), IaDrinksWriter.cmdCache(new File("parent/root")));
        assertEquals("tfmc_drinks", DrinksNamespace.current());
        assertEquals(new File("contents/tfmc_drinks"), DrinksNamespace.contentsRoot(new File("contents")));
    }

    @Test void realmControlsNamespaceAndConfiguredSibling() {
        try {
            for (Object realm : Arrays.asList(null, "", " ", "\u2003", "main", " MAIN ")) {
                net.tfminecraft.tfmcweb.TFMCWeb.realm = realm;
                assertEquals("tfmc_drinks", DrinksNamespace.current());
            }
            net.tfminecraft.tfmcweb.TFMCWeb.realm = " TEST ";
            assertEquals("tfmc_drinks_test", DrinksNamespace.current());
            assertEquals(Path.of(Cache.itemsAdderTfmcDrinks).resolveSibling("tfmc_drinks_test").toFile(), IaDrinksWriter.resolveDrinksRoot(plugin));
            Cache.itemsAdderTfmcDrinks = "root";
            when(plugin.getDataFolder()).thenReturn(new File("only"));
            assertEquals(new File("tfmc_drinks_test"), IaDrinksWriter.resolveDrinksRoot(plugin));
            net.tfminecraft.tfmcweb.TFMCWeb.failure = new IllegalStateException("unavailable");
            assertEquals("tfmc_drinks", DrinksNamespace.current());
        } finally {
            net.tfminecraft.tfmcweb.TFMCWeb.realm = "main";
            net.tfminecraft.tfmcweb.TFMCWeb.failure = null;
        }
    }

    @Test void writerCreatesMissingDirectoriesAndReportsBlockedTextureDirectory() throws Exception {
        var root = Path.of(Cache.itemsAdderTfmcDrinks);
        Files.createDirectories(root.resolve("configs"));
        var cache = IaDrinksWriter.cmdCache(root.toFile()).toPath();
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "POTION:\n");
        var allocator = new CmdAllocator(plugin);
        try (var scaffold = mockStatic(IaDrinksScaffold.class); var api = mockStatic(ProvinceSystemClient.class)) {
            api.when(() -> ProvinceSystemClient.downloadSubmissionFile("a", "texture.png"))
                .thenReturn(DownloadResult.success(new byte[]{1}));
            api.when(() -> ProvinceSystemClient.assignTextureCmd("t", 100, "tfmc_drinks:a"))
                .thenReturn(SimpleResult.success("ok"));
            var result = IaDrinksWriter.write(plugin, drink("a","t","A"),allocator,log);
            assertEquals(100,result.cmd);
            assertEquals("tfmc_drinks", YamlConfiguration.loadConfiguration(root.resolve("configs/items.yml").toFile()).getString("info.namespace"));
            assertTrue(Files.isRegularFile(root.resolve("resourcepack/tfmc_drinks/textures/item/a.png")));
            Cache.itemsAdderTfmcDrinks = root.resolve("blocked").toString();
            Files.writeString(root.resolve("blocked"), "file");
            assertTrue(assertThrows(IOException.class, () -> IaDrinksWriter.write(plugin,drink("a","t","A"),allocator,log))
                .getMessage().contains("could not create textures dir"));
        }
    }

    @Test void removerReportsFilesystemRefusalToDeleteTexture() throws Exception {
        File root = Path.of(Cache.itemsAdderTfmcDrinks).toFile();
        // Load the production classes before intercepting File construction used by class loading.
        assertFalse(IaDrinksRemover.remove(plugin, null, log));
        assertEquals("tfmc_drinks", DrinksNamespace.current());
        try (var writer = mockStatic(IaDrinksWriter.class);
             var files = mockConstruction(File.class, (file, context) -> {
                 String child = (String) context.arguments().get(1);
                 when(file.isFile()).thenReturn(child.endsWith(".png"));
                 when(file.delete()).thenReturn(false);
                 when(file.getAbsolutePath()).thenReturn("blocked.png");
             })) {
            writer.when(() -> IaDrinksWriter.resolveDrinksRoot(plugin)).thenReturn(root);
            assertEquals("could not delete blocked.png", assertThrows(IOException.class,
                () -> IaDrinksRemover.remove(plugin,"drink",log)).getMessage());
            verify(files.constructed().getLast()).delete();
        }
    }

    @Test void allocatorDropsFreedValuesAboveNewMaximum() {
        var allocator = new CmdAllocator(plugin);
        allocator.allocate(); allocator.allocate(); allocator.allocate();
        allocator.free(102);
        Cache.cmdMax = 101;
        allocator.reloadBounds();
        assertEquals(102, allocator.peekNext());
        assertThrows(IllegalStateException.class, allocator::allocate);
    }

    @Test void allocatorDoesNotReturnIdsWhoseStateCannotBePersisted() throws Exception {
        var allocator = new CmdAllocator(plugin);
        var state = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        Files.delete(state); Files.createDirectory(state);
        assertThrows(IllegalStateException.class, allocator::allocate);
        assertEquals(100, allocator.peekNext());
        Files.delete(state);
        assertEquals(100, allocator.allocate());
        allocator.free(100);
        Files.delete(state); Files.createDirectory(state);
        assertThrows(IllegalStateException.class, allocator::allocate);
        assertEquals(100, allocator.peekNext());
        Files.delete(state);
        assertEquals(100, allocator.allocate());
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }

    @Test void allocatorKeepsPreviousStateWhenTemporaryFileCannotBeCreated() throws Exception {
        var allocator = new CmdAllocator(plugin);
        var state = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        byte[] before = Files.readAllBytes(state);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.createTempFile(any(Path.class), eq("cmd-state-"), eq(".tmp")))
                .thenThrow(new IOException("disk full"));
            assertThrows(IllegalStateException.class, allocator::allocate);
        }
        assertArrayEquals(before, Files.readAllBytes(state));
        assertEquals(100, allocator.peekNext());
        assertEquals(100, new CmdAllocator(plugin).allocate());
    }

    @Test void allocatorFlushFailurePreservesPreviousCounterAndDoesNotReturnAnId() throws Exception {
        var allocator = new CmdAllocator(plugin);
        var state = plugin.getDataFolder().toPath().resolve("cmd-state.yml");
        byte[] before = Files.readAllBytes(state);
        var channel = mock(java.nio.channels.FileChannel.class);
        doThrow(new IOException("flush failed")).when(channel).force(true);
        try (var channels = mockStatic(java.nio.channels.FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> java.nio.channels.FileChannel.open(any(Path.class), eq(java.nio.file.StandardOpenOption.WRITE)))
                .thenReturn(channel);
            assertThrows(IllegalStateException.class, allocator::allocate);
        }
        verify(channel).close();
        assertArrayEquals(before, Files.readAllBytes(state));
        assertEquals(100, new CmdAllocator(plugin).allocate());
    }

    @Test void allocatorAllowsUnsupportedDirectoryFlushAfterPersistingTheFile() throws Exception {
        var allocator = new CmdAllocator(plugin);
        try (var channels = mockStatic(java.nio.channels.FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> java.nio.channels.FileChannel.open(any(Path.class), eq(java.nio.file.StandardOpenOption.READ)))
                .thenThrow(new IOException("directory flush unavailable"));
            assertEquals(100, allocator.allocate());
        }
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }
}
