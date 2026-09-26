package net.tfminecraft.tfmcweb;

/** Test fixture for the optional reflective realm contract. */
public final class TFMCWeb {
    public static Object realm = "main";
    public static RuntimeException failure;
    public static Object getRealmId() {
        if (failure != null) throw failure;
        return realm;
    }
}
