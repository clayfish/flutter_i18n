package eu.long1.flutter.i18n.workers

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import eu.long1.flutter.i18n.arb.ArbFileType
import eu.long1.flutter.i18n.files.FileHelpers
import java.util.regex.Pattern
import kotlin.collections.ArrayList
import kotlin.collections.HashMap


class I18nFileGenerator(private val project: Project) {

    private val psiManager = PsiManager.getInstance(project)
    private val documentManager = PsiDocumentManager.getInstance(project)
    private val valuesFolder = FileHelpers.getValuesFolder(project)

    fun generate() {
        val files = stringFiles()
        if (files.isEmpty()) files.add(createFileForLang("en"))

        val data = HashMap<String, HashMap<String, String>>()

        files.forEach {
            val lang = it.nameWithoutExtension.substringAfter("_")
            val json = psiManager.findFile(it) as JsonFile
            data[lang] = getStringFromFile(json) ?: HashMap()
        }

        val builder = StringBuilder()
        builder.append(i18nFileImports)
        builder.append("//This file is automatically generated. DO NOT EDIT, all your changes would be lost.\n")
        appendSClass(data["en"]!!, builder)

        data.keys.forEach {
            if (it == "en") builder.append("class en extends S {\n  en(Locale locale) : super(locale);\n}\n")
            else appendLangClass(it, data, builder)
        }

        appendDelegateClass(data, builder)

        val i18nFile = builder.toString()

        val file = FileHelpers.getI18nFile(project)
        val dartFile = psiManager.findFile(file)!!
        val document = documentManager.getDocument(dartFile)!!
        document.setText(i18nFile)

        CodeStyleManager.getInstance(psiManager).reformatText(dartFile, 0, i18nFile.length)
    }

    private fun stringFiles(): ArrayList<VirtualFile> = valuesFolder.children.filter {
        it.extension == ArbFileType.defaultExtension && it.name.startsWith("strings_", true)
    } as ArrayList

    internal fun appendSClass(en: HashMap<String, String>, builder: StringBuilder) {
        val ids = ArrayList<String>(en.keys)

        val pluralsMaps = HashMap<String, ArrayList<String>>()
        val plurals = findPluralsIds(ids, pluralsMaps)
        ids -= plurals

        val parametrized = ids.filter { en[it]!!.contains("$") }
        ids -= parametrized

        builder.append(sClassHeader)

        ids.forEach { appendStringMethod(it, en[it]!!, builder, false) }
        parametrized.forEach { appendParametrizedMethod(it, en[it]!!, builder, false) }
        pluralsMaps.keys.forEach { appendPluralMethod(it, pluralsMaps[it]!!, en, builder, false) }

        builder.append("}\n\n")
    }

    internal fun appendLangClass(lang: String, map: HashMap<String, HashMap<String, String>>, builder: StringBuilder) {
        val langMap = map[lang]!!
        val enIds = ArrayList<String>(map["en"]!!.keys)
        val ids = ArrayList(langMap.keys).filter { enIds.contains(it) } as ArrayList

        val pluralsMaps = HashMap<String, ArrayList<String>>()
        val plurals = findPluralsIds(ids, pluralsMaps)
        ids -= plurals

        val parametrized = ids.filter { langMap[it]!!.contains("$") }
        ids -= parametrized


        builder.append("class $lang extends S {\n  $lang(Locale locale) : super(locale);\n\n   " +
                "@override\n  TextDirection get textDirection => TextDirection.${if (rtl.contains(lang.split("_")[0])) "rtl" else "ltr"};\n\n")

        ids.forEach { appendStringMethod(it, langMap[it]!!, builder) }
        parametrized.forEach { appendParametrizedMethod(it, langMap[it]!!, builder) }
        pluralsMaps.keys.forEach { appendPluralMethod(it, pluralsMaps[it]!!, langMap, builder) }

        builder.append("}\n\n")

        //for hebrew iw=he
        if (lang.startsWith("iw")) {
            builder.append("class he_IL extends $lang {\n  he_IL(Locale locale) : super(locale);\n\n   " +
                    "@override\n  TextDirection get textDirection => TextDirection.rtl;\n\n}")
        }
    }

