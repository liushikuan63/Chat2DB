package ai.chat2db.community.jcef.frame;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** Admission point shared by every native action and backend bridge request. */
abstract class TrustedJcefQueryHandler extends CefMessageRouterHandlerAdapter {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private final Supplier<CefBrowser> applicationBrowser;
    private final Supplier<String> frontendUrl;

    TrustedJcefQueryHandler(Supplier<CefBrowser> applicationBrowser, Supplier<String> frontendUrl) {
        this.applicationBrowser = applicationBrowser;
        this.frontendUrl = frontendUrl;
    }

    @Override
    public final boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
            boolean persistent, CefQueryCallback callback) {
        // CefFrame is only valid inside onQuery. Inspect it synchronously, before enqueuing work
        // or setting privileged request context; rejected queries must not reach another router.
        if (!isTrustedSource(browser, frame)) {
            callback.failure(403, "Native requests are allowed only from the application frontend.");
            return true;
        }
        return handleTrustedQuery(browser, frame, queryId, request, persistent, callback);
    }

    private boolean isTrustedSource(CefBrowser browser, CefFrame frame) {
        try {
            if (browser == null || browser != applicationBrowser.get()
                    || frame == null || !frame.isValid() || !frame.isMain()) {
                return false;
            }
            String configured = frontendUrl.get();
            String current = frame.getURL();
            if (configured == null || current == null) {
                return false;
            }
            URI trusted = new URI(configured);
            URI source = new URI(current);
            if (trusted.isOpaque() || source.isOpaque() || trusted.getUserInfo() != null
                    || source.getUserInfo() != null || trusted.getScheme() == null
                    || !trusted.getScheme().equalsIgnoreCase(source.getScheme())) {
                return false;
            }
            if ("file".equalsIgnoreCase(trusted.getScheme())) {
                if (trusted.getAuthority() != null || source.getAuthority() != null) {
                    return false;
                }
                return filePath(trusted).equals(filePath(source));
            }
            if (!"http".equalsIgnoreCase(trusted.getScheme()) && !"https".equalsIgnoreCase(trusted.getScheme())) {
                return false;
            }
            String host = trusted.getHost();
            return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    && host.equalsIgnoreCase(source.getHost()) && effectivePort(trusted) == effectivePort(source);
        } catch (RuntimeException | URISyntaxException invalidOrDisposedSource) {
            return false;
        }
    }

    private static Path filePath(URI uri) throws URISyntaxException {
        // Hash routes and query strings do not change the packaged document. Decode through URI
        // rather than prefix matching, which would also accept a sibling file or remote authority.
        return Path.of(new URI("file", null, uri.getPath(), null, null)).toAbsolutePath().normalize();
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    protected abstract boolean handleTrustedQuery(CefBrowser browser, CefFrame frame, long queryId,
            String request, boolean persistent, CefQueryCallback callback);
}
