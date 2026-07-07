// Full-screen QR scanner. Fires onScanned exactly once with the URL the code
// encodes (http://<ip>:<port>/?t=<token>); anything that doesn't look like a
// server URL is ignored so random QR codes in view can't hijack the flow.

import { useRef } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaView } from "react-native-safe-area-context";
import { CameraView } from "expo-camera";
import { looksLikeServerUrl } from "../networks";

export function ScanScreen({ onScanned, onCancel }) {
  const scannedRef = useRef(false);
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
            onScanned(data);
          }}
        />
        <View style={styles.overlay} pointerEvents="box-none">
          <Text style={styles.hint}>Point at the QR code shown on your computer</Text>
          <Pressable style={styles.cancel} onPress={onCancel}>
            <Text style={styles.cancelText}>Cancel</Text>
          </Pressable>
        </View>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: "#18181b" },
  overlay: {
    ...StyleSheet.absoluteFillObject,
    justifyContent: "flex-end",
    alignItems: "center",
    paddingBottom: 48,
  },
  hint: {
    color: "#fff",
    fontSize: 16,
    textAlign: "center",
    backgroundColor: "rgba(0,0,0,.55)",
    paddingVertical: 10,
    paddingHorizontal: 18,
    borderRadius: 12,
    marginBottom: 16,
  },
  cancel: { padding: 14, alignItems: "center" },
  cancelText: { color: "#a0a0aa", fontSize: 15 },
});
