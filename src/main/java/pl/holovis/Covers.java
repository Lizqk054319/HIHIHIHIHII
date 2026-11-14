package pl.holovis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Okładki do karty Spotify. Źródła: okładka z Windows (SMTC, jeśli Spotify ją podaje),
 * a gdy jej nie ma - wyszukiwanie po wykonawcy i tytule w publicznym katalogu iTunes (obrazek PNG).
 */
public final class Covers {
    private Covers() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Pattern ART = Pattern.compile("\"artworkUrl100\"\\s*:\\s*\"([^\"]+)\"");

    /** Gotowy obrazek PNG (bajty) i licznik zmian. */
    public static volatile byte[] png;
    public static volatile int stamp;
    private static String key = "";
    private static volatile boolean fromSystem;

    private static void offer(byte[] b, boolean system) {
        if (b == null || b.length < 8) return;
        // PNG ma sygnaturę 0x89 'P' 'N' 'G'; inne formaty próbujemy przekonwertować
        if (!(b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G')) b = toPng(b);
        if (b == null) return;
        png = b;
        fromSystem = system;
        stamp++;
    }

    private static byte[] toPng(byte[] b) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(b));
            if (img == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Wywoływane, gdy most Windows zapisał okładkę do pliku. */
    public static void fromFile() {
        try { offer(Files.readAllBytes(Spotify.COVER), true); } catch (Exception ignored) {}
    }

    /** Nowy utwór: szukamy okładki w sieci (chyba że Windows już ją dał). */
    public static void request(String artist, String title) {
        String k = artist + "\t" + title;
        synchronized (Covers.class) {
            if (k.equals(key)) return;
            key = k;
        }
        fromSystem = false;
        png = null;
        stamp++;
        Thread t = new Thread(() -> load(k, artist, title), "holovis-cover");
        t.setDaemon(true);
        t.start();
    }

    private static void load(String k, String artist, String title) {
        try {
            Thread.sleep(1200);   // dajemy chwilę okładce z Windows
            if (!k.equals(key) || fromSystem) return;
            String q = URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://itunes.apple.com/search?media=music&entity=song&limit=1&term=" + q))
                    .timeout(Duration.ofSeconds(8)).header("User-Agent", "KosaVisuals").build();
            String body = HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
            Matcher m = ART.matcher(body);
            if (!m.find()) return;
            String url = m.group(1).replace("\\/", "/").replaceAll("\\d+x\\d+bb\\.[a-z]+$", "600x600bb.png");
            HttpRequest img = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "KosaVisuals").build();
            byte[] b = HTTP.send(img, HttpResponse.BodyHandlers.ofByteArray()).body();
            if (!k.equals(key) || fromSystem) return;
            offer(b, false);
        } catch (Exception e) {
            System.err.println("[KosaVisuals] Okładka: " + e);
        }
    }
}
