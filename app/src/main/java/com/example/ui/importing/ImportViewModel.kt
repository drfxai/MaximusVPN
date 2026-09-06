package com.example.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.UniversalImportResult
import com.example.data.repository.ServerRepository
import com.example.vpn.engine.UniversalImportEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ImportUiState(
    val rawTextInput: String = "",
    val isAnalyzing: Boolean = false,
    val previewResult: UniversalImportResult? = null,
    val importSuccessMessage: String? = null,
    val errorMessage: String? = null
)

class ImportViewModel(
    private val serverRepository: ServerRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    fun onTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(
            rawTextInput = text,
            importSuccessMessage = null,
            errorMessage = null
        )
    }

    /**
     * Parses the current text input or multi-file sources and prepares an import preview.
     */
    fun analyzeContent(rawContent: String, fileName: String? = null) {
        if (rawContent.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter or paste configuration text.")
            return
        }

        _uiState.value = _uiState.value.copy(isAnalyzing = true, errorMessage = null, importSuccessMessage = null)

        viewModelScope.launch {
            try {
                val result = UniversalImportEngine.importText(
                    rawText = rawContent,
                    sourceFileName = fileName
                )
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    previewResult = result
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isAnalyzing = false,
                    errorMessage = "Analysis error: ${e.localizedMessage}"
                )
            }
        }
    }

    /**
     * Confirms the import and saves valid deduplicated nodes into the database.
     */
    fun confirmImport() {
        val preview = _uiState.value.previewResult ?: return

        viewModelScope.launch {
            try {
                val (inserted, duplicates) = serverRepository.insertAllWithDeduplication(preview.validProfiles)
                _uiState.value = _uiState.value.copy(
                    rawTextInput = "",
                    previewResult = null,
                    importSuccessMessage = "Successfully imported ${inserted.size} new nodes (${duplicates.size} duplicates skipped)."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to save configurations: ${e.localizedMessage}"
                )
            }
        }
    }

    fun dismissPreview() {
        _uiState.value = _uiState.value.copy(previewResult = null)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(importSuccessMessage = null, errorMessage = null)
    }
}
