package me.helvert.codebridge.language

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic

/** Told when a file's outline has changed without the file changing: an extension has answered. */
fun interface CodeBridgeOutlineListener {
    fun outlineChanged(file: VirtualFile)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<CodeBridgeOutlineListener> = Topic(CodeBridgeOutlineListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
