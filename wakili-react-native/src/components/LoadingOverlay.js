// Full-screen overlay shown while the WebView loads the gateway page. Just the
// logo, the spinner, and which host it's connecting to — if the host is truly
// unreachable the WebView's error lands on the computers page, which has all
// the recovery options.

import { ActivityIndicator, Image, StyleSheet, Text, View } from "react-native";
import { colors } from "../theme";

export function LoadingOverlay({ name }) {
  return (
    <View style={[StyleSheet.absoluteFill, styles.wrap]}>
      <Image source={require("../../assets/splash-icon.png")} style={styles.logo} />
      <ActivityIndicator color={colors.accent} size="large" />
      <Text style={styles.msg}>Connecting to the host {name || ""}…</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { backgroundColor: colors.bg, alignItems: "center", justifyContent: "center", padding: 24 },
  logo: { width: 72, height: 72, marginBottom: 20 },
  msg: { color: colors.muted, fontSize: 15, marginTop: 16, textAlign: "center" },
});
