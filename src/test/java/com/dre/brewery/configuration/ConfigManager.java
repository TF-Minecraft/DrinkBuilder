package com.dre.brewery.configuration;

/** Test-only boundary fixture; counters verify registration precedes recipe loading. */
public class ConfigManager {
    public static int registrations;
    public static int recipeLoads;
    public static int cauldronLoads;
    public static int reloads;
    public static boolean fail;
    public static void registerDefaultPluginItems() {
        if (fail) throw new IllegalStateException("API failure");
        registrations++;
    }
    public static void newInstance(Class<?> type, boolean overwrite) { reloads++; }
    public static void loadRecipes() { recipeLoads++; }
    public static void loadCauldronIngredients() { cauldronLoads++; }
}
