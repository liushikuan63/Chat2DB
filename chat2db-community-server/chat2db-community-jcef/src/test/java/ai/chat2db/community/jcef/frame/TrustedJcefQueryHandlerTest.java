package ai.chat2db.community.jcef.frame;

import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedJcefQueryHandlerTest {
    @TempDir Path directory;

    @Test
    void packagedIndexAcceptsRoutesAndEscapedPathsButNoOtherFile() {
        String index = directory.resolve("app space/dist/index.html").toUri().toString();
        Harness harness = new Harness(index);
        harness.query(harness.browser, frame(index + "#/workspace", true, true));
        assertEquals(1, harness.dispatched.get());
        harness.query(harness.browser, frame(directory.resolve("app space/dist/other.html").toUri().toString(), true, true));
        harness.query(harness.browser, frame("file://external-host/dist/index.html", true, true));
        harness.query(harness.browser, frame("https://example.com/", true, true));
        assertEquals(1, harness.dispatched.get());
        assertEquals(3, harness.rejected.get());
    }

    @Test
    void devFrontendRequiresExactLoopbackOrigin() {
        Harness harness = new Harness("http://127.0.0.1:8889/");
        harness.query(harness.browser, frame("http://127.0.0.1:8889/workspace?tab=1", true, true));
        assertEquals(1, harness.dispatched.get());
        for (String url : new String[]{"http://127.0.0.1:8888/", "http://127.0.0.1.evil.test:8889/",
                "http://127.0.0.1:8889@evil.test/", "http://user@127.0.0.1:8889/",
                "https://127.0.0.1:8889/", "http://localhost:8889/", "data:text/html,hello", "not a URL"}) {
            harness.query(harness.browser, frame(url, true, true));
        }
        assertEquals(1, harness.dispatched.get());
        assertEquals(8, harness.rejected.get());
    }

    @Test
    void arbitraryConfiguredRemoteFrontendDoesNotGainNativePrivileges() {
        Harness harness = new Harness("https://example.com/");
        harness.query(harness.browser, frame("https://example.com/", true, true));
        assertEquals(0, harness.dispatched.get());
        assertEquals(1, harness.rejected.get());
    }

    @Test
    void deniesChildFramesInvalidFramesAndOtherBrowsersBeforeDispatch() {
        Harness harness = new Harness("http://localhost:8889/");
        harness.query(harness.browser, frame("http://localhost:8889/", false, true));
        harness.query(harness.browser, frame("http://localhost:8889/", true, false));
        harness.query(browser(), frame("http://localhost:8889/", true, true));
        harness.query(harness.browser, null);
        harness.query(null, frame("http://localhost:8889/", true, true));
        assertEquals(0, harness.dispatched.get());
        assertEquals(5, harness.rejected.get());
    }

    @Test
    void uninitializedOrUnavailableFrameFailsClosed() {
        Harness harness = new Harness(null);
        harness.query(harness.browser, frame("http://localhost:8889/", true, true));
        harness.trustedUrl.set("http://localhost:8889/");
        CefFrame unavailable = (CefFrame) Proxy.newProxyInstance(CefFrame.class.getClassLoader(),
                new Class<?>[]{CefFrame.class}, (proxy, method, args) -> { throw new IllegalStateException("disposed"); });
        harness.query(harness.browser, unavailable);
        assertEquals(0, harness.dispatched.get());
        assertEquals(2, harness.rejected.get());
    }

    @Test
    void acceptsIpv6LoopbackAndDefaultPortWithoutAcceptingDifferentOrigins() {
        Harness ipv6 = new Harness("http://[::1]:8889/");
        ipv6.query(ipv6.browser, frame("http://[::1]:8889/#/workspace", true, true));
        assertEquals(1, ipv6.dispatched.get());
        Harness defaultPort = new Harness("http://localhost/");
        defaultPort.query(defaultPort.browser, frame("http://localhost:80/workspace", true, true));
        assertEquals(1, defaultPort.dispatched.get());
    }

    private static final class Harness {
        final CefBrowser browser = browser();
        final AtomicReference<String> trustedUrl;
        final AtomicInteger dispatched = new AtomicInteger();
        final AtomicInteger rejected = new AtomicInteger();
        final TrustedJcefQueryHandler handler;
        Harness(String trustedUrl) {
            this.trustedUrl = new AtomicReference<>(trustedUrl);
            handler = new TrustedJcefQueryHandler(() -> browser, this.trustedUrl::get) {
                @Override protected boolean handleTrustedQuery(CefBrowser browser, CefFrame frame, long queryId,
                        String request, boolean persistent, CefQueryCallback callback) {
                    dispatched.incrementAndGet();
                    callback.success("ok");
                    return true;
                }
            };
        }
        void query(CefBrowser source, CefFrame frame) {
            assertTrue(handler.onQuery(source, frame, 42L, "arbitrary privileged request", false,
                    new CefQueryCallback() {
                        @Override public void success(String response) { }
                        @Override public void failure(int code, String message) {
                            assertEquals(403, code);
                            rejected.incrementAndGet();
                        }
                    }), "rejected queries must be consumed rather than offered to another handler");
        }
    }

    private static CefBrowser browser() {
        return (CefBrowser) Proxy.newProxyInstance(CefBrowser.class.getClassLoader(),
                new Class<?>[]{CefBrowser.class}, (proxy, method, args) -> { throw new UnsupportedOperationException(method.getName()); });
    }
    private static CefFrame frame(String url, boolean main, boolean valid) {
        return (CefFrame) Proxy.newProxyInstance(CefFrame.class.getClassLoader(), new Class<?>[]{CefFrame.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getURL" -> url;
                    case "isMain" -> main;
                    case "isValid" -> valid;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
