package com.ahmedgamil.wakili.feature.connect

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButton
import com.ahmedgamil.wakili.core.designsystem.component.WakiliButtonStyle
import com.ahmedgamil.wakili.core.designsystem.component.WakiliCard
import com.ahmedgamil.wakili.core.designsystem.component.WakiliTextField
import com.ahmedgamil.wakili.core.designsystem.icon.WakiliIcons
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliDimens
import com.ahmedgamil.wakili.core.designsystem.theme.WakiliTheme
import com.ahmedgamil.wakili.core.designsystem.R as DesignR

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConnectViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = WakiliTheme.colors

    LaunchedEffect(uiState.connected) {
        if (uiState.connected) onConnected()
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.onScanRequested()
    }

    if (uiState.scanning) {
        Box(modifier = modifier.fillMaxSize()) {
            QrScanner(
                onQr = viewModel::onQrScanned,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = WakiliDimens.Space48),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
            ) {
                Text(
                    text = stringResource(R.string.connect_scan_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                )
                WakiliButton(
                    text = stringResource(R.string.connect_cancel),
                    onClick = viewModel::onScanCancelled,
                    style = WakiliButtonStyle.Ghost,
                    icon = WakiliIcons.X,
                )
            }
        }
        return
    }

    // .lg-overlay / .lg-form — centered login card on the app background
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(WakiliDimens.Space20),
        contentAlignment = Alignment.Center,
    ) {
        WakiliCard(
            radius = WakiliDimens.RadiusPanel,
            contentPadding = WakiliDimens.Space24,
            modifier = Modifier.width(WakiliDimens.LoginFormWidth),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WakiliDimens.Space12),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Image(
                    painter = painterResource(DesignR.drawable.wakili_logo),
                    contentDescription = null,
                    modifier = Modifier.size(WakiliDimens.LogoSize),
                )
                // .lg-title
                Text(
                    text = stringResource(R.string.connect_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
                // .lg-sub
                Text(
                    text = stringResource(R.string.connect_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    textAlign = TextAlign.Center,
                )
                uiState.error?.let { error ->
                    // .lg-err
                    Text(
                        text = stringResource(
                            when (error) {
                                ConnectError.INVALID_URL -> R.string.connect_error_invalid
                                ConnectError.UNAUTHORIZED -> R.string.connect_error_unauthorized
                                ConnectError.UNREACHABLE -> R.string.connect_error_unreachable
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.danger,
                        textAlign = TextAlign.Center,
                    )
                }
                if (uiState.connecting) {
                    CircularProgressIndicator(color = colors.accent)
                    Text(
                        text = stringResource(R.string.connect_connecting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                } else {
                    WakiliButton(
                        text = stringResource(R.string.connect_scan_qr),
                        onClick = { cameraPermission.launch(Manifest.permission.CAMERA) },
                        style = WakiliButtonStyle.Primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(WakiliDimens.Space4))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WakiliDimens.Space8),
                    ) {
                        WakiliTextField(
                            value = uiState.manualUrl,
                            onValueChange = viewModel::onManualUrlChange,
                            modifier = Modifier.weight(1f),
                            placeholder = stringResource(R.string.connect_manual_hint),
                            singleLine = true,
                        )
                        WakiliButton(
                            text = stringResource(R.string.connect_go),
                            onClick = viewModel::onSubmitManual,
                            enabled = uiState.canSubmit,
                            style = WakiliButtonStyle.Default,
                        )
                    }
                }
            }
        }
    }
}