    private fun appendDelegateClass(map: HashMap<String, HashMap<String, String>>, builder: StringBuilder) {
        builder.append(delegateClassHeader)
        map.keys.forEach {
            val langParts = it.split("_")
            val lang = langParts[0]
            val country = if (langParts.size == 2) langParts[1] else ""

            builder.append("      new Locale(\"$lang\", \"$country\"),\n")

            //for hebrew iw=he
            if (it.startsWith("iw")) {
                builder.append("      new Locale(\"he\", \"IL\"),\n")
            }
        }

        builder.append(delegateClassResolution)
        map.keys.forEach {
            builder.append("      case \"$it\":\n        return new SynchronousFuture<WidgetsLocalizations>(new $it(locale));\n")

            //for hebrew iw=he
            if (it.startsWith("iw")) {
                builder.append("      case \"he_IL\":\n        return new SynchronousFuture<WidgetsLocalizations>(new he_IL(locale));\n")
            }
        }

        builder.append(delegateClassEnd)
    }


    internal fun appendStringMethod(id: String, value: String, builder: StringBuilder, isOverride: Boolean = true) {
        if (isOverride) builder.append("  @override\n")
        builder.append("  String get $id => \"$value\";\n")
    }

    internal fun appendParametrizedMethod(id: String, value: String, builder: StringBuilder, isOverride: Boolean = true) {
        PARAMETER_MATCHER.reset(value)

        if (isOverride) builder.append("  @override\n")
        builder.append("  String $id(")
        while (PARAMETER_MATCHER.find()) {
            val parameter = PARAMETER_MATCHER.group().substring(1)
            builder.append("String $parameter, ")
        }
        builder.setLength(builder.length - 2)
        builder.append(") => \"$value\";\n")
    }

    internal fun appendPluralMethod(id: String, countsList: ArrayList<String>, valuesMap: HashMap<String, String>,
                                    builder: StringBuilder, isOverride: Boolean = true) {
        val zero = countsList.contains("Zero")
        val one = countsList.contains("One")
        val two = countsList.contains("Two")
        val few = countsList.contains("Few")
        val many = countsList.contains("Many")


        val parameterName: String = {
            PARAMETER_MATCHER.reset(valuesMap["${id}Other"]!!).find()
            PARAMETER_MATCHER.group().substring(1)
        }()

        if (isOverride) builder.append("  @override\n")
        builder.append("  String $id(String $parameterName) {\n    switch ($parameterName) {\n")

        if (zero) builder.append("      case \"0\":\n        return \"${valuesMap["${id}Zero"]!!}\";\n")
        if (one) builder.append("      case \"1\":\n        return \"${valuesMap["${id}One"]!!}\";\n")
        if (two) builder.append("      case \"2\":\n        return \"${valuesMap["${id}Two"]!!}\";\n")
        if (few) builder.append("      case \"few\":\n        return \"${valuesMap["${id}Few"]!!}\";\n")
        if (many) builder.append("      case \"many\":\n        return \"${valuesMap["${id}Many"]!!}\";\n")
        builder.append("      default:\n        return \"${valuesMap["${id}Other"]!!}\";\n    }\n  }\n")
    }

    fun getCountFromValue(text: String): String? = when (text) {
        "0" -> "Zero"
        "1" -> "One"
        "2" -> "Two"
        "few" -> "Few"
        "many" -> "Many"
        else -> throw IllegalArgumentException("This value $text is not valid.")
    }

    /**
     * Create a file in the values folder for the given language.
     */
    private fun createFileForLang(lang: String): VirtualFile {
        val virtualFile = valuesFolder.findOrCreateChildData(this, "strings_$lang.arb")
        val psiFile = psiManager.findFile(virtualFile)!! as JsonFile
        val doc = documentManager.getDocument(psiFile)!!
        doc.setText("{}")
        CodeStyleManager.getInstance(psiManager).reformat(psiFile)
        return virtualFile
    }

    /**
     * Searches for plurals in the ids of the strings and return a list will al of them.
     *
     * @param ids contains the list that needs to be searched for plurals
     * @param pluralsMaps we append to this map the id of the plural and a list of all the qualities("One", "Two", ...)
     * that were declared.
     *
     * @return A list with the ids that are considered plurals and that will be treated separately.
     *
     * NOTE: It is not considered a plural when the Other quantity is not declared. In this case the other qualities
     * will be treated as independent ids.
     */
    internal fun findPluralsIds(ids: ArrayList<String>, pluralsMaps: HashMap<String, ArrayList<String>>): List<String> {
        val map = HashMap<String, ArrayList<String>>()
        val pluralIds = ids.filter {
            PLURAL_MATCHER.reset(it)
            val find = PLURAL_MATCHER.find()
            if (find) {
                val id = PLURAL_MATCHER.group(1)
                val quantity = PLURAL_MATCHER.group(2)
                val list = map[id] ?: ArrayList()
                list.add(quantity)
                map[id] = list
            }
            find
        } as ArrayList

        HashMap(map).forEach { id, counts ->
            if (counts.none { it == "Other" }) {
                counts.forEach { count -> pluralIds.remove("$id$count") }
                map.remove(id)
            }
        }

        pluralsMaps.putAll(map)
        return pluralIds
    }


