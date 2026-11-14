package com.v2ray.ang.ui.checkupdate

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.dto.UpdateStatus
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.base.BaseViewModel
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun updateStatusText(status: UpdateStatus): Int = when (status) {
    UpdateStatus.UP_TO_DATE -> R.string.update_already_latest_version
    UpdateStatus.NO_RELEASE -> R.string.forouz_update_no_release
    UpdateStatus.NO_COMPATIBLE_APK -> R.string.forouz_update_no_apk
    UpdateStatus.INVALID_METADATA -> R.string.forouz_update_invalid
    UpdateStatus.AVAILABLE -> R.string.forouz_update_available
}

class CheckUpdateViewModel(application: Application) : BaseViewModel(application) {
    private val preferencesReady = CompletableDeferred<Unit>()
    private val preferenceMutex = Mutex()
    private val _ready = MutableStateFlow(false)
    val ready = _ready.asStateFlow()
    private val _coreVersion = MutableStateFlow("")
    val coreVersion = _coreVersion.asStateFlow()
    private val _message = MutableStateFlow<Int?>(null)
    val message = _message.asStateFlow()
    private val _checkPreRelease = MutableStateFlow(false)
    val checkPreRelease: StateFlow<Boolean> = _checkPreRelease.asStateFlow()
    private val _updateResult = MutableStateFlow<CheckUpdateResult?>(null)
    val updateResult: StateFlow<CheckUpdateResult?> = _updateResult.asStateFlow()
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _checkPreRelease.value = withContext(Dispatchers.IO) {
                    MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "FOROUZ update preferences read", IllegalStateException(e.javaClass.simpleName))
                _message.value = R.string.forouz_update_preferences
            } finally {
                _ready.value = true
                preferencesReady.complete(Unit)
            }
        }
        viewModelScope.launch {
            try { _coreVersion.value = withContext(Dispatchers.IO) { CoreNativeManager.getLibVersion() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LogUtil.e(AppConfig.TAG, "FOROUZ core version", IllegalStateException(e.javaClass.simpleName)) }
        }
    }

    fun toggleCheckPreRelease(enabled: Boolean) {
        if (!_ready.value || isLoading.value) return
        viewModelScope.launch {
            preferenceMutex.withLock {
                try {
                    withContext(Dispatchers.IO) { MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, enabled) }
                    _checkPreRelease.value = enabled
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "FOROUZ update preferences save", IllegalStateException(e.javaClass.simpleName))
                    _message.value = R.string.forouz_update_preferences
                }
            }
        }
    }

    fun checkForUpdates() {
        if (isLoading.value) return
        _isLoading.value = true
        launchLoading {
            _showUpdateDialog.value = false
            _updateResult.value = null
            _message.value = null
            try {
                preferencesReady.await()
                val includePreRelease = preferenceMutex.withLock { _checkPreRelease.value }
                val result = UpdateCheckerManager.checkForUpdate(includePreRelease)
                _updateResult.value = result
                if (result.hasUpdate) _showUpdateDialog.value = true
                else _message.value = updateStatusText(result.status)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "FOROUZ update check", IllegalStateException(e.javaClass.simpleName))
                _message.value = R.string.forouz_update_network
            }
        }
    }

    fun dismissUpdateDialog() { _showUpdateDialog.value = false }
}
