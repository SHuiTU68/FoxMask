package com.topjohnwu.magisk.ui.settings

import android.net.Uri
import androidx.core.net.toFile
import androidx.lifecycle.viewModelScope
import com.topjohnwu.magisk.arch.BaseViewModel
import com.topjohnwu.magisk.core.AppContext
import com.topjohnwu.magisk.core.ktx.timeFormatStandard
import com.topjohnwu.magisk.core.ktx.toTime
import com.topjohnwu.magisk.core.utils.MediaStoreUtils
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.inputStream
import com.topjohnwu.magisk.core.utils.MediaStoreUtils.outputStream
import com.topjohnwu.magisk.terminal.TerminalEmulator
import com.topjohnwu.magisk.terminal.appendLineOnMain
import com.topjohnwu.magisk.terminal.runSuCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/// "无后台执行任意脚本" ViewModel — 类似 KSU action 按钮，但不限定模块目录。
///
/// 用户从设置页选任意 .sh 文件 → 复制到 app cacheDir（root 进程可读）
/// → 用 root 在终端中执行 → 执行完即退出，无常驻后台进程。
/// 执行过程实时显示在 TerminalScreen，结束后可保存日志。
class RunScriptViewModel : BaseViewModel() {

    enum class State { RUNNING, SUCCESS, FAILED }

    private val _state = MutableStateFlow(State.RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var emulator: TerminalEmulator? = null
    private val emulatorReady = CompletableDeferred<TerminalEmulator>()

    fun onEmulatorCreated(emu: TerminalEmulator) {
        emulator = emu
        emulatorReady.complete(emu)
    }

    /// 执行用户选择的脚本。
    /// @param uri 脚本文件的 content/file uri
    /// @param displayName 用于标题和日志文件名
    fun runScript(uri: String, displayName: String) {
        viewModelScope.launch {
            val emu = emulatorReady.await()
            val success = withContext(Dispatchers.IO) {
                executeScript(Uri.parse(uri), displayName, emu)
            }
            _state.value = if (success) State.SUCCESS else State.FAILED
        }
    }

    private fun executeScript(uri: Uri, displayName: String, emu: TerminalEmulator): Boolean {
        // 复制脚本到 cacheDir，确保 root 进程能稳定读取（content uri 不能直接给 sh）
        val scriptDir = File(AppContext.cacheDir, "run_script")
        return try {
            scriptDir.deleteRecursively()
            scriptDir.mkdirs()
            val scriptFile = File(scriptDir, "script.sh")
            if (uri.scheme == "file") {
                uri.toFile().copyTo(scriptFile, overwrite = true)
            } else {
                uri.inputStream().use { input ->
                    scriptFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            // 用 root 执行：cd 到脚本目录，sh 执行脚本，保留退出码
            runSuCommand(
                emu,
                "echo '* Running $displayName'; " +
                    "cd $scriptDir; " +
                    "sh ./script.sh; " +
                    "EXIT=\$?; " +
                    "if [ \$EXIT -ne 0 ]; then echo '! Script failed (exit '\$EXIT')'; " +
                    "else echo '* Done'; fi; " +
                    "exit \$EXIT"
            )
        } catch (e: IOException) {
            emu.appendLineOnMain("! Cannot read script: ${e.message}")
            false
        } finally {
            // 清理临时文件
            scriptDir.deleteRecursively()
        }
    }

    fun saveLog(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val logName = "%s_script_log_%s.log".format(
                name,
                System.currentTimeMillis().toTime(timeFormatStandard)
            )
            val file = MediaStoreUtils.getFile(logName)
            file.uri.outputStream().bufferedWriter().use { writer ->
                val transcript = emulator?.screen?.transcriptText
                if (transcript != null) {
                    writer.write(transcript)
                }
            }
            showSnackbar(file.toString())
        }
    }
}