    internal fun getStringFromFile(file: PsiFile): HashMap<String, String>? {
        if (PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java) != null) return null
        val langMap = HashMap<String, String>()
        PsiTreeUtil.findChildrenOfType(file, JsonProperty::class.java).forEach {
            langMap[it.name] = it.value?.text!!.drop(1).dropLast(1)
        }
        return langMap
    }

    companion object {
        private val PLURAL_MATCHER = Pattern.compile("(.*)(Zero|One|Two|Few|Many|Other)").matcher("")
        private val PARAMETER_MATCHER = Pattern.compile("\\$[^\\p{Punct}\\p{Space}\\p{sc=Han}\\p{sc=Hiragana}\\p{sc=Katakana}–]*").matcher("")

        private const val i18nFileImports = "import 'dart:async';\n\nimport 'package:flutter/foundation.dart';\nimport 'package:flutter/material.dart';\n\n"

        private const val sClassHeader = "class S extends WidgetsLocalizations {\n" +
                "  Locale _locale;\n" +
                "  String _lang;\n" +
                "\n" +
                "  S(this._locale) {\n" +
                "    _lang = getLang(_locale);\n" +
                "    print('Current locale: \$_lang');\n" +
                "  }\n" +
                "\n" +
                "  static final GeneratedLocalizationsDelegate delegate =\n" +
                "      new GeneratedLocalizationsDelegate();\n" +
                "\n" +
                "  static S of(BuildContext context) {\n" +
                "    var s = Localizations.of<S>(context, WidgetsLocalizations);\n" +
                "    s._lang = getLang(s._locale);\n" +
                "    return s;\n" +
                "  }\n" +
                "\n" +
                "  @override\n" +
                "  TextDirection get textDirection => TextDirection.ltr;" +
                "\n\n"

        private const val delegateClassHeader = "class GeneratedLocalizationsDelegate extends LocalizationsDelegate<WidgetsLocalizations> {\n" +
                "  const GeneratedLocalizationsDelegate();\n" +
                "\n" +
                "  List<Locale> get supportedLocales {\n" +
                "    return [\n"


        private const val delegateClassResolution = "    ];\n" +
                "  }\n" +
                "\n" +
                "  LocaleResolutionCallback resolution({Locale fallback}) {\n" +
                "    return (Locale locale, Iterable<Locale> supported) {\n" +
                "      var languageLocale = new Locale(locale.languageCode, \"\");\n" +
                "      if (supported.contains(locale))\n" +
                "        return locale;\n" +
                "      else if (supported.contains(languageLocale))\n" +
                "        return languageLocale;\n" +
                "      else {\n" +
                "        var fallbackLocale = fallback ?? supported.first;\n" +
                "        return fallbackLocale;\n" +
                "      }\n" +
                "    };\n" +
                "  }\n" +
                "\n" +
                "  Future<WidgetsLocalizations> load(Locale locale) {\n" +
                "    String lang = getLang(locale);\n" +
                "    switch (lang) {\n"

        private const val delegateClassEnd = "      default:\n" +
                "        return new SynchronousFuture<WidgetsLocalizations>(new S(locale));\n" +
                "    }\n" +
                "  }\n" +
                "\n" +
                "  bool isSupported(Locale locale) => supportedLocales.contains(locale);\n" +
                "\n" +
                "  bool shouldReload(GeneratedLocalizationsDelegate old) => false;\n" +
                "}\n" +
                "\n" +
                "String getLang(Locale l) => l.countryCode != null && l.countryCode.isEmpty\n" +
                "    ? l.languageCode\n" +
                "    : l.toString();"


        private val rtl: Set<String> = setOf("ar", "dv", "fa", "ha", "he", "iw", "ji", "ps", "ur", "yi")
    }


}