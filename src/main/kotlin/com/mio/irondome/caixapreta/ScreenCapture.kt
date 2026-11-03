package com.mio.irondome.caixapreta

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.ImageOutputStream

/**
 * Captura pela tela, e não pintando os componentes, para pegar também o que é desenhado fora do Swing,
 * como o diagrama do Raio de Impacto.
 */
object ScreenCapture {

    /** A janela da IDE na tela, ou nula se ela não estiver visível. */
    fun windowArea(project: Project): Rectangle? {
        val frame = WindowManager.getInstance().getFrame(project)?.takeIf { it.isShowing } ?: return null
        return Rectangle(frame.locationOnScreen, frame.size)
    }

    fun screenshot(area: Rectangle): BufferedImage? = runCatching { Robot().createScreenCapture(area) }.getOrNull()
}

/**
 * Grava [area] num GIF em [output], a uns 4 quadros por segundo, até [stop] ou [MAX_SECONDS]. Cada
 * quadro é gravado no arquivo assim que capturado, para a memória não crescer com a duração.
 * [onFinished] é chamado na EDT.
 */
class GifRecorder(
    private val area: Rectangle,
    private val output: Path,
    private val onFinished: (Result<Recording>) -> Unit,
) {
    class Recording(val file: Path, val seconds: Int, val bytes: Long)

    @Volatile
    private var stopped = false

    fun start() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { record() }
            ApplicationManager.getApplication().invokeLater { onFinished(result) }
        }
    }

    fun stop() {
        stopped = true
    }

    private fun record(): Recording {
        val robot = Robot()
        val started = System.nanoTime()
        ImageIO.createImageOutputStream(output.toFile()).use { stream ->
            val gif = GifWriter(stream)
            var previous: BufferedImage? = null
            var previousAt = 0L
            while (!stopped && millis(System.nanoTime() - started) < MAX_SECONDS * 1000L) {
                val at = System.nanoTime()
                val frame = scaled(robot.createScreenCapture(area))
                previous?.let { gif.write(it, millis(at - previousAt)) }
                previous = frame
                previousAt = at
                val wait = FRAME_INTERVAL_MS - millis(System.nanoTime() - at)
                if (wait > 0) Thread.sleep(wait)
            }
            previous?.let { gif.write(it, millis(System.nanoTime() - previousAt)) }
            gif.finish()
        }
        return Recording(output, (millis(System.nanoTime() - started) / 1000).toInt(), Files.size(output))
    }

    private fun scaled(image: BufferedImage): BufferedImage {
        val scale = minOf(1.0, MAX_WIDTH.toDouble() / image.width)
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = result.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return result
    }

    private fun millis(nanos: Long) = nanos / 1_000_000

    companion object {
        const val MAX_SECONDS = 30
        private const val FRAME_INTERVAL_MS = 250L
        private const val MAX_WIDTH = 1280
    }
}

/** GIF animado em loop, com o tempo de cada quadro. */
private class GifWriter(stream: ImageOutputStream) {

    private val writer = ImageIO.getImageWritersBySuffix("gif").next().apply {
        output = stream
        prepareWriteSequence(null)
    }
    private var first = true

    fun write(image: BufferedImage, delayMs: Long) {
        val params = writer.defaultWriteParam
        val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), params)
        val format = metadata.nativeMetadataFormatName
        val root = metadata.getAsTree(format) as IIOMetadataNode
        child(root, "GraphicControlExtension").apply {
            setAttribute("disposalMethod", "none")
            setAttribute("userInputFlag", "FALSE")
            setAttribute("transparentColorFlag", "FALSE")
            setAttribute("delayTime", (delayMs / 10).coerceAtLeast(2).toString())
            setAttribute("transparentColorIndex", "0")
        }
        if (first) {
            val loop = IIOMetadataNode("ApplicationExtension").apply {
                setAttribute("applicationID", "NETSCAPE")
                setAttribute("authenticationCode", "2.0")
                userObject = byteArrayOf(1, 0, 0)
            }
            child(root, "ApplicationExtensions").appendChild(loop)
            first = false
        }
        metadata.setFromTree(format, root)
        writer.writeToSequence(IIOImage(image, null, metadata), params)
    }

    fun finish() {
        writer.endWriteSequence()
        writer.dispose()
    }

    private fun child(root: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until root.length) {
            val node = root.item(i)
            if (node.nodeName == name) return node as IIOMetadataNode
        }
        return IIOMetadataNode(name).also(root::appendChild)
    }
}
