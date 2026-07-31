package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.ServerConfig

@Composable
fun SettingsCard(
    config: ServerConfig,
    isServerRunning: Boolean,
    onUpdateConfig: (
        ftpPort: Int,
        webPort: Int,
        isReadOnly: Boolean,
        requireAuth: Boolean,
        username: String,
        password: String,
        keepScreenOn: Boolean
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    var ftpPortText by remember(config.ftpPort) { mutableStateOf(config.ftpPort.toString()) }
    var webPortText by remember(config.webPort) { mutableStateOf(config.webPort.toString()) }
    var isReadOnly by remember(config.isReadOnly) { mutableStateOf(config.isReadOnly) }
    var requireAuth by remember(config.requireAuth) { mutableStateOf(config.requireAuth) }
    var username by remember(config.username) { mutableStateOf(config.username) }
    var password by remember(config.password) { mutableStateOf(config.password) }
    var keepScreenOn by remember(config.keepScreenOn) { mutableStateOf(config.keepScreenOn) }

    fun applyChanges() {
        val fPort = ftpPortText.toIntOrNull() ?: 2121
        val wPort = webPortText.toIntOrNull() ?: 8080
        onUpdateConfig(fPort, wPort, isReadOnly, requireAuth, username, password, keepScreenOn)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Server Configuration",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Ports Input Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = webPortText,
                    onValueChange = {
                        webPortText = it
                        applyChanges()
                    },
                    label = { Text("Web Port") },
                    enabled = !isServerRunning,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("web_port_input"),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = ftpPortText,
                    onValueChange = {
                        ftpPortText = it
                        applyChanges()
                    },
                    label = { Text("FTP Port") },
                    enabled = !isServerRunning,
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ftp_port_input"),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Read-Only Mode Switch
            SettingSwitchRow(
                title = "Read-Only Access Mode",
                subtitle = "Prevent connected devices from uploading or deleting files",
                checked = isReadOnly,
                enabled = !isServerRunning,
                testTag = "readonly_switch",
                onCheckedChange = {
                    isReadOnly = it
                    applyChanges()
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Password Auth Switch
            SettingSwitchRow(
                title = "Require Login Authentication",
                subtitle = "Require Username and Password to connect",
                checked = requireAuth,
                enabled = !isServerRunning,
                testTag = "auth_switch",
                onCheckedChange = {
                    requireAuth = it
                    applyChanges()
                }
            )

            if (requireAuth) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = {
                            username = it
                            applyChanges()
                        },
                        label = { Text("Username") },
                        enabled = !isServerRunning,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("username_input"),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            applyChanges()
                        },
                        label = { Text("Password") },
                        enabled = !isServerRunning,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("password_input"),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // Keep Screen Awake
            SettingSwitchRow(
                title = "Keep Server Awake",
                subtitle = "Maintain CPU wake lock while server is running",
                checked = keepScreenOn,
                enabled = !isServerRunning,
                testTag = "keep_awake_switch",
                onCheckedChange = {
                    keepScreenOn = it
                    applyChanges()
                }
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
    }
}
