package pl.holovis.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import pl.holovis.Accounts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Konta online KosaVisuals (przez Firebase): kto gra na KosaVisuals na tym samym serwerze,
 * prywatne wiadomości z potwierdzeniem przeczytania, rangi widoczne u wszystkich i podgląd czatów dla właścicieli.
 *
 * Baza:
 *  users/{uuid}        {acc, mc, server, role, seen}
 *  ranks/{nick}        "VIP" | "FRIEND" | "V-WŁAŚCICIEL"
 *  chats/{chatId}/{id} {from, fromName, fromMc, to, toName, text, ts, status}
 *  chatmeta/{chatId}   {a, b, last}
 *  inbox/{uuid}/{id}   {chat, from, fromName, fromMc, text, ts}
 */
public final class KosaNet {
    private KosaNet() {}

    public record User(String uuid, String acc, String mc, String server, String role, long seen, pl.holovis.CustomNametag.Data tag) {}
    public record Msg(String id, String from, String fromName, String fromMc, String to, String text, long ts, String status) {}
    public record Incoming(String id, String chat, String from, String fromName, String fromMc, String text, long ts) {}
    public record ChatMeta(String id, String a, String b, long last) {}

    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "holovis-net");
        t.setDaemon(true);
        return t;
    });

    private static volatile List<User> users = List.of();
    private static volatile Map<String, String> ranks = Map.of();
    private static volatile Map<String, List<Msg>> chats = Map.of();
    private static volatile List<ChatMeta> allChats = List.of();
    private static final Set<String> seenInbox = new HashSet<>();
    private static volatile String watchedChat;
    private static volatile boolean busy;
    private static long tPresence, tInbox, tUsers, tRanks, tChat;
    private static volatile long serverClockOffset;

    public static boolean available() { return Net.configured() && Accounts.has(); }

    public static String myUuid() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) return mc.player.getUuid().toString();
        UUID u = mc.getSession().getUuidOrNull();
        return u == null ? "" : u.toString();
    }

    public static String myMc() { return MinecraftClient.getInstance().getSession().getUsername(); }

    public static String server() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ServerInfo si = mc.getCurrentServerEntry();
        if (si != null) return si.address.toLowerCase(Locale.ROOT);
        return mc.isInSingleplayer() ? "singleplayer" : "";
    }

    public static String chatId(String a, String b) { return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a; }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static long num(JsonObject o, String k) {
        try { return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : 0L; } catch (Exception e) { return 0L; }
    }

    private static String cut(String s) { return s.length() > 40 ? s.substring(0, 40) : s; }

    /** Napis NameTag innego gracza z KosaVisuals (albo null). */
    public static pl.holovis.CustomNametag.Data tagOf(UUID id) {
        if (id == null || !Net.configured()) return null;
        String s = id.toString();
        long now = System.currentTimeMillis() + serverClockOffset;
        for (User u : users) if (u.uuid().equals(s) && now - u.seen() < 120_000) return u.tag();
        return null;
    }

    private static Map<String, Object> sv() { Map<String, Object> m = new HashMap<>(); m.put(".sv", "timestamp"); return m; }

    // ------------------------------------------------------------------ pętla
    public static void tick(MinecraftClient mc) {
        if (!available() || mc.player == null || busy) return;
        long now = System.currentTimeMillis();
        boolean presence = now - tPresence > 20_000, inbox = now - tInbox > 3_000, us = now - tUsers > 10_000,
                rk = now - tRanks > 30_000, ch = watchedChat != null && now - tChat > 1_500;
        if (!(presence || inbox || us || rk || ch)) return;
        if (presence) tPresence = now;
        if (inbox) tInbox = now;
        if (us) tUsers = now;
        if (rk) tRanks = now;
        if (ch) tChat = now;
        String me = myUuid(), srv = server(), acc = Accounts.name(), nick = myMc();
        String role = Accounts.isOwner() ? (Accounts.isLocalOwner() ? "owner" : "vowner") : "";
        String wc = watchedChat;
        pl.holovis.CustomNametag.Data myTag = pl.holovis.CustomNametag.mine();
        busy = true;
        EX.execute(() -> {
            try {
                if (presence) {
                    Map<String, Object> u = new LinkedHashMap<>();
                    u.put("acc", acc); u.put("mc", nick); u.put("server", srv); u.put("role", role); u.put("seen", sv());
                    if (myTag != null) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("l1", myTag.l1()); t.put("l2", myTag.l2()); t.put("icon", myTag.icon());
                        t.put("size", Math.round(myTag.size() * 10)); t.put("font", myTag.font()); t.put("color", myTag.color());
                        t.put("mode", myTag.mode());
                        u.put("tag", t);
                    }
                    JsonElement r = Net.put("users/" + me, u);
                    if (r != null && r.isJsonObject() && r.getAsJsonObject().has("seen"))
                        serverClockOffset = r.getAsJsonObject().get("seen").getAsLong() - System.currentTimeMillis();
                }
                if (rk) loadRanks();
                if (us) loadUsers();
                if (inbox) loadInbox(me);
                if (wc != null) loadChat(wc);
            } finally {
                busy = false;
            }
        });
    }

    private static void loadRanks() {
        JsonElement e = Net.get("ranks");
        Map<String, String> m = new HashMap<>();
        if (e != null && e.isJsonObject())
            for (var en : e.getAsJsonObject().entrySet()) if (en.getValue().isJsonPrimitive()) m.put(en.getKey(), en.getValue().getAsString());
        ranks = m;
    }

    private static void loadUsers() {
        JsonElement e = Net.get("users");
        List<User> l = new ArrayList<>();
        if (e != null && e.isJsonObject())
            for (var en : e.getAsJsonObject().entrySet()) {
                if (!en.getValue().isJsonObject()) continue;
                JsonObject o = en.getValue().getAsJsonObject();
                pl.holovis.CustomNametag.Data tag = null;
                if (o.has("tag") && o.get("tag").isJsonObject()) {
                    JsonObject t = o.getAsJsonObject("tag");
                    tag = new pl.holovis.CustomNametag.Data(cut(str(t, "l1")), cut(str(t, "l2")), (int) num(t, "icon"),
                            Math.max(1f, Math.min(5f, num(t, "size") / 10f)), (int) num(t, "font"), (int) num(t, "color"), (int) num(t, "mode"));
                }
                l.add(new User(en.getKey(), str(o, "acc"), str(o, "mc"), str(o, "server"), str(o, "role"), num(o, "seen"), tag));
            }
        users = l;
    }

    private static void loadInbox(String me) {
        JsonElement e = Net.get("inbox/" + me);
        if (e == null || !e.isJsonObject()) return;
        for (var en : e.getAsJsonObject().entrySet()) {
            if (!en.getValue().isJsonObject() || !seenInbox.add(en.getKey())) continue;
            JsonObject o = en.getValue().getAsJsonObject();
            Incoming in = new Incoming(en.getKey(), str(o, "chat"), str(o, "from"), str(o, "fromName"), str(o, "fromMc"),
                    str(o, "text"), num(o, "ts"));
            MinecraftClient.getInstance().execute(() -> MessageToasts.add(in));
        }
    }

    private static void loadChat(String cid) {
        JsonElement e = Net.get("chats/" + cid);
        List<Msg> l = new ArrayList<>();
        if (e != null && e.isJsonObject())
            for (var en : e.getAsJsonObject().entrySet()) {
                if (!en.getValue().isJsonObject()) continue;
                JsonObject o = en.getValue().getAsJsonObject();
                l.add(new Msg(en.getKey(), str(o, "from"), str(o, "fromName"), str(o, "fromMc"), str(o, "to"), str(o, "text"),
                        num(o, "ts"), str(o, "status")));
            }
        l.sort(Comparator.comparing(Msg::id));
        Map<String, List<Msg>> m = new HashMap<>(chats);
        m.put(cid, l);
        chats = m;
    }

    // ------------------------------------------------------------------ dane dla GUI
    /** Gracze KosaVisuals na tym samym serwerze (widziani w ostatnich 90 s), bez Ciebie. */
    public static List<User> onServer() {
        String me = myUuid(), srv = server();
        long now = System.currentTimeMillis() + serverClockOffset;
        List<User> l = new ArrayList<>();
        for (User u : users) if (!u.uuid().equals(me) && u.server().equals(srv) && !srv.isEmpty() && now - u.seen() < 90_000) l.add(u);
        return l;
    }

    /** Czy gracz o tym UUID używa KosaVisuals (był online w ostatnich 2 minutach). */
    public static boolean isKosaUser(UUID id) {
        if (id == null) return false;
        String s = id.toString();
        long now = System.currentTimeMillis() + serverClockOffset;
        for (User u : users) if (u.uuid().equals(s) && now - u.seen() < 120_000) return true;
        return false;
    }

    public static String globalRank(String nick) {
        if (nick == null) return null;
        return ranks.get(nick.toLowerCase(Locale.ROOT));
    }

    public static void setRank(String nick, String rank) {
        String k = nick.toLowerCase(Locale.ROOT);
        Map<String, String> m = new HashMap<>(ranks);
        if (rank == null || rank.isEmpty()) m.remove(k); else m.put(k, rank);
        ranks = m;
        EX.execute(() -> { if (rank == null || rank.isEmpty()) Net.delete("ranks/" + k); else Net.put("ranks/" + k, "\"" + rank.replace("\"", "") + "\""); });
    }

    public static List<Msg> chat(String cid) { List<Msg> l = chats.get(cid); return l == null ? List.of() : l; }

    public static void watch(String cid) { watchedChat = cid; tChat = 0; }

    public static void send(String toUuid, String toName, String text) {
        String t = text.trim();
        if (t.isEmpty() || !available()) return;
        if (t.length() > 256) t = t.substring(0, 256);
        String me = myUuid(), cid = chatId(me, toUuid), acc = Accounts.name(), nick = myMc(), msg = t;
        EX.execute(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", me); m.put("fromName", acc); m.put("fromMc", nick); m.put("to", toUuid); m.put("toName", toName);
            m.put("text", msg); m.put("ts", sv()); m.put("status", "sent");
            String id = Net.push("chats/" + cid, m);
            if (id == null) return;
            Map<String, Object> in = new LinkedHashMap<>();
            in.put("chat", cid); in.put("from", me); in.put("fromName", acc); in.put("fromMc", nick); in.put("text", msg); in.put("ts", sv());
            Net.put("inbox/" + toUuid + "/" + id, in);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("a", acc.isEmpty() ? nick : acc); meta.put("b", toName); meta.put("last", sv());
            Net.put("chatmeta/" + cid, meta);
            loadChat(cid);
        });
    }

    public static void markRead(Incoming in) {
        String me = myUuid();
        EX.execute(() -> {
            Net.patch("chats/" + in.chat() + "/" + in.id(), Map.of("status", "read"));
            Net.delete("inbox/" + me + "/" + in.id());
        });
    }

    public static void ignore(Incoming in) {
        String me = myUuid();
        EX.execute(() -> Net.delete("inbox/" + me + "/" + in.id()));
    }

    /** Dla właściciela: lista wszystkich rozmów. */
    public static List<ChatMeta> allChats() { return allChats; }

    public static void loadAllChats() {
        EX.execute(() -> {
            JsonElement e = Net.get("chatmeta");
            List<ChatMeta> l = new ArrayList<>();
            if (e != null && e.isJsonObject())
                for (var en : e.getAsJsonObject().entrySet()) {
                    if (!en.getValue().isJsonObject()) continue;
                    JsonObject o = en.getValue().getAsJsonObject();
                    l.add(new ChatMeta(en.getKey(), str(o, "a"), str(o, "b"), num(o, "last")));
                }
            l.sort(Comparator.comparingLong(ChatMeta::last).reversed());
            allChats = l;
        });
    }
}
