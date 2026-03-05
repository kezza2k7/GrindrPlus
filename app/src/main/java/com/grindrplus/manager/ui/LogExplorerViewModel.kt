package com.grindrplus.manager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map

data class DisplayLogEntry(
    val entry: LogEntry,
    val annotatedCategory: AnnotatedString,
    val annotatedMessage: AnnotatedString,
    val logColor: Color
)

class LogExplorerViewModel : ViewModel() {
    private val rawLogs = MutableStateFlow<List<LogEntry>>(emptyList())
    private val hiddenCategories = MutableStateFlow<Set<String>>(emptySet())
    
    // Explicit backing field for search input.
    val searchQuery = MutableStateFlow("")
    val isSearching = MutableStateFlow(false)

    // Colors
    private val highlightColor = Color(0xFFC8E6C9) // equivalent to primaryContainer depending on theme
    private val onHighlightColor = Color(0xFF1B5E20) // equivalent to onPrimaryContainer

    private fun getLogColor(type: LogType): Color {
        return when (type) {
            LogType.SUCCESS -> Color(0xFF4CAF50)
            LogType.WARNING -> Color(0xFFFFC107)
            LogType.ERROR -> Color(0xFFE91E63)
            LogType.DEBUG -> Color(0xFF9C27B0)
            LogType.VERBOSE -> Color(0xFF757575)
            LogType.INFO -> Color(0xFFE0E0E0) // Fallback for onSurfaceVariant
        }
    }

    private fun buildHighlightedString(text: String, query: String, defaultColor: Color): AnnotatedString {
        if (query.isEmpty()) return buildAnnotatedString {
            withStyle(SpanStyle(color = defaultColor)) { append(text) }
        }
        return buildAnnotatedString {
            var startIndex = 0
            while (startIndex < text.length) {
                val index = text.indexOf(query, startIndex, ignoreCase = true)
                if (index == -1) {
                    withStyle(SpanStyle(color = defaultColor)) { append(text.substring(startIndex)) }
                    break
                }
                withStyle(SpanStyle(color = defaultColor)) { append(text.substring(startIndex, index)) }
                withStyle(SpanStyle(background = highlightColor, color = onHighlightColor)) {
                    append(text.substring(index, index + query.length))
                }
                startIndex = index + query.length
            }
        }
    }

    @OptIn(FlowPreview::class)
    val displayLogs: StateFlow<List<DisplayLogEntry>> = combine(
        rawLogs,
        hiddenCategories,
        searchQuery.debounce(300L)
    ) { logs, hidden, query ->
        logs.asSequence()
            .filter { entry ->
                val matchesQuery = query.isEmpty() ||
                    entry.message.contains(query, ignoreCase = true) ||
                    entry.category.contains(query, ignoreCase = true)
                // When searching, bypass category filter so hidden categories still appear in results
                if (query.isNotEmpty()) matchesQuery
                else entry.category !in hidden && matchesQuery
            }
            .map { entry ->
                DisplayLogEntry(
                    entry = entry,
                    annotatedCategory = buildHighlightedString(entry.category, query, Color(0xFFE0E0E0)), // Default surface variant equivalent
                    annotatedMessage = buildHighlightedString(entry.message, query, getLogColor(entry.type)),
                    logColor = getLogColor(entry.type)
                )
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCategories: StateFlow<Set<String>> = rawLogs.map { logs: List<LogEntry> ->
        val categories = mutableSetOf<String>()
        for (log in logs) {
            if (log.category != "unknown" && log.category.isNotEmpty()) {
                categories.add(log.category)
            }
        }
        categories
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val hiddenCategoriesState: StateFlow<Set<String>> = hiddenCategories

    fun setLogs(logs: List<LogEntry>) {
        rawLogs.value = logs
    }

    fun toggleCategoryHidden(category: String) {
        hiddenCategories.update { curr ->
            if (category in curr) curr - category else curr + category
        }
    }

    fun setSearching(active: Boolean) {
        isSearching.value = active
        if (!active) {
            searchQuery.value = "" // Clear query explicitly per discussion on UX persistence
        }
    }

    fun showAllCategories() {
        hiddenCategories.value = emptySet()
    }
}
