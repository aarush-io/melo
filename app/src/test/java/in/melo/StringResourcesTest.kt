package `in`.melo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Lint already fails on missing or extra translations; this checks what it does not: that every
 * translation takes the same arguments as the English text, and that Russian plurals have the
 * forms Russian needs.
 */
class StringResourcesTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first(File::isDirectory)

    private class Strings(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun read(folder: String): Strings {
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        File(res, folder).listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val children = root.childNodes
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                if (element.getAttribute("translatable") == "false") continue
                when (element.tagName) {
                    "string" -> strings[element.getAttribute("name")] = element.textContent
                    "plurals" -> {
                        val items = element.getElementsByTagName("item")
                        plurals[element.getAttribute("name")] = (0 until items.length).associate { index ->
                            val item = items.item(index) as Element
                            item.getAttribute("quantity") to item.textContent
                        }
                    }
                }
            }
        }
        return Strings(strings, plurals)
    }

    private fun arguments(text: String): List<String> = ARGUMENT.findAll(text).map { it.value }.sorted().toList()

    @Test fun translationsTakeTheSameArguments() {
        val english = read("values")
        listOf("values-fr", "values-ru").forEach { folder ->
            val translated = read(folder)
            translated.strings.forEach { (name, text) ->
                val source = english.strings[name] ?: return@forEach
                assertEquals("$folder/$name", arguments(source), arguments(text))
            }
        }
    }

    @Test fun russianIsComplete() {
        val english = read("values")
        val russian = read("values-ru")
        val missing = english.strings.keys.filter { it !in russian.strings && it in read("values-fr").strings }
        assertTrue("Missing Russian strings: $missing", missing.isEmpty())
        assertEquals(english.plurals.keys, russian.plurals.keys)
    }

    @Test fun russianPluralsHaveEveryForm() {
        read("values-ru").plurals.forEach { (name, items) ->
            assertTrue("$name: ${items.keys}", items.keys.containsAll(listOf("one", "few", "many", "other")))
            // "one" also covers 21, 31…: the count must be printed, never written out.
            assertTrue("$name: the 'one' form must show the count", items.getValue("one").contains("%d") || items.getValue("one").contains("%1\$d"))
        }
    }

    private companion object {
        val ARGUMENT = Regex("""%(\d+\$)?[sdf]""")
    }
}
