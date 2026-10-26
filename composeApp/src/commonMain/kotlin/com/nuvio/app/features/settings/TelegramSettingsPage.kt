package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogOption
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.features.telegram.TelegramAuthorizationMode
import com.nuvio.app.features.telegram.TelegramCacheSize
import com.nuvio.app.features.telegram.TelegramRepository
import com.nuvio.app.features.telegram.TelegramUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.telegram_cache_clear
import nuvio.composeapp.generated.resources.telegram_cache_exact_bytes
import nuvio.composeapp.generated.resources.telegram_cache_limit_description
import nuvio.composeapp.generated.resources.telegram_cache_limit_title
import nuvio.composeapp.generated.resources.telegram_cache_measuring
import nuvio.composeapp.generated.resources.telegram_cache_size_16_gb
import nuvio.composeapp.generated.resources.telegram_cache_size_2_gb
import nuvio.composeapp.generated.resources.telegram_cache_size_4_gb
import nuvio.composeapp.generated.resources.telegram_cache_size_8_gb
import nuvio.composeapp.generated.resources.telegram_cache_title
import nuvio.composeapp.generated.resources.telegram_cache_unavailable
import nuvio.composeapp.generated.resources.telegram_code_label
import nuvio.composeapp.generated.resources.telegram_connect
import nuvio.composeapp.generated.resources.telegram_connected_as
import nuvio.composeapp.generated.resources.telegram_disconnect
import nuvio.composeapp.generated.resources.telegram_email_code_label
import nuvio.composeapp.generated.resources.telegram_email_label
import nuvio.composeapp.generated.resources.telegram_missing_credentials
import nuvio.composeapp.generated.resources.telegram_password_label
import nuvio.composeapp.generated.resources.telegram_phone_label
import nuvio.composeapp.generated.resources.telegram_section_account
import nuvio.composeapp.generated.resources.telegram_section_storage
import nuvio.composeapp.generated.resources.telegram_starting
import nuvio.composeapp.generated.resources.telegram_unsupported
import nuvio.composeapp.generated.resources.action_done
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.telegramSettingsContent(
    isTablet: Boolean,
    uiState: TelegramUiState,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.telegram_section_account),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TelegramAccountCard(isTablet = isTablet, uiState = uiState)
            }
        }
    }

    if (uiState.isConnected) {
        item {
            SettingsSection(
                title = stringResource(Res.string.telegram_section_storage),
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    TelegramCacheCard(isTablet = isTablet, uiState = uiState)
                }
            }
        }
    }
}

@Composable
private fun TelegramAccountCard(
    isTablet: Boolean,
    uiState: TelegramUiState,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var input by rememberSaveable(uiState.mode) { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (uiState.mode) {
            TelegramAuthorizationMode.Ready -> {
                Text(
                    text = stringResource(
                        Res.string.telegram_connected_as,
                        uiState.displayName ?: uiState.username ?: "Telegram",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = TelegramRepository::logOut, enabled = !uiState.isBusy) {
                    Text(stringResource(Res.string.telegram_disconnect))
                }
            }
            TelegramAuthorizationMode.PhoneNumber,
            TelegramAuthorizationMode.Code,
            TelegramAuthorizationMode.EmailAddress,
            TelegramAuthorizationMode.EmailCode,
            TelegramAuthorizationMode.Password -> {
                val isPassword = uiState.mode == TelegramAuthorizationMode.Password
                if (isPassword) {
                    SettingsSecretTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = stringResource(Res.string.telegram_password_label),
                        modifier = Modifier.fillMaxWidth(),
                        isError = uiState.errorMessage != null,
                    )
                } else {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.errorMessage != null,
                        label = {
                            Text(
                                stringResource(
                                    when (uiState.mode) {
                                        TelegramAuthorizationMode.PhoneNumber -> Res.string.telegram_phone_label
                                        TelegramAuthorizationMode.EmailAddress -> Res.string.telegram_email_label
                                        TelegramAuthorizationMode.EmailCode -> Res.string.telegram_email_code_label
                                        else -> Res.string.telegram_code_label
                                    },
                                ),
                            )
                        },
                    )
                }
                uiState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(
                    enabled = input.isNotBlank() && !uiState.isBusy,
                    onClick = {
                        when (uiState.mode) {
                            TelegramAuthorizationMode.PhoneNumber -> TelegramRepository.submitPhoneNumber(input)
                            TelegramAuthorizationMode.Code -> TelegramRepository.submitCode(input)
                            TelegramAuthorizationMode.EmailAddress -> TelegramRepository.submitEmailAddress(input)
                            TelegramAuthorizationMode.EmailCode -> TelegramRepository.submitEmailCode(input)
                            TelegramAuthorizationMode.Password -> TelegramRepository.submitPassword(input)
                            else -> Unit
                        }
                    },
                ) {
                    Text(stringResource(Res.string.telegram_connect))
                }
            }
            TelegramAuthorizationMode.MissingCredentials -> TelegramInfoText(
                stringResource(Res.string.telegram_missing_credentials),
            )
            TelegramAuthorizationMode.Unsupported -> TelegramInfoText(
                stringResource(Res.string.telegram_unsupported),
            )
            else -> TelegramInfoText(
                uiState.errorMessage ?: stringResource(Res.string.telegram_starting),
            )
        }
    }
}

