package me.helvert.codebridge.workbench

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.*

/**
 * Why each extension the project's host could not activate failed, for the VS Code Extensions page.
 *
 * VS Code's release build does not notify about a failed activation, except for a missing
 * dependency. The extension says what is wrong itself, if it can, and the Extensions view marks it
 * and shows the error under its runtime status. This is that runtime status. Like VS Code's, it
 * lasts as long as the host does.
 */
@Service(Service.Level.PROJECT)
class HostedActivationFailures {
    private val failed = MutableStateFlow<Map<String, String>>(emptyMap())

    /** The host's message for each extension that failed, by lower-case id. */
    val failures: StateFlow<Map<String, String>> = failed.asStateFlow()

    fun failed(extensionId: String, message: String) {
        failed.update { it + (extensionId.lowercase(Locale.ROOT) to message) }
    }

    fun hostStopped() {
        failed.value = emptyMap()
    }

    companion object {
        fun getInstance(project: Project): HostedActivationFailures = project.service()
    }
}
