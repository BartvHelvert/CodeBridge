package me.helvert.codebridge.extensions.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.io.HttpRequests
import java.awt.Image
import java.util.Collections
import javax.imageio.ImageIO

/** Client-side decoration. Executable packages are downloaded and verified by backend services. */
@Service(Service.Level.APP)
class RegistryImages {
    private val images = Collections.synchronizedMap(HashMap<String, Image?>())

    fun cached(url: String): Image? = images[url]
    fun tried(url: String): Boolean = images.containsKey(url)

    fun fetch(url: String): Image? {
        if (tried(url)) return cached(url)
        val image = runCatching {
            val bytes = HttpRequests.request(url).productNameAsUserAgent()
                .connectTimeout(5_000).readTimeout(10_000).readBytes(null)
            ImageIO.read(bytes.inputStream())
        }.getOrNull()
        images[url] = image
        return image
    }

    companion object {
        fun getInstance(): RegistryImages = service()
    }
}
