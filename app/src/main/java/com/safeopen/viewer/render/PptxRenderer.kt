package com.safeopen.viewer.render

import java.io.File
import java.util.Locale

/**
 * PowerPoint (.pptx / .pptm / .ppsx) → HTML slides. Shapes, text, pictures and tables are
 * positioned like on the real slide. Macros, animations, sounds and links are ignored.
 */
object PptxRenderer {

    const val MAX_SLIDES = 400

    private const val CSS = """
        body { background: #3b3f47; padding: 10px 8px; }
        .label { color: #d6d9df; font-size: 13px; margin: 14px 2px 6px; }
        .slide { position: relative; width: 100%; background: #fff; overflow: hidden; border-radius: 4px;
                 box-shadow: 0 2px 8px rgba(0,0,0,.4); container-type: inline-size; background-size: cover; background-position: center; }
        .el { position: absolute; display: flex; flex-direction: column; overflow: visible; }
        .el.t-ctr { justify-content: center; } .el.t-b { justify-content: flex-end; }
        .el p { margin: 0; line-height: 1.15; }
        .el img { width: 100%; height: 100%; object-fit: fill; }
        .el table { width: 100%; height: 100%; border-collapse: collapse; margin: 0; }
        .el td { border: 1px solid #9aa3b2; padding: .3em; }
        .chart { border: 1px dashed #9aa3b2; align-items: center; justify-content: center; color: #6b7280; }
    """

    private data class Box(val x: Long, val y: Long, val cx: Long, val cy: Long)

    /** Child-coordinate transform for group shapes. */
    private data class Xf(val ox: Double = 0.0, val oy: Double = 0.0, val sx: Double = 1.0, val sy: Double = 1.0,
                          val chx: Double = 0.0, val chy: Double = 0.0) {
        fun map(b: Box) = Box(
            (ox + (b.x - chx) * sx).toLong(), (oy + (b.y - chy) * sy).toLong(),
            (b.cx * sx).toLong(), (b.cy * sy).toLong()
        )
    }

    private class Ctx(
        val zip: ZipDoc,
        val rels: Map<String, ZipDoc.Rel>,
        val w: Long, val h: Long,
        val layoutPh: Map<String, Box>, val masterPh: Map<String, Box>
    )

    fun render(file: File): String = ZipDoc(file).use { zip ->
        val presPath = zip.rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target ?: "ppt/presentation.xml"
        val pres = Node.parse(zip.read(presPath) ?: throw IllegalStateException("presentation missing"))
        val presRoot = pres.children.firstOrNull() ?: throw IllegalStateException("empty presentation")
        val presRels = zip.rels(presPath)
        val sz = presRoot.child("sldSz")
        val w = sz?.attr("cx")?.toLongOrNull()?.takeIf { it > 0 } ?: 9_144_000L
        val h = sz?.attr("cy")?.toLongOrNull()?.takeIf { it > 0 } ?: 6_858_000L

        val slidePaths = presRoot.child("sldIdLst")?.children("sldId")?.mapNotNull { s ->
            val rid = s.attrs["r:id"] ?: s.attr("id")
            presRels[rid]?.takeIf { !it.external }?.target
        } ?: emptyList()

        val ratio = String.format(Locale.US, "%.4f", w.toDouble() / h)
        val sb = StringBuilder()
        val layoutCache = HashMap<String, Map<String, Box>>()
        slidePaths.take(MAX_SLIDES).forEachIndexed { i, path ->
            val data = zip.read(path) ?: return@forEachIndexed
            val slide = Node.parse(data).children.firstOrNull() ?: return@forEachIndexed
            if (slide.attr("show") == "0") return@forEachIndexed
            val rels = zip.rels(path)
            val layoutPath = rels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.target
            val layoutPh = layoutPath?.let { lp -> layoutCache.getOrPut(lp) { placeholders(zip, lp) } } ?: emptyMap()
            val masterPath = layoutPath?.let { lp -> zip.rels(lp).values.firstOrNull { it.type.endsWith("/slideMaster") }?.target }
            val masterPh = masterPath?.let { mp -> layoutCache.getOrPut(mp) { placeholders(zip, mp) } } ?: emptyMap()
            val ctx = Ctx(zip, rels, w, h, layoutPh, masterPh)

            sb.append("<div class=\"label\">").append(i + 1).append(" / ").append(slidePaths.size).append("</div>")
            val bgStyle = background(slide, ctx)
            sb.append("<div class=\"slide\" style=\"aspect-ratio:$ratio;$bgStyle\">")
            slide.path("cSld", "spTree")?.let { renderTree(it, Xf(), ctx, sb) }
            sb.append("</div>")
            if (sb.length > Limits.OUTPUT_CHARS) throw TooLargeException()
        }
        if (slidePaths.size > MAX_SLIDES) sb.append(Html.note("⚠ $MAX_SLIDES+"))
        Html.page(sb.toString(), CSS)
    }

