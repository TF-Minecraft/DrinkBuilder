package dev.lone.itemsadder.api;

import org.bukkit.inventory.ItemStack;

/**
 * Test boundary for the two ItemsAdder API calls used by the ingredient checker.
 * The provided ItemsAdder JAR references server-only NMS classes. This fixture
 * lets unit tests model API responses without loading a Minecraft server.
 */
public class CustomStack {
    public static CustomStack getInstance(String id) { throw new UnsupportedOperationException("stub in each test"); }
    public ItemStack getItemStack() { throw new UnsupportedOperationException("stub in each test"); }
}
