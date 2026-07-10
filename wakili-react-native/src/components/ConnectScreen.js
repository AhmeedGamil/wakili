// The hosts page. One surface, identical everywhere it appears — the ONLY
// difference is that after a connection failure it also shows the error text:
//   - first run (nothing saved): scan QR / type an address
//   - active host unreachable: error text + the same page
//   - opened on demand (Settings → Connection → Add or change the host),
//     overlaying the live session.
// The saved hosts live in a DROPDOWN labeled "Current host": collapsed it's a
// single row (the selection); expanded, the options render in an absolutely
// positioned box that FLOATS OVER the buttons below — opening it never shifts
// Connect / Scan QR, no matter how many hosts are saved. Connect acts on the
// selection — retrying the current host and switching to another are the same
// gesture. Each option has a two-tap Remove (so a token isn't lost by a slip).
// Names are derived (hostname-lan / hostname-tailscale, see networks.js).

import { useState } from "react";
import {
  Image,
  KeyboardAvoidingView,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from "react-native";
import { colors } from "../theme";
import { hostOf, looksLikeServerUrl } from "../networks";

export function ConnectScreen({ networks, activeId, error, showBack, onPick, onScan, onManual, onBack, onRemove }) {
  const [manual, setManual] = useState("");
  const [selectedId, setSelectedId] = useState(activeId); // Connect acts on this
  const [open, setOpen] = useState(false);                // dropdown expanded?
  const [armedId, setArmedId] = useState(null);           // remove needs a second tap

  const selected = networks.find((n) => n.id === selectedId) || networks[0] || null;

  return (
    <View style={styles.fill}>
      <KeyboardAvoidingView behavior="padding" style={styles.fill}>
        <ScrollView
          style={styles.fill}
          contentContainerStyle={[styles.center, styles.pad, styles.grow]}
          keyboardShouldPersistTaps="handled"
          showsVerticalScrollIndicator={false}
        >
          <Image source={require("../../assets/splash-icon.png")} style={styles.logo} />
          <Text style={styles.title}>Wakili</Text>
          {error ? (
            <Text style={styles.sub}>
              Couldn't reach the host ({error}). Is the gateway running and the
              phone on the same network / VPN?
            </Text>
          ) : networks.length ? (
            <Text style={styles.sub}>
              Select a host and tap Connect, or add a new one.
            </Text>
          ) : (
            <Text style={styles.sub}>
              Start the gateway on your computer, then scan the QR code it shows.
            </Text>
          )}

          {selected ? (
            <View style={styles.listWrap}>
              <Text style={styles.listLabel}>Current host</Text>

              {/* Closed state: one row showing the selection */}
              <Pressable
                style={[styles.select, open && styles.selectOpen]}
                onPress={() => { setOpen(!open); setArmedId(null); }}
              >
                <View style={styles.netMain}>
                  <Text style={styles.netName} numberOfLines={1}>{selected.name}</Text>
                  <Text style={styles.netHost} numberOfLines={1}>{hostOf(selected.url)}</Text>
                </View>
                <Text style={styles.chevron}>{open ? "▲" : "▼"}</Text>
              </Pressable>

              {/* Open state: the options, in a bounded scrollable box */}
              {open ? (
                <View style={styles.menu}>
                  <ScrollView style={styles.menuScroll} nestedScrollEnabled keyboardShouldPersistTaps="handled">
                    {networks.map((n) => (
                      <Pressable
                        key={n.id}
                        style={[styles.option, n.id === selected.id && styles.optionSelected]}
                        onPress={() => { setSelectedId(n.id); setOpen(false); setArmedId(null); }}
                      >
                        <View style={styles.netMain}>
                          <Text style={styles.netName} numberOfLines={1}>{n.name}</Text>
                          <Text style={styles.netHost} numberOfLines={1}>{hostOf(n.url)}</Text>
                        </View>
                        {n.id === activeId ? <Text style={styles.current}>current</Text> : null}
                        <Pressable
                          hitSlop={10}
                          onPress={() => {
                            if (armedId === n.id) { setArmedId(null); if (onRemove) onRemove(n.id); }
                            else setArmedId(n.id);
                          }}
                        >
                          <Text style={styles.remove}>{armedId === n.id ? "Sure?" : "✕"}</Text>
                        </Pressable>
                      </Pressable>
                    ))}
                  </ScrollView>
                </View>
              ) : null}
            </View>
          ) : null}

          {selected ? (
            <Pressable style={styles.btn} onPress={() => onPick(selected.id)}>
              <Text style={styles.btnText}>Connect</Text>
            </Pressable>
          ) : null}

          <Pressable style={selected ? styles.btnSecondary : styles.btn} onPress={onScan}>
            <Text style={selected ? styles.btnSecondaryText : styles.btnText}>Scan QR code</Text>
          </Pressable>
          {showBack ? (
            <Pressable style={styles.btnGhost} onPress={onBack}>
              <Text style={styles.btnGhostText}>Back</Text>
            </Pressable>
          ) : null}

          <View style={styles.row}>
            <TextInput
              style={styles.input}
              value={manual}
              onChangeText={setManual}
              placeholder="or type: http://192.168.1.10:8730/?t=..."
              placeholderTextColor={colors.subtle}
              autoCapitalize="none"
              autoCorrect={false}
              keyboardType="url"
              onSubmitEditing={() => onManual(manual)}
            />
            <Pressable
              style={[styles.btnSmall, !looksLikeServerUrl(manual) && styles.btnDisabled]}
              disabled={!looksLikeServerUrl(manual)}
              onPress={() => onManual(manual)}
            >
              <Text style={styles.btnText}>Go</Text>
            </Pressable>
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: colors.bg },
  center: { alignItems: "center", justifyContent: "center" },
  grow: { flexGrow: 1 },
  pad: { padding: 24 },
  logo: { width: 96, height: 96, marginBottom: 12 },
  title: { color: colors.text, fontSize: 28, fontWeight: "700", marginBottom: 8 },
  sub: { color: colors.muted, fontSize: 15, textAlign: "center", marginBottom: 24, lineHeight: 22 },
  // zIndex lifts the whole wrap above the buttons that follow it, so the
  // absolutely-positioned menu draws over them instead of pushing them down.
  listWrap: { alignSelf: "stretch", marginBottom: 12, zIndex: 20 },
  listLabel: { color: colors.subtle, fontSize: 13, marginBottom: 8, textTransform: "uppercase", letterSpacing: 0.5 },
  select: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: colors.card,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: colors.border,
    paddingVertical: 12,
    paddingHorizontal: 14,
  },
  selectOpen: { borderColor: colors.accent, borderBottomLeftRadius: 0, borderBottomRightRadius: 0 },
  chevron: { color: colors.muted, fontSize: 12, marginLeft: 10 },
  menu: {
    // Floats below the select row, over whatever follows — no layout shift.
    position: "absolute",
    top: "100%",
    left: 0,
    right: 0,
    zIndex: 30,
    elevation: 8, // Android: draw above siblings (zIndex alone isn't enough with a WebView around)
    backgroundColor: colors.card,
    borderWidth: 1,
    borderTopWidth: 0,
    borderColor: colors.accent,
    borderBottomLeftRadius: 12,
    borderBottomRightRadius: 12,
    overflow: "hidden",
  },
  menuScroll: { maxHeight: 240 },
  option: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  optionSelected: { backgroundColor: colors.bg },
  netMain: { flex: 1, minWidth: 0, marginRight: 10 },
  netName: { color: colors.text, fontSize: 16, fontWeight: "600" },
  netHost: { color: colors.subtle, fontSize: 13, marginTop: 2 },
  current: { color: colors.accent, fontSize: 12, fontWeight: "700", marginRight: 12, textTransform: "uppercase", letterSpacing: 0.5 },
  remove: { color: colors.danger, fontSize: 14, fontWeight: "700", paddingHorizontal: 6 },
  btn: {
    backgroundColor: colors.accent,
    borderRadius: 12,
    paddingVertical: 14,
    paddingHorizontal: 32,
    marginBottom: 12,
    alignSelf: "stretch",
    alignItems: "center",
  },
  btnSecondary: {
    backgroundColor: colors.card,
    borderRadius: 12,
    paddingVertical: 14,
    paddingHorizontal: 32,
    marginBottom: 12,
    alignSelf: "stretch",
    alignItems: "center",
  },
  btnSecondaryText: { color: colors.text, fontSize: 16, fontWeight: "600" },
  btnSmall: {
    backgroundColor: colors.accent,
    borderRadius: 10,
    paddingVertical: 12,
    paddingHorizontal: 18,
    marginLeft: 8,
  },
  btnDisabled: { opacity: 0.4 },
  btnText: { color: "#fff", fontSize: 16, fontWeight: "600" },
  btnGhost: { padding: 14, alignItems: "center" },
  btnGhostText: { color: colors.muted, fontSize: 15 },
  row: { flexDirection: "row", alignItems: "center", alignSelf: "stretch", marginTop: 8 },
  input: {
    flex: 1,
    backgroundColor: colors.card,
    borderRadius: 10,
    color: colors.text,
    paddingHorizontal: 14,
    paddingVertical: 12,
    fontSize: 14,
  },
});