    private fun placeholders(zip: ZipDoc, path: String): Map<String, Box> {
        val data = zip.read(path) ?: return emptyMap()
        val root = Node.parse(data).children.firstOrNull() ?: return emptyMap()
        val out = HashMap<String, Box>()
        for (sp in root.descendants("sp")) {
            val ph = sp.path("nvSpPr", "nvPr", "ph") ?: continue
            val box = xfrmBox(sp.path("spPr", "xfrm")) ?: continue
            val type = normType(ph.attr("type"))
            ph.attr("idx")?.let { out.putIfAbsent("idx:$it", box) }
            out.putIfAbsent("type:$type", box)
        }
        return out
    }

    private fun normType(t: String?) = when (t) {
        null, "obj", "body" -> "body"
        "ctrTitle", "title" -> "title"
        "subTitle" -> "subTitle"
        else -> t
    }

    private fun xfrmBox(x: Node?): Box? {
        x ?: return null
        val off = x.child("off") ?: return null
        val ext = x.child("ext") ?: return null
        return Box(
            off.attr("x")?.toLongOrNull() ?: 0, off.attr("y")?.toLongOrNull() ?: 0,
            ext.attr("cx")?.toLongOrNull() ?: 0, ext.attr("cy")?.toLongOrNull() ?: 0
        )
    }

    private fun background(slide: Node, ctx: Ctx): String {
        val bgPr = slide.path("cSld", "bg", "bgPr") ?: return ""
        bgPr.path("solidFill", "srgbClr")?.attr("val")?.let { if (it.matches(HEX6)) return "background-color:#$it;" }
        bgPr.path("blipFill", "blip")?.attr("embed")?.let { id ->
            val rel = ctx.rels[id]
            if (rel != null && !rel.external) ctx.zip.imageUri(rel.target)?.let { return "background-image:url('$it');" }
        }
        return ""
    }

    private fun renderTree(tree: Node, xf: Xf, ctx: Ctx, sb: StringBuilder) {
        for (c in tree.children) {
            when (c.local) {
                "sp" -> shape(c, xf, ctx, sb)
                "pic" -> picture(c, xf, ctx, sb)
                "graphicFrame" -> frame(c, xf, ctx, sb)
                "grpSp" -> {
                    val x = c.path("grpSpPr", "xfrm")
                    val box = xfrmBox(x)
                    val chOff = x?.child("chOff")
                    val chExt = x?.child("chExt")
                    if (box != null && chOff != null && chExt != null) {
                        val chcx = chExt.attr("cx")?.toDoubleOrNull()?.takeIf { it > 0 } ?: box.cx.toDouble()
                        val chcy = chExt.attr("cy")?.toDoubleOrNull()?.takeIf { it > 0 } ?: box.cy.toDouble()
                        val mapped = xf.map(box)
                        val inner = Xf(
                            mapped.x.toDouble(), mapped.y.toDouble(),
                            mapped.cx / chcx, mapped.cy / chcy,
                            chOff.attr("x")?.toDoubleOrNull() ?: 0.0, chOff.attr("y")?.toDoubleOrNull() ?: 0.0
                        )
                        renderTree(c, inner, ctx, sb)
                    } else renderTree(c, xf, ctx, sb)
                }
                "AlternateContent" -> {
                    val pick = c.child("Fallback") ?: c.child("Choice")
                    if (pick != null) renderTree(pick, xf, ctx, sb)
                }
            }
        }
    }

