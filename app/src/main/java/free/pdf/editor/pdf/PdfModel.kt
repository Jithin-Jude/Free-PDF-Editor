package free.pdf.editor.pdf

import android.net.Uri

sealed interface PdfElement {
    val id: String
    val x: Float       // X coordinate in Original PostScript points
    val y: Float       // Y coordinate in Original PostScript points
    val width: Float   // Width in points
    val height: Float  // Height in points

    fun copyWithPosition(x: Float, y: Float): PdfElement
    fun copyWithSize(width: Float, height: Float): PdfElement

    data class Text(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float = 200f,
        override val height: Float = 60f,
        val text: String,
        val fontSize: Float = 14f,
        val colorHex: String = "#000000",
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val backgroundColorHex: String? = null // For opaque whiteouts or text boxes
    ) : PdfElement {
        override fun copyWithPosition(x: Float, y: Float) = copy(x = x, y = y)
        override fun copyWithSize(width: Float, height: Float) = copy(width = width, height = height)
    }

    data class Image(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float = 150f,
        override val height: Float = 150f,
        val uriString: String,
        val rotation: Float = 0f
    ) : PdfElement {
        override fun copyWithPosition(x: Float, y: Float) = copy(x = x, y = y)
        override fun copyWithSize(width: Float, height: Float) = copy(width = width, height = height)
    }

    data class Whiteout(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float = 120f,
        override val height: Float = 40f,
        val colorHex: String = "#FFFFFF" // Typically white, but could be black (redacted) or yellow (highlight)
    ) : PdfElement {
        override fun copyWithPosition(x: Float, y: Float) = copy(x = x, y = y)
        override fun copyWithSize(width: Float, height: Float) = copy(width = width, height = height)
    }

    data class Drawing(
        override val id: String,
        override val x: Float,
        override val y: Float,
        override val width: Float = 0f, // Not strictly used since it is freehand
        override val height: Float = 0f,
        val points: List<PointData>, // Points relative to page origin in PostScript points
        val colorHex: String = "#000000",
        val strokeWidth: Float = 4f
    ) : PdfElement {
        override fun copyWithPosition(x: Float, y: Float) = this
        override fun copyWithSize(width: Float, height: Float) = this
    }
}

data class PointData(
    val x: Float,
    val y: Float
)

data class PageData(
    val index: Int,
    val width: Int,  // in points
    val height: Int, // in points
    val cachePath: String, // Full file path to high-res background bitmap
    val elements: List<PdfElement> = emptyList()
)

data class RecentFile(
    val path: String,
    val name: String,
    val dateModified: Long
)
