package com.safeopen.viewer.render

import org.xmlpull.v1.XmlPullParser

/** A tiny, bounded XML tree used where streaming is awkward (slides, layouts). */
class Node(val name: String, val attrs: Map<String, String>) {
    val children = ArrayList<Node>(2)
    var text: StringBuilder? = null

    val local: String get() = name.substringAfter(':')

    fun attr(localName: String): String? =
        attrs[localName] ?: attrs.entries.firstOrNull { it.key.endsWith(":$localName") }?.value

    fun child(localName: String): Node? = children.firstOrNull { it.local == localName }
    fun children(localName: String): List<Node> = children.filter { it.local == localName }

    /** Path of local names, e.g. path("spPr", "xfrm", "off"). */
    fun path(vararg names: String): Node? {
        var n: Node? = this
        for (x in names) n = n?.child(x) ?: return null
        return n
    }

    fun descendants(localName: String, out: MutableList<Node> = ArrayList()): MutableList<Node> {
        for (c in children) {
            if (c.local == localName) out += c
            c.descendants(localName, out)
        }
        return out
    }

    fun allText(): String {
        val sb = StringBuilder()
        fun walk(n: Node) { n.text?.let { sb.append(it) }; n.children.forEach { walk(it) } }
        walk(this)
        return sb.toString()
    }

    companion object {
        private const val MAX_NODES = 400_000

        fun parse(data: ByteArray): Node {
            val p = XmlFactory.parser(data)
            val root = Node("#root", emptyMap())
            val stack = ArrayList<Node>().apply { add(root) }
            var count = 0
            while (true) {
                when (p.next()) {
                    XmlPullParser.END_DOCUMENT -> return root
                    XmlPullParser.START_TAG -> {
                        if (++count > MAX_NODES) throw TooLargeException()
                        val attrs = HashMap<String, String>(p.attributeCount * 2)
                        for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                        val n = Node(p.name, attrs)
                        stack.last().children += n
                        stack += n
                    }
                    XmlPullParser.END_TAG -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                    XmlPullParser.TEXT -> {
                        val t = p.text
                        if (t != null && t.isNotEmpty()) {
                            val top = stack.last()
                            (top.text ?: StringBuilder().also { top.text = it }).append(t)
                        }
                    }
                }
            }
        }
    }
}
