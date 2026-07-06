// Wakili — native shell around the remote-agent web UI.
//
// First run: scan the QR the gateway prints (it encodes http://<ip>:<port>/?t=<token>)
// or type the address by hand. The URL is persisted; every later launch goes
// straight into the web UI in a full-screen WebView. The web app itself captures
// the ?t= token into localStorage (domStorageEnabled keeps it across launches).

import { useCallback, useEffect, useRef, useState } from "react";
import {
  ActivityIndicator,
  BackHandler,
  Image,
  KeyboardAvoidingView,
  Linking,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider, SafeAreaView } from "react-native-safe-area-context";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { CameraView, useCameraPermissions } from "expo-camera";
import { WebView } from "react-native-webview";

const SERVER_KEY = "wakili.server";

const looksLikeServerUrl = (s) => /^https?:\/\/\S+/i.test((s || "").trim());

export default function App() {
  return (
    <SafeAreaProvider>
      <Root />
    </SafeAreaProvider>
  );
}

function Root() {
  const [server, setServer] = useState(null); // null = loading, "" = none saved
  const [scanning, setScanning] = useState(false);
  const [manual, setManual] = useState("");
  const [loadError, setLoadError] = useState("");
  const [retry, setRetry] = useState(0);
  const [permission, requestPermission] = useCameraPermissions();
  const webRef = useRef(null);
  const canGoBackRef = useRef(false);
  const scannedRef = useRef(false);

  useEffect(() => {
    AsyncStorage.getItem(SERVER_KEY).then((v) => setServer(v || ""));
  }, []);

  // Android back button. Ask the web UI first: if it has an open surface (sidebar
  // drawer, a list, a menu, a modal) it closes the top-most one and back stops
  // there. It replies via onMessage; only when nothing was open do we fall back
  // to WebView history, then exiting the app. The round-trip is async, so we
  // consume the event here and act on the reply below.
  useEffect(() => {
    const sub = BackHandler.addEventListener("hardwareBackPress", () => {
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
  }, []);

  const connect = useCallback((url) => {
    const u = url.trim();
    if (!looksLikeServerUrl(u)) return;
    AsyncStorage.setItem(SERVER_KEY, u);
    setLoadError("");
    setScanning(false);
    setServer(u);
  }, []);

  const startScan = useCallback(async () => {
    scannedRef.current = false;
    if (!permission?.granted) {
      const r = await requestPermission();
      if (!r.granted) return;
    }
    setScanning(true);
  }, [permission, requestPermission]);

  const forget = useCallback(() => {
    AsyncStorage.removeItem(SERVER_KEY);
    setLoadError("");
    setServer("");
  }, []);

  if (server === null) {
    return (
      <View style={[styles.fill, styles.center]}>
        <StatusBar style="light" />
        <ActivityIndicator color="#3b82f6" size="large" />
      </View>
    );
  }

  if (scanning) {
    return (
      // Safe area around the whole page — otherwise the camera (and anything
      // drawn over it) sits underneath the status bar.
      <SafeAreaView style={styles.fill}>
        <StatusBar style="light" />
        <View style={styles.fill}>
          <CameraView
            style={StyleSheet.absoluteFill}
            barcodeScannerSettings={{ barcodeTypes: ["qr"] }}
            onBarcodeScanned={({ data }) => {
              if (scannedRef.current || !looksLikeServerUrl(data)) return;
              scannedRef.current = true;
              connect(data);
            }}
          />
          <View style={styles.scanOverlay} pointerEvents="box-none">
            <Text style={styles.scanHint}>
              Point at the QR code shown on your computer
            </Text>
            <Pressable style={styles.btnGhost} onPress={() => setScanning(false)}>
              <Text style={styles.btnGhostText}>Cancel</Text>
            </Pressable>
          </View>
        </View>
      </SafeAreaView>
    );
  }

  if (!server || loadError) {
    return (
      <SafeAreaView style={styles.fill}>
        <StatusBar style="light" />
        <KeyboardAvoidingView behavior="padding" style={styles.fill}>
          <ScrollView
            style={styles.fill}
            contentContainerStyle={[styles.center, styles.pad, styles.grow]}
            keyboardShouldPersistTaps="handled"
            showsVerticalScrollIndicator={false}
          >
          <Image source={require("./assets/splash-icon.png")} style={styles.logo} />
          <Text style={styles.title}>Wakili</Text>
          {loadError ? (
            <Text style={styles.sub}>
              Couldn't reach the computer ({loadError}). Is the gateway running and
              the phone on the same network / VPN?
            </Text>
          ) : (
            <Text style={styles.sub}>
              Start the gateway on your computer, then scan the QR code it shows.
            </Text>
          )}
          {loadError ? (
            <Pressable
              style={styles.btn}
              onPress={() => {
                setLoadError("");
                setRetry((n) => n + 1);
              }}
            >
              <Text style={styles.btnText}>Try again</Text>
            </Pressable>
          ) : null}
          <Pressable style={styles.btn} onPress={startScan}>
            <Text style={styles.btnText}>Scan QR code</Text>
          </Pressable>
          {loadError ? (
            <Pressable style={styles.btnGhost} onPress={forget}>
              <Text style={styles.btnGhostText}>Connect to a different computer</Text>
            </Pressable>
          ) : null}
          <View style={styles.row}>
            <TextInput
              style={styles.input}
              value={manual}
              onChangeText={setManual}
              placeholder="or type: http://192.168.1.10:8730/?t=..."
              placeholderTextColor="#6b6b74"
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              onSubmitEditing={() => connect(manual)}
            />
            <Pressable
              style={[styles.btnSmall, !looksLikeServerUrl(manual) && styles.btnDisabled]}
              disabled={!looksLikeServerUrl(manual)}
              onPress={() => connect(manual)}
            >
              <Text style={styles.btnText}>Go</Text>
            </Pressable>
          </View>
          </ScrollView>
        </KeyboardAvoidingView>
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
        key={`${server}#${retry}`}
        source={{ uri: server }}
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
        onError={({ nativeEvent }) =>
          setLoadError(nativeEvent.description || "network error")
        }
        onHttpError={({ nativeEvent }) => {
          if (nativeEvent.statusCode >= 500) setLoadError(`HTTP ${nativeEvent.statusCode}`);
        }}
        renderLoading={() => (
          <View style={[StyleSheet.absoluteFill, styles.center, { backgroundColor: "#18181b" }]}>
            <ActivityIndicator color="#3b82f6" size="large" />
          </View>
        )}
        startInLoadingState
      />
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: "#18181b" },
  center: { alignItems: "center", justifyContent: "center" },
  grow: { flexGrow: 1 },
  pad: { padding: 24 },
  web: { flex: 1, backgroundColor: "#18181b" },
  logo: { width: 96, height: 96, marginBottom: 12 },
  title: { color: "#ececec", fontSize: 28, fontWeight: "700", marginBottom: 8 },
  sub: { color: "#a0a0aa", fontSize: 15, textAlign: "center", marginBottom: 24, lineHeight: 22 },
  btn: {
    backgroundColor: "#3b82f6",
    borderRadius: 12,
    paddingVertical: 14,
    paddingHorizontal: 32,
    marginBottom: 12,
    alignSelf: "stretch",
    alignItems: "center",
  },
  btnSmall: {
    backgroundColor: "#3b82f6",
    borderRadius: 10,
    paddingVertical: 12,
    paddingHorizontal: 18,
    marginLeft: 8,
  },
  btnDisabled: { opacity: 0.4 },
  btnText: { color: "#fff", fontSize: 16, fontWeight: "600" },
  btnGhost: { padding: 14, alignItems: "center" },
  btnGhostText: { color: "#a0a0aa", fontSize: 15 },
  row: { flexDirection: "row", alignItems: "center", alignSelf: "stretch", marginTop: 8 },
  input: {
    flex: 1,
    backgroundColor: "#242428",
    borderRadius: 10,
    color: "#ececec",
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 14,
  },
  scanOverlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: "flex-end",
    alignItems: "center",
    paddingBottom: 48,
  },
  scanHint: {
    color: "#fff",
    fontSize: 16,
    textAlign: "center",
    backgroundColor: "rgba(0,0,0,.55)",
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderRadius: 12,
    marginBottom: 16,
  },
});
