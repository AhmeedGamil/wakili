// Wakili — native shell around the wakili web UI.
//
// First run: scan the QR the gateway prints (it encodes http://<ip>:<port>/?t=<token>)
// or type the address by hand. The phone keeps a LIST of computers (each with
// its own token) in AsyncStorage — see src/networks.js — so the user can save
// home + work + laptop and switch between them without re-scanning. The active
// one is shown full-screen in a WebView; the web app captures the ?t= token
// into localStorage (domStorageEnabled keeps it across launches).
//
// Screens (src/components): ConnectScreen — THE hosts page (first-run setup,
// unreachable-host recovery, and on-demand management via Settings →
// Connection → "Add or change the host", where it overlays the live WebView so
// the session stays mounted underneath), ScanScreen (QR camera),
// LoadingOverlay ("Connecting to the host …").

import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, BackHandler, KeyboardAvoidingView, Linking, Platform, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import { useCameraPermissions } from "expo-camera";
import { WebView } from "react-native-webview";

import { colors } from "./src/theme";
import { loadNetworks, persist, upsert, probeName, looksLikeServerUrl, autoName } from "./src/networks";
import { LoadingOverlay } from "./src/components/LoadingOverlay";
import { ScanScreen } from "./src/components/ScanScreen";
import { ConnectScreen } from "./src/components/ConnectScreen";

export default function App() {
  return (
    <SafeAreaProvider>
      <Root />
    </SafeAreaProvider>
  );
}

