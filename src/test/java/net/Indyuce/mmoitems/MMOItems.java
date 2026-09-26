package net.Indyuce.mmoitems;

import java.util.*;

/** Minimal reflection fixture for the MMOItems integration. */
public final class MMOItems {
    public static MMOItems plugin;
    public Items items = new Items();
    public Types types = new Types();
    public Items getItems() { return items; }
    public Types getTypes() { return types; }
    public static final class Type {}
    public static final class Types {
        public Object all = List.of(new Type(), new Type());
        public Object getAll() { return all; }
    }
    public static final class Items {
        public String existing;
        public boolean fail;
        public Object getMMOItem(Type type, String id) {
            if (fail) throw new IllegalStateException("unavailable");
            return Objects.equals(existing, id) ? new Object() : null;
        }
    }
}
