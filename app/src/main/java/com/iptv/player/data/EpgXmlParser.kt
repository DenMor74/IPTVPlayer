package com.iptv.player.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Потоковый разбор XMLTV: читаем прямо из сети, не держим файл в памяти.
 * Сохраняем только каналы из плейлиста и только передачи в окне [fromMs, toMs].
 * Сопоставление канала: сначала по tvg-id, затем по названию (display-name).
 */
class EpgXmlParser(
    private val tvgToChannel: Map<String, String>,
    private val nameToChannel: Map<String, String>,
    private val fromMs: Long,
    private val toMs: Long
) {
    fun parse(input: InputStream): Map<String, List<Programme>> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val idMap = HashMap<String, String>()                 // id в XMLTV -> id канала в плейлисте
        val out = HashMap<String, MutableList<Programme>>()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> readChannel(p, idMap)
                    "programme" -> readProgramme(p, idMap, out)
                }
            }
            ev = p.next()
        }
        return out.mapValues { e -> e.value.sortedBy { it.start } }
    }

    private fun readChannel(p: XmlPullParser, idMap: MutableMap<String, String>) {
        val id = p.getAttributeValue(null, "id") ?: ""
        var target: String? = tvgToChannel[id]
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && p.name == "channel") break
            if (e == XmlPullParser.START_TAG && p.name == "display-name" && target == null) {
                target = nameToChannel[EpgLogic.normalizeName(p.nextText())]
            }
        }
        if (target != null) idMap[id] = target
    }

    private fun readProgramme(
        p: XmlPullParser,
        idMap: Map<String, String>,
        out: MutableMap<String, MutableList<Programme>>
    ) {
        val chId = p.getAttributeValue(null, "channel")?.let { idMap[it] }
        val start = EpgLogic.parseXmltvTime(p.getAttributeValue(null, "start") ?: "")
        val stop = EpgLogic.parseXmltvTime(p.getAttributeValue(null, "stop") ?: "")
        if (chId == null || start == null || stop == null || stop < fromMs || start > toMs) {
            skipToEnd(p)
            return
        }
        var title = ""
        var desc = ""
        while (true) {
            val e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && p.name == "programme") break
            if (e == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> { val t = p.nextText().trim(); if (title.isEmpty()) title = t }
                    "desc" -> { val t = p.nextText().trim(); if (desc.isEmpty()) desc = t }
                }
            }
        }
        out.getOrPut(chId) { ArrayList() }.add(Programme(chId, start, stop, title, desc))
    }

    private fun skipToEnd(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