    private fun boxCss(b: Box, ctx: Ctx, rot: Node?): String {
        fun pct(v: Long, of: Long) = String.format(Locale.US, "%.3f", v * 100.0 / of)
        val sb = StringBuilder("left:${pct(b.x, ctx.w)}%;top:${pct(b.y, ctx.h)}%;width:${pct(b.cx, ctx.w)}%;height:${pct(b.cy, ctx.h)}%;")
        val r = rot?.attr("rot")?.toLongOrNull() ?: 0
        val flipH = rot?.attr("flipH") == "1"
        val flipV = rot?.attr("flipV") == "1"
        if (r != 0L || flipH || flipV) {
            sb.append("transform:")
            if (r != 0L) sb.append("rotate(${r / 60000.0}deg) ")
            if (flipH) sb.append("scaleX(-1) ")
            if (flipV) sb.append("scaleY(-1)")
            sb.append(';')
        }
        return sb.toString()
    }

    private fun shape(sp: Node, xf: Xf, ctx: Ctx, sb: StringBuilder) {
        val ph = sp.path("nvSpPr", "nvPr", "ph")
        val xfrm = sp.path("spPr", "xfrm")
        val phType = normType(ph?.attr("type"))
        val raw = xfrmBox(xfrm)
            ?: ph?.let { p -> p.attr("idx")?.let { ctx.layoutPh["idx:$it"] } ?: ctx.layoutPh["type:$phType"] ?: ctx.masterPh["type:$phType"] }
            ?: if (ph != null) defaultBox(phType, ctx) else null
            ?: return
        val box = if (xfrm != null) xf.map(raw) else raw
        val css = StringBuilder(boxCss(box, ctx, xfrm))
        sp.path("spPr", "solidFill", "srgbClr")?.attr("val")?.takeIf { it.matches(HEX6) }?.let { css.append("background:#$it;") }
        sp.path("spPr", "ln", "solidFill", "srgbClr")?.attr("val")?.takeIf { it.matches(HEX6) }?.let { css.append("border:1px solid #$it;") }
        val geom = sp.path("spPr", "prstGeom")?.attr("prst")
        if (geom == "ellipse") css.append("border-radius:50%;")
        if (geom == "roundRect") css.append("border-radius:8%;")

        val body = sp.child("txBody")
        val defaultPt = when (phType) { "title" -> if (ph?.attr("type") == "ctrTitle") 44.0 else 36.0; "subTitle" -> 24.0; "body" -> if (ph != null) 22.0 else 18.0; else -> 16.0 }
        val anchor = body?.child("bodyPr")?.attr("anchor") ?: if (phType == "title" && ph != null) "ctr" else "t"
        val ins = body?.child("bodyPr")
        val padL = (ins?.attr("lIns")?.toLongOrNull() ?: 91440) * 100.0 / ctx.w
        val padT = (ins?.attr("tIns")?.toLongOrNull() ?: 45720) * 100.0 / ctx.w
        css.append(String.format(Locale.US, "padding:%.2fcqw %.2fcqw;", padT, padL))

        sb.append("<div class=\"el t-$anchor\" style=\"$css\">")
        if (body != null) sb.append(text(body, defaultPt, ctx))
        sb.append("</div>")
    }

    private fun defaultBox(type: String, ctx: Ctx): Box = when (type) {
        "title" -> Box(ctx.w / 20, ctx.h / 20, ctx.w * 9 / 10, ctx.h / 5)
        "subTitle" -> Box(ctx.w / 10, ctx.h * 2 / 5, ctx.w * 4 / 5, ctx.h / 4)
        else -> Box(ctx.w / 20, ctx.h * 3 / 10, ctx.w * 9 / 10, ctx.h * 3 / 5)
    }

    private fun fontCss(pt: Double, ctx: Ctx): String {
        val cqw = pt * 12700.0 * 100.0 / ctx.w
        return String.format(Locale.US, "font-size:%.3fvw;font-size:%.3fcqw;", cqw * 0.95, cqw)
    }

