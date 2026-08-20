package com.ahmedgamil.wakili.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing / size / radius tokens — the px values used across public/app.css,
 * ported 1:1 (1 CSS px = 1 dp). Nothing in the UI layer should use a raw
 * dp literal; add a token here instead.
 */
object WakiliDimens {
    // ---- spacing scale (the gap/padding values app.css uses) ----
    val Space2 = 2.dp
    val Space3 = 3.dp
    val Space4 = 4.dp
    val Space6 = 6.dp
    val Space7 = 7.dp
    val Space8 = 8.dp
    val Space9 = 9.dp
    val Space10 = 10.dp
    val Space11 = 11.dp
    val Space12 = 12.dp
    val Space14 = 14.dp
    val Space16 = 16.dp
    val Space18 = 18.dp
    val Space20 = 20.dp
    val Space24 = 24.dp
    val Space32 = 32.dp
    val Space48 = 48.dp

    // ---- corner radii ----
    val RadiusXs = 5.dp // inline code
    val RadiusSm = 8.dp // segmented tab, small rows
    val RadiusMenuItem = 9.dp // .sess-pop button rows
    val RadiusMd = 10.dp // .btn, session rows, inputs
    val RadiusLg = 12.dp // .ep-row, .dd-head, tool cards
    val Radius = 14.dp // --radius, .add-menu, .file-card
    val RadiusCard = 16.dp // .msg.perm / .msg.ask / dock cards
    val RadiusPanel = 18.dp // popup panels, .slash-menu, .lg-form
    val RadiusBubble = 20.dp // user message bubble
    val RadiusComposer = 26.dp // .composer-bar

    // ---- borders ----
    val BorderThin = 1.dp
    val BorderThick = 2.dp // .think-body / .ask-mark
    val BorderHeavy = 3.dp // blockquote, diff gutters

    // ---- icons (Lucide line icons) ----
    val IconXs = 12.dp // .att-x svg
    val IconSm = 14.dp // .s-pending svg
    val IconMd = 15.dp // .term-cwd svg
    val Icon = 18.dp // .add-item svg, .att-ico, ~1.15em at base font
    val IconLg = 20.dp
    val IconXl = 24.dp // composer + button (ChatGPT-scale)

    // ---- controls ----
    // .switch / .md-switch visual size. The whole row is still the touch target.
    val SwitchWidth = 40.dp
    val SwitchHeight = 22.dp
    val SwitchKnob = 18.dp
    val SwitchKnobInset = 2.dp
    val SegmentedPadding = 3.dp // .appr-seg / .sv-toggle / .pick-tabs
    val ComposerButton = 36.dp // .btn.send / .btn.attach
    val FilesButton = 40.dp // .files-btn
    val RoundButton = 42.dp // .icon-btn.round
    val ControlRowHeight = 45.dp // dropdown headers and the Auto continue row
    val AskMark = 18.dp // .ask-mark
    val AskMarkInset = 3.dp
    val Swatch = 34.dp // accent swatch circle
    // --group-head-h: the sidebar's project header. The collapse half and the ➕
    // are both this tall, which makes the ➕ a square that matches it exactly —
    // and keeps each one's ripple inside its own button.
    val GroupHeadRow = 40.dp
    val BrandLogo = 40.dp // visible .side-brand logo frame
    const val BrandLogoAssetScale = 1.68f // removes transparent padding in the source bitmap
    val SwatchRing = 2.dp // .swatch.on ring width

    // ---- delete-in-progress spinners (.spinner-danger sizes) ----
    val SpinnerLg = 22.dp // Files grid overlay
    val SpinnerSm = 16.dp // .ft-file leading slot
    val SpinnerRow = 15.dp // .session row flag
    val AttachmentProgressRing = 34.dp // .att-ring
    val AttachmentProgressStroke = 4.dp

