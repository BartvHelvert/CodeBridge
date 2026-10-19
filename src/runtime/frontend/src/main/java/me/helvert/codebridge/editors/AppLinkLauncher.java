package me.helvert.codebridge.editors;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * What the operating system runs for a link under the editor's own scheme --
 * {@code vscodium://eamodio.gitlens/did-authenticate?code=...&codebridge=63344.<token>} -- once
 * {@code CodeBridgeSchemeHandler} has registered it: hands the link to the running IDE's built-in server,
 * whose port and the extension host's token the link carries, as {@code /codebridge/uri/<token>/...}.
 * <p>
 * Java with nothing but the JDK, run by the IDE's own runtime ({@code javaw}, so no console window
 * opens): it starts in a fraction of a second, with no IDE classes and no Kotlin runtime to load.
 */
public final class AppLinkLauncher {
    private static final String PARAM = "codebridge=";

    private AppLinkLauncher() {
    }

    /**
     * {@code args[0]} is the link; {@code args[1]}, the port to use for a link that names none.
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) return;
        URI link = new URI(args[0]);
        String port = args.length > 1 ? args[1] : null;
        // No token of ours: any web page could have made the link, so the IDE asks before it goes anywhere.
        String token = "-";
        List<String> rest = new ArrayList<>();
        String query = link.getRawQuery();
        if (query != null) {
            for (String part : query.split("&")) {
                if (part.startsWith(PARAM)) {
                    String[] value = part.substring(PARAM.length()).split("\\.", 2);
                    if (value.length == 2 && value[0].matches("\\d{1,5}") && value[1].matches("[0-9a-f]{1,64}")) {
                        port = value[0];
                        token = value[1];
                    }
                } else if (!part.isEmpty()) rest.add(part);
            }
        }
        if (port == null || !port.matches("\\d{1,5}") || link.getRawAuthority() == null) return;
        String target = "http://127.0.0.1:" + port + "/codebridge/uri/" + token + "/" + link.getRawAuthority()
                + (link.getRawPath() == null ? "" : link.getRawPath())
                + (rest.isEmpty() ? "" : "?" + String.join("&", rest));
        HttpURLConnection connection = (HttpURLConnection) URI.create(target).toURL().openConnection();
        connection.setConnectTimeout(5_000);
        // Answered once the extension has the link, which may wait on the user's say-so.
        connection.setReadTimeout(10 * 60_000);
        connection.getResponseCode();
        connection.disconnect();
    }
}
