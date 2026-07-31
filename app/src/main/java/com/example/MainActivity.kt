package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.ServerStatus
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.ServerViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: ServerViewModel = viewModel()
) {
    val context = LocalContext.current

    val config by viewModel.config.collectAsState()
    val status by viewModel.status.collectAsState()
    val activeClients by viewModel.activeClients.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val wifiIp by viewModel.wifiIp.collectAsState()
    val wifiSsid by viewModel.wifiSsid.collectAsState()
    val fileCount by viewModel.folderFileCount.collectAsState()
    val totalSize by viewModel.folderTotalSize.collectAsState()
    val folderItems by viewModel.folderContents.collectAsState()
    val textSnippets by viewModel.textSnippets.collectAsState()

    var showQrDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }

    // Folder Picker Activity Result Launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.setFolderUri(uri)
            Toast.makeText(context, "Folder selected successfully", Toast.LENGTH_SHORT).show()
        }
    }

    // Notification Permission Launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.toggleServer()
        } else {
            Toast.makeText(context, "Notification permission is recommended for status display", Toast.LENGTH_SHORT).show()
            viewModel.toggleServer()
        }
    }

    fun handleToggleServer() {
        if (config.folderUri == null && status != ServerStatus.RUNNING) {
            Toast.makeText(context, "Please select a folder to share first", Toast.LENGTH_LONG).show()
            folderPickerLauncher.launch(null)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && status != ServerStatus.RUNNING) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.toggleServer()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Wi-Fi Folder Share",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.refreshNetworkInfo()
                            Toast.makeText(context, "Network status updated", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("refresh_network_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh Network")
                    }

                    IconButton(
                        onClick = {
                            val shareText = "Connect to my phone files:\nWeb Browser: ${viewModel.getWebUrl()}\nFTP Client: ${viewModel.getFtpUrl()}"
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Network Address"))
                        },
                        enabled = status == ServerStatus.RUNNING,
                        modifier = Modifier.testTag("share_address_button")
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = "Share Address")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header / Status Card
            HeaderSection(
                status = status,
                wifiSsid = wifiSsid,
                activeClients = activeClients,
                onToggleServer = { handleToggleServer() }
            )

            // Target Folder Selector Card
            FolderSelectorCard(
                folderName = config.folderName,
                folderPath = config.folderPath,
                fileCount = fileCount,
                totalSizeBytes = totalSize,
                hasSelectedFolder = config.folderUri != null,
                onPickFolder = { folderPickerLauncher.launch(null) },
                onViewFiles = { showFolderDialog = true }
            )

            // Connection Addresses Card
            ConnectionInfoCard(
                webUrl = viewModel.getWebUrl(),
                ftpUrl = viewModel.getFtpUrl(),
                wifiIp = wifiIp,
                isRunning = status == ServerStatus.RUNNING,
                onShowQrCode = { showQrDialog = true },
                onOpenWebBrowser = {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(viewModel.getWebUrl()))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open web browser", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            // Realtime Text & Clipboard Portal Card
            TextTransferCard(
                snippets = textSnippets,
                onSendText = { text -> viewModel.sendTextSnippet(text) },
                onDeleteSnippet = { id -> viewModel.deleteTextSnippet(id) },
                onClearAll = { viewModel.clearAllTextSnippets() },
                onCopyToClipboard = { text -> viewModel.copyToClipboard(text) },
                onGetClipboardText = { viewModel.getClipboardText() }
            )

            // Settings Card
            SettingsCard(
                config = config,
                isServerRunning = status == ServerStatus.RUNNING,
                onUpdateConfig = { ftpPort, webPort, isReadOnly, requireAuth, username, password, keepScreenOn ->
                    viewModel.updateConfig(
                        ftpPort = ftpPort,
                        webPort = webPort,
                        isReadOnly = isReadOnly,
                        requireAuth = requireAuth,
                        username = username,
                        password = password,
                        keepScreenOn = keepScreenOn
                    )
                }
            )

            // Live Activity & Access Logs Card
            LogsCard(
                logs = logs,
                onClearLogs = { viewModel.clearLogs() }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Modals
        if (showQrDialog) {
            QrCodeModal(
                webUrl = viewModel.getWebUrl(),
                ftpUrl = viewModel.getFtpUrl(),
                onDismiss = { showQrDialog = false }
            )
        }

        if (showFolderDialog) {
            FolderContentsModal(
                folderName = config.folderName,
                items = folderItems,
                onDismiss = { showFolderDialog = false }
            )
        }
    }
}
