package com.gamecore.ui.logs

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gamecore.core.diagnostics.LogKind
import com.gamecore.core.diagnostics.LogRecord
import com.gamecore.core.diagnostics.LogRecordCodec
import com.gamecore.core.diagnostics.LogStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The readings of the history the screen offers. */
enum class LogFilter(val label: String) {
    ALL("All"),
    CRASHES("Crashes"),
    GAMES("Games"),
    SESSIONS("Sessions"),
    ;

    fun accepts(record: LogRecord): Boolean = when (this) {
        ALL -> true
        CRASHES -> record.kind == LogKind.APP_CRASH ||
            record.kind == LogKind.GAME_CRASH ||
            record.kind == LogKind.ANR
        GAMES -> record.kind == LogKind.GAME_CRASH ||
            record.kind == LogKind.GAME_SESSION
        SESSIONS -> record.kind == LogKind.GAME_SESSION
    }
}

data class LogsUiState(
    val enabled: Boolean = true,
    val records: List<LogRecord> = emptyList(),
    val filter: LogFilter = LogFilter.ALL,
    val isLoading: Boolean = true,
    val message: String? = null,
) {
    val visibleRecords: List<LogRecord> get() = records.filter(filter::accepts)
}

/**
 * The Logs screen's state holder.
 *
 * It owns no logging policy: [LogStore] decides what is written and whether the switch is on. This
 * class reads the store, filters what it finds, and builds the one share intent a file needs. Keeping
 * the write path out of the ViewModel is what lets the crash handler use the same store without a
 * screen ever being alive.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    private val store: LogStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private val _shareRequest = MutableStateFlow<Intent?>(null)
    val shareRequest: StateFlow<Intent?> = _shareRequest.asStateFlow()

    init {
        viewModelScope.launch {
            store.enabled.collect { on ->
                _state.update { it.copy(enabled = on) }
            }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val records = store.recent(400)
            _state.update { it.copy(records = records, isLoading = false) }
        }
    }

    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            val changed = store.setEnabled(on)
            _state.update {
                it.copy(
                    message = when {
                        !changed -> "The logging switch could not be saved."
                        on -> "Logging is on. New crashes and session boundaries will be recorded."
                        else -> "Logging is off. No new record will be written; existing history stays."
                    },
                )
            }
            if (changed) refresh()
        }
    }

    fun setFilter(filter: LogFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun clear() {
        viewModelScope.launch {
            val cleared = store.clear()
            _state.update {
                it.copy(
                    records = if (cleared) emptyList() else it.records,
                    message = if (cleared) {
                        "Log history cleared. The switch itself is unchanged."
                    } else {
                        "The log history could not be cleared."
                    },
                )
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Writes the visible records to a shareable file and publishes the intent for the screen. */
    fun share(records: List<LogRecord>) {
        if (records.isEmpty()) {
            _state.update { it.copy(message = "There is nothing to share yet.") }
            return
        }
        viewModelScope.launch {
            val file = store.export(records)
            if (file == null) {
                _state.update { it.copy(message = "The log file could not be written.") }
                return@launch
            }
            val uri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
            if (uri == null) {
                _state.update { it.copy(message = "The log file could not be shared.") }
                return@launch
            }
            _shareRequest.value = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "GameCore diagnostic logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    fun consumeShare() {
        _shareRequest.value = null
    }

    /** The text the copy button puts on the clipboard. */
    fun copyText(records: List<LogRecord>): String = LogRecordCodec.toShareText(records)
}