    // ---- indicator dots ----
    val BusyDot = 7.dp // .s-busy / .s-unread
    val StatusDot = 10.dp
    val TypingDot = 6.dp // .ti-dots span
    val AttachRemove = 20.dp // .att-x

    // ---- cards, thumbs, layout ----
    val AttachmentCard = 86.dp // .att-card
    val ThumbSm = 46.dp // .sf-thumb
    val ThumbLg = 110.dp // .ft-grid minmax / .ft-thumb height
    val ImageMaxHeight = 340.dp // .file-img / .att-msg.img img
    val FileCardMaxWidth = 300.dp // .file-card.img max-width
    val ContentMaxWidth = 760.dp // --maxw
    val ChatStickThreshold = 80.dp // web STICK_THRESHOLD before auto-follow disengages
    val SidebarWidth = 272.dp // --sidebar-w
    val SidebarBrandContentInset = 90.dp // pinned brand (logo row + host row); content starts below it with breathing room
    val SidebarTopFadeHeight = 104.dp // brand overlay below the status bar (covers the two-row brand + the content inset)
    val SidebarBottomFadeHeight = 48.dp // compact fade above the navigation bar
    val ComposerPickerGap = 88.dp // controls popover sits this far above the screen bottom (clears the composer)
    val SessionModelMaxWidth = 114.dp // .s-model max-width: 42% of the sidebar
    val ContextMenuWidth = 190.dp // .sess-pop — ONE width for every popover menu
    val LoginFormWidth = 320.dp // .lg-form
    val LogoSize = 96.dp
    val PermBodyMaxHeight = 96.dp // .perm-body
    val AskPaneMaxHeight = 340.dp // .dock .ask-pane inside the 60vh dock card, phone-scaled
    val ToolBodyMaxHeight = 380.dp // .tool-body
    val InputMaxHeight = 180.dp // #input
    val TermInputMaxHeight = 160.dp // .term-input
    val MenuMaxHeight = 264.dp // .slash-menu
    val ConnectHostMenuMaxHeight = 240.dp // react-native hosts dropdown
    val PickerMinWidth = 190.dp // .add-menu
    val PickerPopWidth = 290.dp // .picker-pop
    val PickerPopMaxHeight = 480.dp // .picker-pop max-height (~76vh)
    val FolderListHeight = 280.dp // folder-picker directory list
    val ToastBottomOffset = 92.dp // .toast bottom

    // ---- fractions (CSS percentage constraints) ----
    const val BubbleMaxWidthFraction = 0.85f // .bubble max-width: 85%
    const val ImageMsgMaxWidthFraction = 0.62f // .att-msg.img
    const val ChatImageLoadingAspect = 4f / 3f
    const val TopBarFadeSolidStop = 0.18f // short opaque lead-in before the top fade
    // The brand sits on this fade, so it stays fully opaque well past halfway and
    // only then falls off — rows scrolling under the logo disappear instead of
    // showing through it.
    const val SidebarTopFadeMidStop = 0.62f
    const val SidebarTopFadeMidAlpha = 0.94f

    // ---- alpha steps used by app.css ----
    const val AlphaSendDisabled = 0.28f // .btn.send:disabled
    const val AlphaDisabled = 0.45f // .fp-foot .btn:disabled
    const val AlphaBlocked = 0.55f // .composer.blocked / .ask-opt:disabled
    const val AlphaCurrent = 0.75f // .ep-row.current
    const val AlphaAccentWash = 0.14f // color-mix 14% accent backgrounds
    const val AlphaErrorWash = 0.12f // .diff-out.err
    const val AlphaDiffWash = 0.15f // .diff-add / .diff-del backgrounds
    const val AlphaMutedWash = 0.08f // .diff-out
    const val AlphaGreeting = 0.92f // .messages:empty::before
    const val AlphaSubtle = 0.64f // react-native subtle text relative to muted
    const val AlphaConnectDisabled = 0.4f // react-native disabled host action
}