    private fun text(body: Node, defaultPt: Double, ctx: Ctx): String {
        val sb = StringBuilder()
        // list-level default sizes from the shape's own list style, if any
        val lvl1Sz = body.path("lstStyle", "lvl1pPr", "defRPr")?.attr("sz")?.toDoubleOrNull()?.div(100)
        val basePt = lvl1Sz ?: defaultPt
        for (p in body.children("p")) {
            val pPr = p.child("pPr")
            val algn = when (pPr?.attr("algn")) { "ctr" -> "center"; "r" -> "right"; "just", "dist" -> "justify"; "l" -> "left"; else -> null }
            val rtl = pPr?.attr("rtl") == "1"
            val level = pPr?.attr("lvl")?.toIntOrNull() ?: 0
            val bullet = pPr?.path("buChar")?.attr("char")
            val css = StringBuilder()
            algn?.let { css.append("text-align:$it;") }
            if (level > 0) css.append(String.format(Locale.US, "margin-inline-start:%.2fcqw;", level * 3.0))
            val inner = StringBuilder()
            var maxPt = 0.0
            for (r in p.children) {
                when (r.local) {
                    "r", "fld" -> {
                        val t = r.child("t")?.allText() ?: continue
                        val rPr = r.child("rPr")
                        val pt = rPr?.attr("sz")?.toDoubleOrNull()?.div(100) ?: basePt
                        maxPt = maxOf(maxPt, pt)
                        inner.append(run(Html.esc(t), rPr, pt, ctx))
                    }
                    "br" -> inner.append("<br>")
                }
            }
            if (maxPt == 0.0) maxPt = p.child("endParaRPr")?.attr("sz")?.toDoubleOrNull()?.div(100) ?: basePt
            css.append(fontCss(maxPt, ctx))
            val content = if (inner.isEmpty()) "&nbsp;" else (if (bullet != null && bullet.isNotBlank()) Html.esc(bullet) + " " else "") + inner
            sb.append("<p dir=\"${if (rtl) "rtl" else "auto"}\" style=\"$css\">").append(content).append("</p>")
        }
        return sb.toString()
    }

    private fun run(text: String, rPr: Node?, pt: Double, ctx: Ctx): String {
        val css = StringBuilder(fontCss(pt, ctx))
        rPr?.path("solidFill", "srgbClr")?.attr("val")?.takeIf { it.matches(HEX6) }?.let { css.append("color:#$it;") }
        var s = "<span style=\"$css\">$text</span>"
        if (rPr?.attr("b") == "1") s = "<b>$s</b>"
        if (rPr?.attr("i") == "1") s = "<i>$s</i>"
        val u = rPr?.attr("u")
        if (u != null && u != "none") s = "<u>$s</u>"
        if (rPr?.attr("strike").let { it != null && it != "noStrike" }) s = "<s>$s</s>"
        return s
    }

    private fun picture(pic: Node, xf: Xf, ctx: Ctx, sb: StringBuilder) {
        val xfrm = pic.path("spPr", "xfrm")
        val box = xfrmBox(xfrm)?.let { xf.map(it) } ?: return
        val id = pic.path("blipFill", "blip")?.attr("embed") ?: return
        val rel = ctx.rels[id] ?: return
        if (rel.external) return
        val uri = ctx.zip.imageUri(rel.target) ?: return
        sb.append("<div class=\"el\" style=\"${boxCss(box, ctx, xfrm)}\"><img src=\"$uri\"></div>")
    }

    private fun frame(gf: Node, xf: Xf, ctx: Ctx, sb: StringBuilder) {
        val x = gf.child("xfrm")
        val box = xfrmBox(x)?.let { xf.map(it) } ?: return
        val data = gf.path("graphic", "graphicData")
        val tbl = data?.child("tbl")
        if (tbl != null) {
            sb.append("<div class=\"el\" style=\"${boxCss(box, ctx, null)}\"><table>")
            for (tr in tbl.children("tr")) {
                sb.append("<tr>")
                for (tc in tr.children("tc")) {
                    if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                    val span = tc.attr("gridSpan")?.toIntOrNull()?.let { " colspan=\"$it\"" } ?: ""
                    val rspan = tc.attr("rowSpan")?.toIntOrNull()?.let { " rowspan=\"$it\"" } ?: ""
                    val fill = tc.path("tcPr", "solidFill", "srgbClr")?.attr("val")?.takeIf { it.matches(HEX6) }?.let { " style=\"background:#$it\"" } ?: ""
                    sb.append("<td$span$rspan$fill>")
                    tc.child("txBody")?.let { sb.append(text(it, 14.0, ctx)) }
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table></div>")
        } else {
            val uri = data?.attr("uri") ?: ""
            val label = when {
                uri.contains("chart") -> "📊"
                uri.contains("diagram") -> "🔷"
                else -> "▢"
            }
            sb.append("<div class=\"el chart\" style=\"${boxCss(box, ctx, null)}\">$label</div>")
        }
    }

    private val HEX6 = Regex("[0-9A-Fa-f]{6}")
}
