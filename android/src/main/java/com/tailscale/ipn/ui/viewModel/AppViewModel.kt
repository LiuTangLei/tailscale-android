// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.app.Application
import android.net.Uri
import android.net.VpnService
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.ShareFileHelper
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class AwgWriteOperation
internal constructor(
    val id: Long,
    val profileGeneration: Long,
    val peerStableID: String? = null,
)

data class AwgProfileMutationOperation
internal constructor(
    val id: Long,
    val profileGeneration: Long,
    val invalidatedProfile: Boolean,
    val profileBoundaryEpochAtStart: Long,
)

internal enum class AwgPrefsReader {
  MAIN,
  SETTINGS,
  VIEWER,
}

internal data class AwgPrefsReadToken(
    val reader: AwgPrefsReader,
    val requestEpoch: Long,
    val mutationEpoch: Long,
    val profileGeneration: Long,
)

internal class AwgWriteCoordinator {
  private val nextID = AtomicLong()
  private val nextPrefsEpoch = AtomicLong()
  private val _inProgress = MutableStateFlow<AwgWriteOperation?>(null)
  val inProgress: StateFlow<AwgWriteOperation?> = _inProgress
  private val _profileMutationInProgress = MutableStateFlow<AwgProfileMutationOperation?>(null)
  val profileMutationInProgress: StateFlow<AwgProfileMutationOperation?> =
      _profileMutationInProgress
  private val latestPrefsReadEpoch = mutableMapOf<AwgPrefsReader, Long>()
  private var prefsMutationEpoch = 0L

  private val _profileGenerationState = MutableStateFlow(0L)
  val profileGenerationState: StateFlow<Long> = _profileGenerationState
  val profileGeneration: Long
    get() = _profileGenerationState.value

  @Synchronized
  fun tryBegin(peerStableID: String? = null): AwgWriteOperation? {
    if (_profileMutationInProgress.value != null || _inProgress.value != null) return null
    val operation =
        AwgWriteOperation(
            id = nextID.incrementAndGet(),
            profileGeneration = profileGeneration,
            peerStableID = peerStableID,
        )
    if (!_inProgress.compareAndSet(null, operation)) return null
    invalidatePrefsReadsLocked()
    return operation
  }

  @Synchronized
  fun finish(operation: AwgWriteOperation): Boolean {
    if (!_inProgress.compareAndSet(operation, null)) return false
    // A timeout is ambiguous: LocalAPI may have committed after the client stopped waiting. Reads
    // launched during any write must therefore stay stale even when the callback reports failure.
    invalidatePrefsReadsLocked()
    return operation.profileGeneration == profileGeneration
  }

  @Synchronized
  fun tryBeginProfileMutation(
      invalidateProfile: Boolean,
      profileBoundaryEpochAtStart: Long = 0L,
  ): AwgProfileMutationOperation? {
    if (_inProgress.value != null || _profileMutationInProgress.value != null) return null
    if (invalidateProfile) incrementProfileGenerationLocked()
    val operation =
        AwgProfileMutationOperation(
            id = nextID.incrementAndGet(),
            profileGeneration = profileGeneration,
            invalidatedProfile = invalidateProfile,
            profileBoundaryEpochAtStart = profileBoundaryEpochAtStart,
        )
    if (!_profileMutationInProgress.compareAndSet(null, operation)) return null
    invalidatePrefsReadsLocked()
    return operation
  }

  @Synchronized
  fun finishProfileMutation(operation: AwgProfileMutationOperation): Boolean {
    if (!_profileMutationInProgress.compareAndSet(operation, null)) return false
    invalidatePrefsReadsLocked()
    return true
  }

  @Synchronized
  fun invalidateProfile() {
    incrementProfileGenerationLocked()
    invalidatePrefsReadsLocked()
  }

  @Synchronized
  fun beginPrefsRead(reader: AwgPrefsReader): AwgPrefsReadToken {
    val requestEpoch = nextPrefsEpoch.incrementAndGet()
    latestPrefsReadEpoch[reader] = requestEpoch
    return AwgPrefsReadToken(
        reader = reader,
        requestEpoch = requestEpoch,
        mutationEpoch = prefsMutationEpoch,
        profileGeneration = profileGeneration,
    )
  }

  @Synchronized
  fun isCurrentPrefsRead(token: AwgPrefsReadToken): Boolean =
      token.profileGeneration == profileGeneration &&
          token.mutationEpoch == prefsMutationEpoch &&
          latestPrefsReadEpoch[token.reader] == token.requestEpoch &&
          _inProgress.value == null &&
          _profileMutationInProgress.value == null