@Composable
private fun TelegramCacheCard(isTablet: Boolean, uiState: TelegramUiState) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var showLimitDialog by remember { mutableStateOf(false) }

    // The authoritative size costs a synchronous TDLib round-trip, so it is fetched only
    // while this page is on screen rather than on the background authorization poll.
    LaunchedEffect(Unit) {
        TelegramRepository.refreshCacheSize()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(Res.string.telegram_cache_title), fontWeight = FontWeight.Medium)
                Text(
                    text = when {
                        !uiState.cacheSizeMeasured -> stringResource(Res.string.telegram_cache_measuring)
                        uiState.cacheSizeBytes < 0L -> stringResource(Res.string.telegram_cache_unavailable)
                        else -> stringResource(
                            Res.string.telegram_cache_exact_bytes,
                            formatBytes(uiState.cacheSizeBytes),
                            groupDigits(uiState.cacheSizeBytes),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = TelegramRepository::clearCache) {
                Text(stringResource(Res.string.telegram_cache_clear))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.telegram_cache_limit_title),
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(
                        Res.string.telegram_cache_limit_description,
                        telegramCacheSizeLabel(uiState.cacheSizeLimit),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showLimitDialog = true }) {
                Text(telegramCacheSizeLabel(uiState.cacheSizeLimit))
            }
        }
    }

    if (showLimitDialog) {
        DialogSurface(
            onDismissRequest = { showLimitDialog = false },
            title = stringResource(Res.string.telegram_cache_limit_title),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TelegramCacheSize.entries.forEach { option ->
                    DialogOption(
                        text = telegramCacheSizeLabel(option),
                        selected = option == uiState.cacheSizeLimit,
                        onClick = {
                            TelegramRepository.setCacheSizeLimit(option)
                            showLimitDialog = false
                        },
                    )
                }
            }
            DialogButtons {
                DialogButton(
                    text = stringResource(Res.string.action_done),
                    onClick = { showLimitDialog = false },
                )
            }
        }
    }
}

@Composable
private fun telegramCacheSizeLabel(size: TelegramCacheSize): String = stringResource(
    when (size) {
        TelegramCacheSize.GB_2 -> Res.string.telegram_cache_size_2_gb
        TelegramCacheSize.GB_4 -> Res.string.telegram_cache_size_4_gb
        TelegramCacheSize.GB_8 -> Res.string.telegram_cache_size_8_gb
        TelegramCacheSize.GB_16 -> Res.string.telegram_cache_size_16_gb
    },
)

@Composable
private fun TelegramInfoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "${oneDecimal(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576L -> "${oneDecimal(bytes / 1_048_576.0)} MB"
    bytes >= 1_024L -> "${oneDecimal(bytes / 1_024.0)} KB"
    else -> "$bytes B"
}

/** Groups digits so an exact byte count stays readable in a small settings row. */
private fun groupDigits(value: Long): String {
    val digits = value.toString()
    val out = StringBuilder()
    digits.forEachIndexed { index, char ->
        if (index > 0 && (digits.length - index) % 3 == 0) out.append(',')
        out.append(char)
    }
    return out.toString()
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10.0).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}
