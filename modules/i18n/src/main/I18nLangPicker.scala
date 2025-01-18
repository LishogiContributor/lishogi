package lila.i18n

import play.api.i18n.Lang
import play.api.mvc.RequestHeader

object I18nLangPicker {

  def apply(req: RequestHeader, userLang: Option[String] = None): Lang =
    userLang
      .orElse(req.session get "lang")
      .flatMap(Lang.get)
      .flatMap(findCloser)
      .orElse(bestFromRequestHeaders(req))
      .getOrElse(defaultLang)

  def bestFromRequestHeaders(req: RequestHeader): Option[Lang] = {
    req.acceptLanguages.foldLeft(none[Lang]) {
      case (None, lang) => findCloser(lang)
      case (found, _)   => found
    }
  }

  def allFromRequestHeaders(req: RequestHeader): List[Lang] =
    req.acceptLanguages.flatMap(findCloser).distinct.toList

  def byStr(str: String): Option[Lang] =
    Lang get str flatMap findCloser

  def sortFor(langs: List[Lang], req: RequestHeader): List[Lang] = {
    val mine = allFromRequestHeaders(req).zipWithIndex.toMap
    langs.sortBy { mine.getOrElse(_, Int.MaxValue) }
  }

  private val defaultByLanguage: Map[String, Lang] =
    LangList.all.keys
      .foldLeft(Map.empty[String, Lang]) { case (acc, lang) =>
        acc + (lang.language -> lang)
      }

  def findCloser(to: Lang): Option[Lang] =
    if (LangList.all.keySet contains to) Some(to)
    else if (to.language == "zh") {
      if (List("TW", "HK", "MO").contains(to.country) || to.script == "Hant") Lang.get("zh-TW")
      else Lang.get("zh-CN")
    } else defaultByLanguage.get(to.language)

  def byQuery(code: String): Option[Lang] = byStr(code)

}