function Root() {
  const [booted, setBooted] = useState(false);
  const [nets, setNets] = useState([]);          // saved computers [{ id, name, url }]
  const [activeId, setActiveId] = useState(null);
  const [scanning, setScanning] = useState(false);
  const [connectView, setConnectView] = useState(false); // computers page over the live session
  const [loadError, setLoadError] = useState("");
  const [webLoading, setWebLoading] = useState(true);
  const [retry, setRetry] = useState(0);
  const [permission, requestPermission] = useCameraPermissions();
  const webRef = useRef(null);
  const canGoBackRef = useRef(false);
  // Mirrors for async / event-listener callbacks that read the latest state.
  const activeIdRef = useRef(null);
  activeIdRef.current = activeId;
  const connectViewRef = useRef(false);
  connectViewRef.current = connectView;

  const active = nets.find((n) => n.id === activeId) || null;

  useEffect(() => {
    loadNetworks().then(({ networks, activeId: id }) => {
      setNets(networks);
      setActiveId(id);
      setBooted(true);
    });
  }, []);

  // Apply + persist a networks change in one place.
  const commit = (networks, nextActiveId) => {
    setNets(networks);
    setActiveId(nextActiveId);
    persist(networks, nextActiveId).catch(() => {});
  };

  // Fresh WebView for the active computer (also used to retry the current one).
  const reload = () => {
    setLoadError("");
    setConnectView(false);
    setWebLoading(true);
    setRetry((n) => n + 1);
  };

  // Connect to the selected host. Another one -> switch to it. The current
  // one -> retry if it errored, otherwise just close the overlay (no reload).
  const switchTo = (id) => {
    if (id === activeId) {
      if (loadError) reload();
      else setConnectView(false);
      return;
    }
    commit(nets, id);
    reload();
  };

  // Add (or refresh) a computer by URL and make it active. The name starts as
  // the URL's host; once the gateway answers /api/host it becomes the derived
  // "<hostname>-<path>" label (ahmedpc-lan / ahmedpc-tailscale).
  const addNetwork = (url) => {
    const u = (url || "").trim();
    if (!looksLikeServerUrl(u)) return;
    const { networks, id } = upsert(nets, u);
    commit(networks, id);
    setScanning(false);
    reload();
    probeName(u).then((hostname) => {
      if (!hostname) return;
      setNets((cur) => {
        const next = cur.map((n) => (n.id === id ? { ...n, name: autoName(n.url, hostname) } : n));
        persist(next, activeIdRef.current).catch(() => {});
        return next;
      });
    });
  };

  const removeNetwork = (id) => {
    const next = nets.filter((n) => n.id !== id);
    const nextActive = id === activeId ? (next.length ? next[0].id : null) : activeId;
    commit(next, nextActive);
    if (id === activeId) reload(); // removed the one we were on -> jump to the next
  };

  const startScan = useCallback(async () => {
    if (!permission?.granted) {
      const r = await requestPermission();
      if (!r.granted) return;
    }
    setScanning(true);
  }, [permission, requestPermission]);

  // Android back button. The computers-page overlay closes first; otherwise ask
  // the web UI: if it has an open surface (sidebar drawer, a list, a menu, a
  // modal) it closes the top-most one and back stops there. It replies via
  // onMessage; only when nothing was open do we fall back to WebView history,
  // then exiting the app. The round-trip is async, so we consume the event here
  // and act on the reply below.
  useEffect(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
      if (connectViewRef.current) { setConnectView(false); return true; }
      if (!webRef.current) return false; // setup / scan screens: default behavior
      webRef.current.injectJavaScript(`(function(){
        var handled = false;
        try { handled = !!(window.__wakiliBack && window.__wakiliBack()); } catch (e) {}
        if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage(JSON.stringify({ wakiliBack: true, handled: handled }));
      })(); true;`);
      return true;
    });
    return () => sub.remove();
  }, []);

  // Messages posted from the web UI.
  const onWebMessage = useCallback((data) => {
    let msg = null;
    try { msg = JSON.parse(data); } catch { return; }
    if (!msg) return;
    // Reply from window.__wakiliBack: if the web UI didn't close anything, run
    // the old fallback — WebView history if any, otherwise exit the app.
    if (msg.wakiliBack) {
      if (msg.handled) return;
      if (canGoBackRef.current && webRef.current) webRef.current.goBack();
      else BackHandler.exitApp();
      return;
    }
    // Download request: the file's ?dl=1 URL is served as an attachment, so
    // opening it hands the transfer to the system's Download Manager (which
    // shows its own "download complete" notification). The web UI shows a toast.
    if (msg.wakiliDownload && msg.wakiliDownload.url) {
      Linking.openURL(msg.wakiliDownload.url).catch(() => {});
      return;
    }
    // External link tapped in a message: open it in the system browser (a
    // WebView can't honor target="_blank").
    if (msg.wakiliOpenUrl && msg.wakiliOpenUrl.url) {
      Linking.openURL(msg.wakiliOpenUrl.url).catch(() => {});
      return;
    }
    // Settings → Connection → "Saved computers": show the computers page over
    // the live session (the WebView stays mounted underneath).
    if (msg.wakiliNetworks) {
      setConnectView(true);
      return;
    }
  }, []);

  if (!booted) {
    return (
      <View style={[styles.fill, styles.center]}>
        <StatusBar style="light" />
        <ActivityIndicator color={colors.accent} size="large" />
        <Text style={styles.bootText}>Starting Wakili…</Text>
      </View>
    );
  }

  if (scanning) {
    return <ScanScreen onScanned={addNetwork} onCancel={() => setScanning(false)} />;
  }

  const connectPage = (showBack) => (
    <ConnectScreen
      networks={nets}
      activeId={activeId}
      error={loadError}
      showBack={showBack}
      onBack={() => setConnectView(false)}
      onPick={switchTo}
      onScan={startScan}
      onManual={addNetwork}
      onRemove={removeNetwork}
    />
  );

  // No computer saved, or the active one failed to load: the computers page IS
  // the screen (nothing usable behind it).
  if (!active || loadError) {
    return (
      <SafeAreaView style={styles.fill}>
        <StatusBar style="light" />
        {connectPage(false)}
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.fill}>
      <StatusBar style="light" />
      {/* Android 15 edge-to-edge ignores adjustResize, so without this the
          keyboard makes the WebView PAN (topbar pushed off, unreachable).
          Padding the WebView by the keyboard height makes it truly resize.
          iOS WKWebView handles the keyboard itself — no behavior there. */}
      <KeyboardAvoidingView
        behavior={Platform.OS === "android" ? "padding" : undefined}
        style={styles.fill}
      >
        <WebView
          ref={webRef}
          key={`${active.url}#${retry}`}
          source={{ uri: active.url }}
          style={styles.web}
          originWhitelist={["*"]}
          domStorageEnabled
          javaScriptEnabled
          allowsBackForwardNavigationGestures
          pullToRefreshEnabled
          setSupportMultipleWindows={false}
          overScrollMode="never"
          setBuiltInZoomControls={false}
          setDisplayZoomControls={false}
          textZoom={100}
          applicationNameForUserAgent="WakiliApp"
          onNavigationStateChange={(nav) => {
            canGoBackRef.current = nav.canGoBack;
          }}
          onMessage={(e) => onWebMessage(e.nativeEvent.data)}
          onLoadStart={() => setWebLoading(true)}
          onLoadEnd={() => setWebLoading(false)}
          onError={({ nativeEvent }) => {
            setWebLoading(false);
            setLoadError(nativeEvent.description || "network error");
          }}
          onHttpError={({ nativeEvent }) => {
            if (nativeEvent.statusCode >= 500) setLoadError(`HTTP ${nativeEvent.statusCode}`);
          }}
        />
        {webLoading ? <LoadingOverlay name={active.name} /> : null}
        {connectView ? (
          <View style={StyleSheet.absoluteFill}>{connectPage(true)}</View>
        ) : null}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.bg },
  center: { alignItems: "center", justifyContent: "center" },
  web: { flex: 1, backgroundColor: colors.bg },
  bootText: { color: colors.muted, fontSize: 14, marginTop: 14 },
});
