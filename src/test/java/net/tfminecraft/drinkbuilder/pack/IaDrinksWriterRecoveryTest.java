package net.tfminecraft.drinkbuilder.pack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.DownloadResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.SimpleResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.TextureInfo;

class IaDrinksWriterRecoveryTest {
    @TempDir Path dir;
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final Logger log = mock(Logger.class);
    private String savedRoot;
    private int savedMin, savedMax;
    private Path root, png, items, cache, journal;
    private MockedStatic<DrinksNamespace> namespace;
    private MockedStatic<ProvinceSystemClient> api;

    @BeforeEach void setUp() throws Exception {
        savedRoot = Cache.itemsAdderTfmcDrinks;
        savedMin = Cache.cmdMin;
        savedMax = Cache.cmdMax;
        Cache.cmdMin = 100;
        Cache.cmdMax = 102;
        root = dir.resolve("plugins/ItemsAdder/contents/tfmc_drinks");
        Cache.itemsAdderTfmcDrinks = root.toString();
        Path data = dir.resolve("plugins/DrinkBuilder");
        Files.createDirectories(data);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(log);
        png = root.resolve("resourcepack/tfmc_drinks/textures/item/ale.png");
        items = root.resolve("configs/items.yml");
        cache = IaDrinksWriter.cmdCache(root.toFile()).toPath();
        journal = data.resolve("pending-writes/ale.yml");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "POTION:\n  other:item: 50\n");
        namespace = mockStatic(DrinksNamespace.class);
        namespace.when(DrinksNamespace::current).thenReturn("tfmc_drinks");
        api = mockStatic(ProvinceSystemClient.class);
        api.when(() -> ProvinceSystemClient.downloadSubmissionFile("ale", "texture.png"))
            .thenReturn(DownloadResult.success(new byte[]{9, 8, 7}));
        api.when(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()))
            .thenReturn(SimpleResult.fail("response lost"));
    }

    @AfterEach void tearDown() {
        api.close();
        namespace.close();
        Cache.itemsAdderTfmcDrinks = savedRoot;
        Cache.cmdMin = savedMin;
        Cache.cmdMax = savedMax;
    }

    @Test void failedAssignmentRestoresEveryPreexistingByteAndKeepsItsReservation() throws Exception {
        Files.createDirectories(png.getParent());
        Files.createDirectories(items.getParent());
        byte[] oldPng = {1, 2, 3, 4};
        byte[] oldItems = "# preserved formatting\ninfo:\n  namespace: tfmc_drinks\nitems: {}\n".getBytes();
        byte[] oldCache = Files.readAllBytes(cache);
        Files.write(png, oldPng);
        Files.write(items, oldItems);
        var allocator = new CmdAllocator(plugin);
        IOException error = assertThrows(IOException.class, () -> write(allocator, null));
        assertTrue(error.getMessage().contains("assign CMD"));
        assertArrayEquals(oldPng, Files.readAllBytes(png));
        assertArrayEquals(oldItems, Files.readAllBytes(items));
        assertArrayEquals(oldCache, Files.readAllBytes(cache));
        assertTrue(IaDrinksWriter.hasPendingWrite(plugin, "ale"));
        var pending = YamlConfiguration.loadConfiguration(journal.toFile());
        assertEquals(100, pending.getInt("cmd"));
        assertEquals("tfmc_drinks:ale", pending.getString("ia-item-id"));
        assertEquals("texture", pending.getString("texture-id"));
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }

    @Test void repeatedLostResponsesReuseCmdAcrossRestartThenCompletePublication() throws Exception {
        byte[] oldCache = Files.readAllBytes(cache);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
            assertFalse(Files.exists(png));
            assertFalse(Files.exists(items));
            assertArrayEquals(oldCache, Files.readAllBytes(cache));
            assertEquals(101, new CmdAllocator(plugin).peekNext());
        }
        api.verify(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"), times(5));
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("ok"));
        var result = write(new CmdAllocator(plugin), null);
        assertEquals(100, result.cmd);
        assertEquals("tfmc_drinks:ale", result.iaItemId);
        assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(png));
        assertEquals("Ale", YamlConfiguration.loadConfiguration(items.toFile()).getString("items.ale.name"));
        assertTrue(Files.readString(cache).contains("tfmc_drinks:ale: 100"));
        assertTrue(Files.readString(cache).contains("other:item: 50"));
        assertFalse(IaDrinksWriter.hasPendingWrite(plugin, "ale"));
    }

    @Test void retryRepairsLocalFilesWhenRemoteAlreadyAcceptedAssignment() throws Exception {
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("already assigned"));
        var result = write(new CmdAllocator(plugin), 100);
        assertEquals(100, result.cmd);
        assertTrue(Files.isRegularFile(png));
        assertTrue(Files.isRegularFile(items));
        assertFalse(Files.exists(journal));
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }

    @Test void failedLocalWriteRestoresAbsentFilesAndReturnsSafeAllocation() throws Exception {
        Files.delete(cache);
        var allocator = new CmdAllocator(plugin);
        IOException error = assertThrows(IOException.class, () -> write(allocator, null));
        assertTrue(error.getMessage().contains("CMD cache missing"));
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(items));
        assertFalse(Files.exists(cache));
        assertFalse(Files.exists(journal));
        assertEquals(100, new CmdAllocator(plugin).peekNext());
        api.verify(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()), never());
    }

    @Test void rollbackFailureRetainsJournalAndIdAndStillRestoresOtherFiles() throws Exception {
        Files.delete(cache);
        try (var writer = mockStatic(IaDrinksWriter.class, CALLS_REAL_METHODS)) {
            writer.when(() -> IaDrinksWriter.restoreSnapshot(png, null))
                .thenThrow(new IOException("rollback blocked"));
            IOException error = assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
            assertEquals("rollback blocked", error.getSuppressed()[0].getMessage());
            assertTrue(Files.exists(png));
            assertFalse(Files.exists(items));
            assertFalse(Files.exists(cache));
            assertTrue(Files.exists(journal));
            assertEquals(101, new CmdAllocator(plugin).peekNext());
            api.verify(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()), never());
        }
    }

    @Test void successfulAssignmentSurvivesJournalCleanupFailure() throws Exception {
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("ok"));
        try (var writer = mockStatic(IaDrinksWriter.class, CALLS_REAL_METHODS)) {
            writer.when(() -> IaDrinksWriter.deleteJournal(journal)).thenThrow(new IOException("cleanup blocked"));
            var result = write(new CmdAllocator(plugin), null);
            assertEquals(100, result.cmd);
            assertArrayEquals(new byte[]{9, 8, 7}, Files.readAllBytes(png));
            assertTrue(Files.readString(items).contains("Ale"));
            assertTrue(Files.readString(cache).contains("tfmc_drinks:ale: 100"));
            assertTrue(Files.exists(journal));
            assertEquals(101, new CmdAllocator(plugin).peekNext());
            verify(log).warning(contains("could not remove pending write"));
        }
        write(new CmdAllocator(plugin), 100);
        assertFalse(Files.exists(journal));
    }

    @Test void localCleanupFailureDoesNotFreeIdStillReferencedByJournal() throws Exception {
        Files.delete(cache);
        try (var writer = mockStatic(IaDrinksWriter.class, CALLS_REAL_METHODS)) {
            writer.when(() -> IaDrinksWriter.deleteJournal(journal)).thenThrow(new IOException("cleanup blocked"));
            IOException error = assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
            assertEquals("cleanup blocked", error.getSuppressed()[0].getMessage());
            assertFalse(Files.exists(png));
            assertFalse(Files.exists(items));
            assertTrue(Files.exists(journal));
            assertEquals(101, new CmdAllocator(plugin).peekNext());
        }
    }

    @Test void remoteRuntimeFailureAlsoRestoresFilesAndRetainsReservation() throws Exception {
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenThrow(new IllegalStateException("connection reset"));
        assertThrows(IllegalStateException.class, () -> write(new CmdAllocator(plugin), null));
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(items));
        assertTrue(Files.exists(journal));
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }

    @Test void unsafeSubmissionIdsAreRejectedBeforeAnyIoOrAllocation() {
        var allocator = mock(CmdAllocator.class);
        for (String id : List.of("../ale", "a/b", "a\\b", ".", "a.b", "a b")) {
            assertThrows(IOException.class, () -> IaDrinksWriter.write(plugin, drink(id, null), allocator, log));
            assertFalse(IaDrinksWriter.hasPendingWrite(plugin, id));
        }
        assertFalse(IaDrinksWriter.hasPendingWrite(null, "ale"));
        assertFalse(IaDrinksWriter.hasPendingWrite(plugin, null));
        verifyNoInteractions(allocator);
        api.verifyNoInteractions();
    }

    @Test void unreadableOrConflictingJournalFailsClosed() throws Exception {
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        api.clearInvocations();
        assertTrue(assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), 101))
            .getMessage().contains("conflicts"));
        Files.writeString(journal, "cmd: 100\nia-item-id: other:ale\ntexture-id: texture\n");
        assertTrue(assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null))
            .getMessage().contains("does not match"));
        Files.writeString(journal, "cmd: [invalid YAML");
        assertTrue(assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null))
            .getMessage().contains("invalid YAML"));
        api.verifyNoInteractions();
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }

    @Test void journalCreationFailureReturnsAllocationWithoutPublishing() throws Exception {
        Files.writeString(journal.getParent(), "blocked");
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(items));
        assertEquals(100, new CmdAllocator(plugin).peekNext());
        api.verify(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()), never());
    }

    @Test void confirmedRemoteDeletionCleansPartialPublicationAndFreesReservation() throws Exception {
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        Files.createDirectories(png.getParent());
        Files.write(png, new byte[]{1});
        Files.createDirectories(items.getParent());
        Files.writeString(items, "items:\n  ale:\n    name: Ale\n  other:\n    name: Other\n");
        assertTrue(IaDrinksWriter.cancelPendingWrite(plugin, " ale ", new CmdAllocator(plugin), log));
        assertFalse(Files.exists(png));
        var yaml = YamlConfiguration.loadConfiguration(items.toFile());
        assertFalse(yaml.contains("items.ale"));
        assertEquals("Other", yaml.getString("items.other.name"));
        assertFalse(Files.exists(journal));
        assertEquals(100, new CmdAllocator(plugin).peekNext());
        assertFalse(IaDrinksWriter.cancelPendingWrite(plugin, "ale", new CmdAllocator(plugin), null));
    }

    @Test void cancellationRejectsInvalidIdsAndJournalWithoutFreeing() throws Exception {
        var allocator = mock(CmdAllocator.class);
        assertThrows(IOException.class, () -> IaDrinksWriter.cancelPendingWrite(plugin, null, allocator, log));
        assertThrows(IOException.class, () -> IaDrinksWriter.cancelPendingWrite(plugin, "../ale", allocator, log));
        Files.createDirectories(journal.getParent());
        for (String body : List.of("ia-item-id: tfmc_drinks:ale\ntexture-id: texture\n",
                "cmd: 100\nia-item-id: other:ale\ntexture-id: texture\n",
                "cmd: 100\nia-item-id: tfmc_drinks:ale\n")) {
            Files.writeString(journal, body);
            assertTrue(assertThrows(IOException.class,
                () -> IaDrinksWriter.cancelPendingWrite(plugin, "ale", allocator, log))
                .getMessage().contains("invalid pending"));
            assertTrue(Files.exists(journal));
        }
        verifyNoInteractions(allocator);
    }

    @Test void cancellationFailureLeavesReservationUntilRetryCompletes() throws Exception {
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        try (var remover = mockStatic(IaDrinksRemover.class)) {
            remover.when(() -> IaDrinksRemover.remove(plugin, "tfmc_drinks:ale", log))
                .thenThrow(new IOException("remove blocked"));
            assertEquals("remove blocked", assertThrows(IOException.class,
                () -> IaDrinksWriter.cancelPendingWrite(plugin, "ale", new CmdAllocator(plugin), log)).getMessage());
            assertTrue(Files.exists(journal));
            assertEquals(101, new CmdAllocator(plugin).peekNext());
        }
        try (var writer = mockStatic(IaDrinksWriter.class, invocation -> {
            if (invocation.getMethod().getName().equals("deleteJournal")) {
                throw new IOException("delete blocked");
            }
            return invocation.callRealMethod();
        })) {
            assertThrows(IOException.class,
                () -> IaDrinksWriter.cancelPendingWrite(plugin, "ale", new CmdAllocator(plugin), log));
            assertTrue(Files.exists(journal));
            assertEquals(101, new CmdAllocator(plugin).peekNext());
        }
        assertTrue(IaDrinksWriter.cancelPendingWrite(plugin, "ale", new CmdAllocator(plugin), log));
        assertEquals(100, new CmdAllocator(plugin).peekNext());
    }

    @Test void localFailureDuringRetryDoesNotReleasePossiblyAcceptedCmd() throws Exception {
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        Files.delete(cache);
        assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null));
        assertTrue(Files.exists(journal));
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }

    @Test void downloadWithoutBytesNeverAllocatesOrPublishes() {
        for (byte[] bytes : new byte[][]{null, new byte[0]}) {
            api.when(() -> ProvinceSystemClient.downloadSubmissionFile("ale", "texture.png"))
                .thenReturn(DownloadResult.success(bytes));
            var allocator = mock(CmdAllocator.class);
            assertThrows(IOException.class, () -> write(allocator, null));
            verifyNoInteractions(allocator);
            assertFalse(Files.exists(journal));
        }
    }

    @Test void malformedExistingItemsAreRestoredInsteadOfOverwritten() throws Exception {
        Files.createDirectories(items.getParent());
        String invalid = "items: [broken YAML";
        Files.writeString(items, invalid);
        assertTrue(assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null))
            .getMessage().contains("invalid YAML"));
        assertEquals(invalid, Files.readString(items));
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(journal));
        assertEquals(100, new CmdAllocator(plugin).peekNext());
    }

    @Test void changedTextureAndMissingCmdCannotReuseJournal() throws Exception {
        Files.createDirectories(journal.getParent());
        for (String body : List.of("ia-item-id: tfmc_drinks:ale\ntexture-id: texture\n",
                "cmd: 100\nia-item-id: tfmc_drinks:ale\ntexture-id: another\n")) {
            Files.writeString(journal, body);
            assertTrue(assertThrows(IOException.class, () -> write(new CmdAllocator(plugin), null))
                .getMessage().contains("does not match"));
        }
        api.verifyNoInteractions();
    }

    @Test void existingRemoteCmdCanRepairMissingPublicationWithoutAllocating() throws Exception {
        var allocator = mock(CmdAllocator.class);
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("already assigned"));
        assertEquals(100, write(allocator, 100).cmd);
        verifyNoInteractions(allocator);
        Files.delete(journal.getParent());
        Files.writeString(journal.getParent(), "blocked");
        assertThrows(IOException.class, () -> write(allocator, 100));
        verifyNoInteractions(allocator);
    }

    @Test void successfulPublicationWithoutLoggerSurvivesJournalCleanupFailure() throws Exception {
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("ok"));
        try (var writer = mockStatic(IaDrinksWriter.class, invocation -> {
            if (invocation.getMethod().getName().equals("deleteJournal")) {
                throw new IOException("cleanup blocked");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals(100, IaDrinksWriter.write(plugin, drink("ale", null), new CmdAllocator(plugin), null).cmd);
            assertTrue(Files.exists(journal));
            assertTrue(Files.exists(png));
        }
    }

    @Test void journalCommittedBeforeCleanupFailureKeepsReservationForSafeRetry() throws Exception {
        var allocator = new CmdAllocator(plugin);
        try (var files = mockStatic(Files.class, invocation -> {
            if (invocation.getMethod().getName().equals("deleteIfExists")
                    && invocation.getArgument(0, Path.class).getFileName().toString().endsWith(".tmp")) {
                throw new IOException("temporary-file cleanup failed");
            }
            return invocation.callRealMethod();
        })) {
            assertEquals("temporary-file cleanup failed", assertThrows(IOException.class,
                () -> write(allocator, null)).getMessage());
        }
        assertTrue(Files.exists(journal));
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(items));
        assertEquals(101, new CmdAllocator(plugin).peekNext());
        api.verify(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()), never());
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("ok"));
        assertEquals(100, write(new CmdAllocator(plugin), null).cmd);
    }

    private IaDrinksWriter.WriteResult write(CmdAllocator allocator, Integer remoteCmd) throws IOException {
        return IaDrinksWriter.write(plugin, drink("ale", remoteCmd), allocator, log);
    }

    private PendingDrink drink(String id, Integer remoteCmd) {
        return new PendingDrink(id, "player", "ale", "Ale", "approved", true, "texture", null, null,
            remoteCmd == null ? null : new TextureInfo("texture", remoteCmd, "tfmc_drinks:ale", "ale.png"));
    }

    @Test void journalFlushFailureReleasesUnpublishedReservationWithoutChangingFiles() throws Exception {
        var allocator = new CmdAllocator(plugin);
        byte[] before = Files.readAllBytes(cache);
        var channel = mock(java.nio.channels.FileChannel.class);
        doThrow(new IOException("journal flush failed")).when(channel).force(true);
        try (var channels = mockStatic(java.nio.channels.FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> java.nio.channels.FileChannel.open(argThat((Path p) -> p.getParent().equals(journal.getParent())), eq(java.nio.file.StandardOpenOption.WRITE)))
                .thenReturn(channel);
            assertThrows(IOException.class, () -> write(allocator, null));
        }
        verify(channel).close();
        assertFalse(Files.exists(journal));
        assertFalse(Files.exists(png));
        assertFalse(Files.exists(items));
        assertArrayEquals(before, Files.readAllBytes(cache));
        assertEquals(100, new CmdAllocator(plugin).peekNext());
        api.verify(() -> ProvinceSystemClient.assignTextureCmd(anyString(), anyInt(), anyString()), never());
    }

    @Test void journalDirectoryFlushIsBestEffortAfterTheFileIsPersisted() throws Exception {
        var allocator = new CmdAllocator(plugin);
        api.when(() -> ProvinceSystemClient.assignTextureCmd("texture", 100, "tfmc_drinks:ale"))
            .thenReturn(SimpleResult.success("ok"));
        try (var channels = mockStatic(java.nio.channels.FileChannel.class, CALLS_REAL_METHODS)) {
            channels.when(() -> java.nio.channels.FileChannel.open(eq(journal.getParent()), eq(java.nio.file.StandardOpenOption.READ)))
                .thenThrow(new IOException("directory flush unavailable"));
            assertEquals(100, write(allocator, null).cmd);
        }
        assertTrue(Files.exists(png));
        assertFalse(Files.exists(journal));
        assertEquals(101, new CmdAllocator(plugin).peekNext());
    }
}
