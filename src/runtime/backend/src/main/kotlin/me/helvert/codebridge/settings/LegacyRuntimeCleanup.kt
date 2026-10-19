package me.helvert.codebridge.settings

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.NioFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists

/**
 * Removes the runtime earlier versions downloaded into the IDE's system directory.
 *
 * The extension host now ships inside the plugin (see [BundledRuntime]), so the ~350 MB copy under
 * `<system>/vsxbridge/runtime` is nothing but disk space, and nothing else would ever delete it.
 */
internal class LegacyRuntimeCleanup : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Every project open runs this; once per session is enough.
        if (!attempted.compareAndSet(false, true)) return
        val legacy = PathManager.getSystemDir().resolve("vsxbridge").resolve("runtime")
        if (!legacy.exists()) return
        withContext(Dispatchers.IO) {
            try {
                NioFiles.deleteRecursively(legacy)
            } catch (failure: Exception) {
                // A second IDE still running the old runtime holds its files open on Windows. The
                // next startup tries again.
                LOG.info("Could not remove the previously downloaded runtime at $legacy", failure)
            }
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(LegacyRuntimeCleanup::class.java)
        private val attempted = AtomicBoolean(false)
    }
}