  private fun invalidatePrefsReadsLocked() {
    prefsMutationEpoch = nextPrefsEpoch.incrementAndGet()
  }

  private fun incrementProfileGenerationLocked() {
    _profileGenerationState.value = _profileGenerationState.value + 1
  }
}

internal fun awgProfileReadyAfterMutation(
    operation: AwgProfileMutationOperation,
    currentProfileGeneration: Long,
    mutationSucceeded: Boolean,
    currentProfileBoundaryEpoch: Long,
    hasCurrentNetmap: Boolean,
): Boolean =
    !operation.invalidatedProfile ||
        (!mutationSucceeded && operation.profileGeneration == currentProfileGeneration) ||
        (hasCurrentNetmap && currentProfileBoundaryEpoch > operation.profileBoundaryEpochAtStart)

class AppViewModelFactory(val application: Application, private val taildropPrompt: Flow<Unit>) :
    ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(AppViewModel::class.java)) {
      return AppViewModel(application, taildropPrompt) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

// Application context-aware ViewModel used to track app-wide VPN and Taildrop state.
// This must be application-scoped because Tailscale may be enabled, disabled, or used for
// file transfers (Taildrop) outside the activity lifecycle.
//
// Responsibilities:
// - Track VPN preparation state (e.g., whether permission has been granted) and activity state
// - Monitor incoming Taildrop file transfers
// - Coordinate prompts for Taildrop directory selection if not yet configured
class AppViewModel(application: Application, private val taildropPrompt: Flow<Unit>) :
    AndroidViewModel(application) {
  // Whether the VPN is prepared. This is set to true if the VPN application is already prepared, or
  // if the user has previously consented to the VPN application. This is used to determine whether
  // a VPN permission launcher needs to be shown.
  val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared
  // Whether a VPN interface has been established. This is set by net.updateTUN upon
  // VpnServiceBuilder.establish, and consumed by UI to reflect VPN state.
  val _vpnActive = MutableStateFlow(false)
  val vpnActive: StateFlow<Boolean> = _vpnActive
  // Whether the local node currently has a non-default AWG configuration applied.
  val _localAwgConfigured = MutableStateFlow(false)
  val localAwgConfigured: StateFlow<Boolean> = _localAwgConfigured
  private val awgWriteCoordinator = AwgWriteCoordinator()
  val awgWriteInProgress: StateFlow<AwgWriteOperation?> = awgWriteCoordinator.inProgress
  val awgProfileMutationInProgress: StateFlow<AwgProfileMutationOperation?> =
      awgWriteCoordinator.profileMutationInProgress
  val awgProfileGenerationState: StateFlow<Long> = awgWriteCoordinator.profileGenerationState
  val awgProfileGeneration: Long
    get() = awgWriteCoordinator.profileGeneration

  private val _awgProfileSyncReady = MutableStateFlow(true)
  val awgProfileSyncReady: StateFlow<Boolean> = _awgProfileSyncReady

  private var observedAwgProfileIdentity: String? = null
  private var hasObservedAwgProfile = false
  private var awgProfileBoundaryEpoch = 0L
  private var hasCurrentAwgNetmap = false
  private var awaitingAwgProfileNetmap: AwgProfileMutationOperation? = null
  // Select Taildrop directory
  var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  private val _triggerDirectoryPicker = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  val triggerDirectoryPicker: SharedFlow<Unit> = _triggerDirectoryPicker
  val TAG = "AppViewModel"

  init {
    observeIncomingTaildrop()
    observeAwgProfileIdentity()
    prepareVpn()
  }

  private fun observeAwgProfileIdentity() {
    viewModelScope.launch {
      Notifier.netmap.collect { netmap ->
        hasCurrentAwgNetmap = netmap != null
        val identity =
            netmap?.let {
              listOf(it.Domain, it.SelfNode.StableID, it.SelfNode.User.toString())
                  .joinToString("\u0000")
            }
        if (!hasObservedAwgProfile || identity != observedAwgProfileIdentity) {
          awgProfileBoundaryEpoch++
          hasObservedAwgProfile = true
          observedAwgProfileIdentity = identity
          awgWriteCoordinator.invalidateProfile()
          // Never expose a previous profile's local badge while the new prefs are being loaded.
          _localAwgConfigured.value = false
        }
        val awaiting = awaitingAwgProfileNetmap
        if (awaiting != null &&
            awgProfileMutationInProgress.value == null &&
            netmap != null &&
            awgProfileBoundaryEpoch > awaiting.profileBoundaryEpochAtStart) {
          awaitingAwgProfileNetmap = null
          _awgProfileSyncReady.value = true
        }
      }
    }
  }

  private fun observeIncomingTaildrop() {
    viewModelScope.launch {
      taildropPrompt.collect {
        TSLog.d(TAG, "Taildrop event received, checking directory")
        checkIfTaildropDirectorySelected()
      }
    }
  }

  fun requestDirectoryPicker() {
    _triggerDirectoryPicker.tryEmit(Unit)
  }

  private fun prepareVpn() {
    // Check if the user has granted permission yet.
    if (!vpnPrepared.value) {
      val vpnIntent = VpnService.prepare(getApplication())
      if (vpnIntent != null) {
        setVpnPrepared(false)
        Log.d(TAG, "VpnService.prepare returned non-null intent")
      } else {
        setVpnPrepared(true)
        Log.d(TAG, "VpnService.prepare returned null intent, VPN is already prepared")
      }
    }
  }

  fun checkIfTaildropDirectorySelected() {
    val app = App.get()
    val storedUri = app.getStoredDirectoryUri()
    if (ShareFileHelper.hasValidTaildropDir()) {
      return
    }

    val documentFile = storedUri?.let { DocumentFile.fromTreeUri(app, it) }
    if (documentFile == null || !documentFile.exists() || !documentFile.canWrite()) {
      TSLog.d(
          "MainViewModel",
          "Stored directory URI is invalid or inaccessible; launching directory picker.")
      viewModelScope.launch { requestDirectoryPicker() }
    } else {
      TSLog.d("MainViewModel", "Using stored directory URI: $storedUri")
    }
  }

  fun setVpnActive(isActive: Boolean) {
    _vpnActive.value = isActive
  }

  fun setVpnPrepared(isPrepared: Boolean) {
    _vpnPrepared.value = isPrepared
  }

  @Synchronized
  fun setLocalAwgConfigured(isConfigured: Boolean) {
    _localAwgConfigured.value = isConfigured
  }

  @Synchronized
  fun tryBeginAwgWrite(peerStableID: String? = null): AwgWriteOperation? {
    if (!_awgProfileSyncReady.value) return null
    return awgWriteCoordinator.tryBegin(peerStableID)
  }

  @Synchronized
  fun finishAwgWrite(
      operation: AwgWriteOperation,
      writeSucceeded: Boolean,
      localAwgConfiguredOnSuccess: Boolean,
  ): Boolean {
    val isCurrent = awgWriteCoordinator.finish(operation)
    if (isCurrent && writeSucceeded) {
      _localAwgConfigured.value = localAwgConfiguredOnSuccess
    }
    return isCurrent
  }

  @Synchronized
  fun tryBeginAwgProfileMutation(
      invalidateProfile: Boolean = true,
  ): AwgProfileMutationOperation? {
    val operation =
        awgWriteCoordinator.tryBeginProfileMutation(
            invalidateProfile = invalidateProfile,
            profileBoundaryEpochAtStart = awgProfileBoundaryEpoch,
        ) ?: return null
    if (invalidateProfile) {
      awaitingAwgProfileNetmap = null
      _awgProfileSyncReady.value = false
      _localAwgConfigured.value = false
    }
    return operation
  }

  @Synchronized
  fun finishAwgProfileMutation(
      operation: AwgProfileMutationOperation,
      mutationSucceeded: Boolean,
  ): Boolean {
    if (!awgWriteCoordinator.finishProfileMutation(operation)) return false
    if (operation.invalidatedProfile) {
      val ready =
          awgProfileReadyAfterMutation(
              operation = operation,
              currentProfileGeneration = awgProfileGeneration,
              mutationSucceeded = mutationSucceeded,
              currentProfileBoundaryEpoch = awgProfileBoundaryEpoch,
              hasCurrentNetmap = hasCurrentAwgNetmap,
          )
      _awgProfileSyncReady.value = ready
      awaitingAwgProfileNetmap = if (ready) null else operation
    }
    return true
  }

  @Synchronized
  fun invalidateAwgProfile() {
    awgWriteCoordinator.invalidateProfile()
  }

  @Synchronized
  internal fun beginAwgPrefsRead(reader: AwgPrefsReader): AwgPrefsReadToken =
      awgWriteCoordinator.beginPrefsRead(reader)

  @Synchronized
  internal fun isCurrentAwgPrefsRead(token: AwgPrefsReadToken): Boolean =
      _awgProfileSyncReady.value && awgWriteCoordinator.isCurrentPrefsRead(token)

  @Synchronized
  internal fun commitLocalAwgStatus(token: AwgPrefsReadToken, isConfigured: Boolean): Boolean {
    if (!_awgProfileSyncReady.value || !awgWriteCoordinator.isCurrentPrefsRead(token)) return false
    _localAwgConfigured.value = isConfigured
    return true
  }
}
