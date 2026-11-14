package com.example.newsreader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NewsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("news_prefs", Context.MODE_PRIVATE)

    // Fonti predefinite iniziali (se l'utente non ne ha ancora aggiunte/rimosse)
	private val defaultFeeds = linkedMapOf(
			"ANSA" to "https://www.ansa.it/sito/ansait_rss.xml",
			"Il Post" to "https://www.ilpost.it/feed/",
			"Il Mattino" to "https://www.ilmattino.it/rss/home.xml",
			"Il Roma" to "https://www.ilroma.net/sezioni/268/rss",
			"Corriere della Sera" to "https://xml2.corriereobjects.it/rss/homepage.xml",
			"la Repubblica" to "https://www.repubblica.it/rss/homepage/rss2.0.xml",
			"Il Sole 24 Ore" to "https://www.ilsole24ore.com/rss/primapagina.xml",
			"Sky TG24" to "https://feed.sky.it/notizie/rss",
			"La Stampa" to "https://www.lastampa.it/rss/homepage.xml",
			"AGI" to "https://www.agi.it/rss/",
			"Adnkronos" to "https://www.adnkronos.com/rss/",
			"Wired Italia" to "https://www.wired.it/feed/",
			"HDblog" to "https://hdblog.it/rss",
			"Everyeye Tech" to "https://www.everyeye.it/feed/rss/tech",
			"Gazzetta dello Sport" to "https://www.gazzetta.it/rss/home.xml",
			"Corriere dello Sport" to "https://www.corrieredellosport.it/rss/"
		)

    fun getSavedFeeds(): Map<String, String> {
        val saved = prefs.getStringSet("custom_feeds", null)
        val savedMap = LinkedHashMap<String, String>()

        if (saved != null) {
            for (item in saved) {
                val parts = item.split("|||")
                if (parts.size == 2) {
                    savedMap[parts[0]] = parts[1]
                }
            }
        }

        // FORATURA INTELLIGENTE: Controlla se nel codice sono state aggiunte nuove testate 
        // e le inserisce automaticamente, rispettando però quelle che l'utente ha eventualmente eliminato o modificato.
        var updated = false
        for ((name, url) in defaultFeeds) {
            // Se la testata di default non è nei salvataggi e l'utente non l'ha esplicitamente cancellata
            if (!savedMap.containsKey(name) && !prefs.getBoolean("deleted_$name", false)) {
                savedMap[name] = url
                updated = true
            }
        }

        // Se è la primissima volta o abbiamo aggiunto nuove fonti, salviamo subito la mappa aggiornata
        if (saved == null || updated) {
            saveFeeds(savedMap)
        }

        return savedMap
    }

    fun saveFeeds(feeds: Map<String, String>) {
        val set = feeds.map { "${it.key}|||${it.value}" }.toSet()
        prefs.edit().putStringSet("custom_feeds", set).apply()
    }

    suspend fun fetchNewsList(): List<NewsPreview> = withContext(Dispatchers.IO) {
        val allNews = mutableListOf<NewsPreview>()
        val currentFeeds = getSavedFeeds()

        for ((source, feedUrl) in currentFeeds) {
            try {
                val doc = Jsoup.connect(feedUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(5000)
                    .parser(Parser.xmlParser())
                    .get()

                val items = doc.select("item")
                for (item in items) {
                    val title = item.selectFirst("title")?.text()?.trim() ?: continue
                    val link = item.selectFirst("link")?.text()?.trim() ?: continue
                    val pubDateRaw = item.selectFirst("pubDate")?.text()?.trim() ?: ""

                    var imgUrl = item.selectFirst("enclosure")?.attr("url")
                        ?: item.selectFirst("media|content")?.attr("url")

                    if (imgUrl.isNullOrEmpty()) {
                        val descHtml = item.selectFirst("description")?.text() ?: ""
                        val descDoc = Jsoup.parse(descHtml)
                        imgUrl = descDoc.selectFirst("img")?.attr("abs:src")
                    }

                    val (formattedDate, rawDateObj) = parseDate(pubDateRaw)

                    allNews.add(
                        NewsPreview(
                            id = link.hashCode().toString(),
                            title = title,
                            publishedDate = formattedDate,
                            rawDate = rawDateObj,
                            imageUrl = imgUrl?.takeIf { it.isNotBlank() },
                            articleUrl = link,
                            sourceName = source
                        )
                    )
                }
            } catch (e: Exception) {
                // Se una testata fallisce (es. blocco anti-bot o URL errato), stampa l'errore ma CONTINUA con le altre
                println("Errore caricamento feed $source: ${e.message}")
            }
        }

        allNews.sortedByDescending { it.rawDate }
    }

    suspend fun fetchFullArticle(preview: NewsPreview): FullArticle = withContext(Dispatchers.IO) {
        val blocks = mutableListOf<ArticleBlock>()
        var heroImage = preview.imageUrl

        try {
            val doc = Jsoup.connect(preview.articleUrl)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .timeout(10000)
                .get()

            if (heroImage.isNullOrEmpty()) {
                heroImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            }

            // Rimuove elementi indesiderati
            doc.select("script, style, nav, header, footer, aside, .ads, .related, .cookie, .author-box").remove()

            // Selettori mirati specifici per testate difficili (Repubblica, Sole 24 Ore, Corriere, ecc.)
            val articleContainer = doc.selectFirst("div[itemprop=articleBody]")
                ?: doc.selectFirst("article")
                ?: doc.selectFirst(".article-body")
                ?: doc.selectFirst(".entry-content")
                ?: doc.selectFirst("main")
                ?: doc.body()

            val elements = articleContainer.select("p, img, h2, h3")
            val seenImages = mutableSetOf<String>()
            if (heroImage != null) seenImages.add(heroImage)

            for (el in elements) {
                when (el.tagName()) {
                    "p", "h2", "h3" -> {
                        val text = el.text().trim()
                        // Filtra frasi troppo corte o avvisi di cookie/newsletter
                        if (text.length > 25 && !text.contains("Iscriviti alla newsletter") && !text.contains("Riproduzione riservata")) {
                            blocks.add(ArticleBlock.TextParagraph(text))
                        }
                    }
                    "img" -> {
                        val src = el.attr("abs:src").ifEmpty { el.attr("abs:data-src") }
                        if (src.isNotBlank() && src.startsWith("http") && !seenImages.contains(src)) {
                            if (!src.contains("icon") && !src.contains("logo") && !src.contains("pixel") && !src.contains("avatar")) {
                                seenImages.add(src)
                                blocks.add(ArticleBlock.ImageBlock(src, el.attr("alt")))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            blocks.add(ArticleBlock.TextParagraph("Impossibile estrarre il testo completo per questa pagina. Puoi leggere l'articolo originale dal link in fondo."))
        }

        FullArticle(
            title = preview.title,
            publishedDate = preview.publishedDate,
            mainImageUrl = heroImage,
            blocks = blocks,
            sourceUrl = preview.articleUrl
        )
    }

    private fun parseDate(rawDate: String): Pair<String, Date> {
        val formats = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
        )

        for (format in formats) {
            try {
                format.isLenient = true
                val date = format.parse(rawDate)
                if (date != null) {
                    val outputFormat = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.ITALIAN)
                    return Pair(outputFormat.format(date), date)
                }
            } catch (_: Exception) {}
        }
        return Pair(rawDate, Date(0))
    }
	fun resetDefaultFeeds(): Map<String, String> {
        // Pulisce eventuali flag di eliminazione salvati dall'utente
        val editor = prefs.edit()
        for (name in defaultFeeds.keys) {
            editor.remove("deleted_$name")
        }
        editor.remove("custom_feeds")
        editor.apply()

        // Salva e restituisce i feed predefiniti puliti
        saveFeeds(defaultFeeds)
        return defaultFeeds
    }
}