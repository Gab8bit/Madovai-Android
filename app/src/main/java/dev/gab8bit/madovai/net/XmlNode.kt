package dev.gab8bit.madovai.net

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/**
 * A minimal generic XML tree mirroring how the reference Node server's `xml2js` parses
 * Cotral's responses (and the iOS `XMLNode`): every child tag becomes a list under its
 * tag name, while a tag's own attributes and (trimmed) text are kept alongside.
 */
class XmlNode(val name: String) {
    val attributes = HashMap<String, String>()
    var text: String = ""
    val children = HashMap<String, MutableList<XmlNode>>()

    fun firstChild(tag: String): XmlNode? = children[tag]?.firstOrNull()

    /** Text of the first child with this tag, or "" — Cotral's fields are non-optional strings. */
    fun childText(tag: String): String = firstChild(tag)?.text ?: ""

    fun childList(tag: String): List<XmlNode> = children[tag] ?: emptyList()
}

object XmlNodeParser {
    private val danglingClosingTag = Regex("^</[A-Za-z][\\w:.-]*>$")

    /**
     * Cotral sometimes returns just a dangling closing tag (e.g. `</transiti>`) for an
     * unknown/no-data query — that and an empty body mean "no data", not a failure.
     * Any parse error also yields null (same as the iOS `XMLParser` path).
     */
    fun parse(data: ByteArray): XmlNode? {
        val trimmed = String(data, Charsets.UTF_8).trim()
        if (trimmed.isEmpty()) return null
        if (danglingClosingTag.matches(trimmed)) return null

        return try {
            // XmlPullParserFactory (not android.util.Xml) so this also runs in JVM tests.
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            // null encoding: honour the XML declaration's own encoding.
            parser.setInput(ByteArrayInputStream(data), null)
            val stack = ArrayList<XmlNode>()
            val textBuffers = ArrayList<StringBuilder>()
            var root: XmlNode? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val node = XmlNode(parser.name)
                        for (i in 0 until parser.attributeCount) {
                            node.attributes[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        }
                        stack.add(node)
                        textBuffers.add(StringBuilder())
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> {
                        textBuffers.lastOrNull()?.append(parser.text ?: "")
                    }
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) {
                            val finished = stack.removeAt(stack.size - 1)
                            finished.text = textBuffers.removeAt(textBuffers.size - 1).toString().trim()
                            val parent = stack.lastOrNull()
                            if (parent != null) {
                                parent.children.getOrPut(parser.name) { ArrayList() }.add(finished)
                            } else {
                                root = finished
                            }
                        }
                    }
                }
                event = parser.next()
            }
            root
        } catch (e: Exception) {
            null
        }
    }
}
