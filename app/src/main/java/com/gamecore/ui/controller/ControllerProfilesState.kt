package com.gamecore.ui.controller

import android.net.Uri
import com.gamecore.core.controller.ControllerProfile
import com.gamecore.core.controller.ControllerTestResult
import com.gamecore.core.controller.ResponseCurveKind

/**
 * The profile editor's working copy.
 *
 * Numeric values are numbers, not strings: the editor uses sliders, so there is no half-typed "0." state to
 * parse and no way to submit a value the model did not intend. [errors] is set only after a save attempt, so
 * a field is not marked wrong while it is being edited.
 */
data class ProfileDraft(
    val id: Long = 0L,
    val name: String = "",
    val deviceSignature: String = "",
    val deviceName: String = "",
    val deadzone: Float = 0.08f,
    val curve: ResponseCurveKind = ResponseCurveKind.EXPONENTIAL,
    val exponent: Float = 2f,
    val driftOffset: Float = 0.08f,
    val driftJitter: Float = 0.02f,
    val notes: String = "",
    val createdAtMillis: Long = 0L,
    val errors: List<String> = emptyList(),
)

/** A written export ready for the share sheet, if the provider supplied a URI. */
data class ProfilesShare(
    val uri: Uri,
    val mimeType: String = "application/json",
)

/**
 * Profiles, the editor, comparison, transfer and the recent drift history for the selected controller.
 *
 * [history] is scoped to the controller the main screen has selected; [profiles] is every saved profile, so
 * a profile can still be edited or deleted after its controller is unplugged. [messageIsError] decides the
 * banner tone rather than the message text being sniffed.
 */
data class ControllerProfilesUiState(
    val loading: Boolean = true,
    val profiles: List<ControllerProfile> = emptyList(),
    val history: List<ControllerTestResult> = emptyList(),
    val deviceSignature: String? = null,
    val deviceName: String? = null,
    val draft: ProfileDraft? = null,
    val compareLeft: Long? = null,
    val compareRight: Long? = null,
    val pendingDelete: ControllerProfile? = null,
    val confirmClearHistory: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val importIssues: List<String> = emptyList(),
    val share: ProfilesShare? = null,
)
