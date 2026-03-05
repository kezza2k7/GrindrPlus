package com.grindrplus.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import android.content.Intent
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
import androidx.core.net.toUri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.grindrplus.BuildConfig
import com.grindrplus.core.Config
import com.grindrplus.core.Logger
import com.grindrplus.manager.utils.FileOperationHandler
import com.grindrplus.manager.utils.uploadAndShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems

enum class LogType {
    INFO, WARNING, ERROR, DEBUG, VERBOSE, SUCCESS
}

data class LogEntry(
    val timestamp: String?,
    val category: String = "unknown",
    val message: String,
    val type: LogType
)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogsScreen(
    onBack: () -> Unit,
    viewModel: LogExplorerViewModel = viewModel()
) {
    val displayLogs by viewModel.displayLogs.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val hiddenCategories by viewModel.hiddenCategoriesState.collectAsStateWithLifecycle()
    val allCategories by viewModel.allCategories.collectAsStateWithLifecycle()
    var showFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showExportDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    var debugModeEnabled by remember {
        mutableStateOf(Config.get("debug_mode", false) as Boolean)
    }

    val isDebugBuild = BuildConfig.DEBUG

    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        launch {
            withContext(Dispatchers.IO) {
                val log = File(context.getExternalFilesDir(null), "grindrplus.log")

                fun parseLogs(logs: List<String>) =
                    logs.map {
                        val splitIndex = it.indexOf(": ")
                        val prefix = if (splitIndex != -1) it.substring(0, splitIndex) else "unknown"
                        val messageBody = if (splitIndex != -1) it.substring(splitIndex + 2) else it

                        val splitPrefix = prefix.split("/")
                        val timestamp = if (splitPrefix.size > 1) splitPrefix[1] else null
                        val categoryStr = splitPrefix.drop(2).joinToString("/")

                        LogEntry(
                            timestamp = timestamp,
                            category = if (categoryStr.isEmpty() || categoryStr == "unknown") "unknown" else categoryStr,
                            message = messageBody,
                            type = when {
                                it.startsWith("I") -> LogType.INFO
                                it.startsWith("W") -> LogType.WARNING
                                it.startsWith("E") -> LogType.ERROR
                                it.startsWith("D") -> LogType.DEBUG
                                it.startsWith("V") -> LogType.VERBOSE
                                else -> LogType.INFO
                            }
                        )
                    }

                viewModel.setLogs(parseLogs(log.readLines()))

                val watchService = FileSystems.getDefault().newWatchService()

                log.toPath().parent.register(
                    watchService,
                    java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
                )

                while (true) {
                    val key = watchService.take()

                    for (event in key.pollEvents()) {
                        if (event.context() == log.name) {
                            val newLog = log.readLines()
                            viewModel.setLogs(parseLogs(newLog))
                        }
                    }

                    key.reset()
                }
            }
        }
    }

    if (showExportDialog) {
        ExportLogsDialog(
            onDismissRequest = { showExportDialog = false },
            onZipExport = {
                scope.launch {
                    try {
                        val zipFile = FileOperationHandler.createLogsZip(context)
                        if (zipFile != null) {
                            FileOperationHandler.exportZipFile(
                                "grindrplus_logs.zip",
                                zipFile
                            )
                        } else {
                            snackbarHostState.showSnackbar("Failed to create logs package")
                        }
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("Error exporting logs: ${e.message}")
                    }
                    showExportDialog = false
                }
            },
            onUrlExport = {
                showExportDialog = false

                scope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar("Generating URL... This may take a while.")
                        }

                        val log = File(context.getExternalFilesDir(null), "grindrplus.log")
                        val logContent = log.readText()

                        uploadAndShare(logContent, context)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            snackbarHostState.showSnackbar("Error: ${e.message ?: "Unknown error"}")
                        }
                    }
                }
            }
        )
    }

    if (showReportDialog) {
        ReportIssueDialog(
            onDismiss = { showReportDialog = false },
            onOpenGitHub = {
                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/R0rt1z2/GrindrPlus/issues".toUri())
                context.startActivity(intent)
                showReportDialog = false
            }
        )
    }

    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filter Categories",
                        style = MaterialTheme.typography.titleLarge
                    )
                    if (hiddenCategories.isNotEmpty()) {
                        Button(
                            onClick = { viewModel.showAllCategories() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Text("Show All")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (allCategories.isEmpty()) {
                    Text(
                        text = "No categories found yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    Text(
                        text = "${hiddenCategories.size} of ${allCategories.size} hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    allCategories.sorted().let { sortedCategories ->
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(sortedCategories) { category ->
                                val isVisible = category !in hiddenCategories
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.toggleCategoryHidden(category) }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = category,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isVisible)
                                            MaterialTheme.colorScheme.onSurface
                                        else
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Switch(
                                        checked = isVisible,
                                        onCheckedChange = { viewModel.toggleCategoryHidden(category) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 80.dp)
            )
        },
        floatingActionButton = {
            if (!isSearching) {
                FloatingActionButton(
                    onClick = { showFilterSheet = true },
                    containerColor = if (hiddenCategories.isNotEmpty())
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    BadgedBox(
                        badge = {
                            if (hiddenCategories.isNotEmpty()) {
                                Badge {
                                    Text(hiddenCategories.size.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filter categories"
                        )
                    }
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) { focusRequester.requestFocus() }
                        TextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchQuery.value = it },
                            placeholder = { Text("Search logs...") },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                        )
                    } else {
                        Text("Logs")
                    }
                },
                navigationIcon = {
                    if (isSearching) {
                        IconButton(onClick = { viewModel.setSearching(false) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Close Search")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Back",
                                modifier = Modifier.rotate(180f)
                            )
                        }
                    }
                },
                actions = {
                    if (isSearching) {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear query")
                            }
                        }
                    } else {
                        IconButton(onClick = { viewModel.setSearching(true) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search logs")
                        }
                        IconButton(
                        onClick = {
                            if (!isDebugBuild) {
                                val newState = !debugModeEnabled
                                debugModeEnabled = newState
                                Config.put("debug_mode", newState)
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (newState) "Verbose logging enabled" else "Verbose logging disabled"
                                    )
                                }
                            }
                        },
                        enabled = !isDebugBuild
                    ) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = "Toggle Verbose Logging",
                                tint = if (isDebugBuild || debugModeEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            }
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            LogsViewer(
                logs = displayLogs,
                searchQuery = searchQuery,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onHideCategory = { viewModel.toggleCategoryHidden(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { showExportDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export Logs")
                }

                Spacer(modifier = Modifier.padding(horizontal = 8.dp))

                Button(
                    onClick = {
                        viewModel.setLogs(emptyList())
                        Logger.clearLogs()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Text("Clear Logs")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { showReportDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text("Report an Issue")
            }

            Spacer(modifier = Modifier.height(90.dp))
        }
    }
}

@Composable
fun LogsViewer(
    logs: List<DisplayLogEntry>,
    searchQuery: String,
    modifier: Modifier = Modifier,
    onHideCategory: ((String) -> Unit)? = null
) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "No logs found for \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "No logs available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    state = listState
                ) {
                    items(logs) { displayEntry ->
                        LogEntryItem(displayEntry, onHideCategory)

                        if (displayEntry != logs.last()) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExportLogsDialog(
    onDismissRequest: () -> Unit,
    onZipExport: () -> Unit,
    onUrlExport: () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Export Logs",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Choose how you would like to export the logs:",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(24.dp))

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onZipExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text(
                            "Generate ZIP",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onUrlExport,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    ) {
                        Text(
                            "Generate URL",
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Button(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
fun ReportIssueDialog(
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close dialog",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Report an Issue",
                        style = MaterialTheme.typography.headlineSmall
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "To help the developer fix issues, you can report it on GitHub by using the \"New Issue\" button and then using the \"Bug Report\" template.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onOpenGitHub,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Open GitHub")
                    }
                }
            }
        }
    }
}

@Composable
fun LogEntryItem(displayEntry: DisplayLogEntry, onHideCategory: ((String) -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (displayEntry.entry.category != "unknown" && displayEntry.entry.category.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                            .clickable { onHideCategory?.invoke(displayEntry.entry.category) }
                            .padding(start = 4.dp, top = 2.dp, bottom = 2.dp, end = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = displayEntry.annotatedCategory,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Hide category",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    if (displayEntry.entry.timestamp != null) {
                        Text(
                            text = displayEntry.entry.timestamp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }
            Text(
                text = displayEntry.annotatedMessage,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}