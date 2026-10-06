package ai.hans.standard.localization

import ai.hans.standard.R
import java.io.File
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** JVM-only resource fixture. Production always resolves through Android Resources. */
internal class TestResourceTextResolver(override val locale: Locale = Locale.ENGLISH) : HansTextResolver {
    private val strings: Map<String, String>
    private val plurals: Map<String, Map<String, String>>
    private val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
    private val pluralNames = R.plurals::class.java.fields.associate { it.getInt(null) to it.name }

    init {
        val resources = listOf(File("src/main/res"), File("android/app/src/main/res"))
            .firstOrNull(File::isDirectory) ?: error("Android resource source directory unavailable")
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            // Android's compile-time XMLConstants omits these Java host-parser attributes.
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
            setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        }
        val translatedStrings = linkedMapOf<String, String>()
        val translatedPlurals = linkedMapOf<String, Map<String, String>>()
        val directories = listOf(File(resources, "values")) +
            if (locale.language == "de") listOf(File(resources, "values-de")) else emptyList()
        directories.forEach { directory ->
            directory.listFiles { file -> file.extension == "xml" }.orEmpty().sortedBy(File::getName)
                .forEach { file ->
                    val children = factory.newDocumentBuilder().parse(file).documentElement.childNodes
                    repeat(children.length) { index ->
                        val node = children.item(index) as? Element ?: return@repeat
                        when (node.tagName) {
                            "string" -> translatedStrings[node.getAttribute("name")] = unescape(node.textContent)
                            "plurals" -> {
                                val items = node.getElementsByTagName("item")
                                translatedPlurals[node.getAttribute("name")] = (0 until items.length)
                                    .associate { item -> (items.item(item) as Element).let {
                                        it.getAttribute("quantity") to unescape(it.textContent)
                                    } }
                            }
                        }
                    }
                }
        }
        strings = translatedStrings
        plurals = translatedPlurals
    }

    override fun text(resourceId: Int, vararg formatArgs: Any): String {
        val name = names[resourceId] ?: error("Unknown string resource: $resourceId")
        val value = strings[name] ?: error("Missing string: $name")
        return if (formatArgs.isEmpty()) value else String.format(locale, value, *formatArgs)
    }

    override fun quantity(resourceId: Int, quantity: Int, vararg formatArgs: Any): String {
        val name = pluralNames[resourceId] ?: error("Unknown plural resource: $resourceId")
        val forms = plurals[name] ?: error("Missing plural: $name")
        val value = forms[if (quantity == 1) "one" else "other"] ?: checkNotNull(forms["other"])
        return if (formatArgs.isEmpty()) value else String.format(locale, value, *formatArgs)
    }

    private fun unescape(value: String): String = value.trim().removeSurrounding("\"")
        .replace("\\n", "\n").replace("\\t", "\t").replace("\\'", "'").replace("\\\"", "\"")
}
