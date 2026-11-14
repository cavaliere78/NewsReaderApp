package com.example.newsreader

import android.content.Context
import android.content.SharedPreferences
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.*

data class NewsPreview(
    val id: String,
    val title: String,
    val publishedDate: String,
    val rawDate: Date,
    val imageUrl: String?,
    val articleUrl: String,
    val sourceName: String
)

data class FullArticle(
    val title: String,
    val publishedDate: String,
    val mainImageUrl: String?,
    val blocks: List<ArticleBlock>,
    val sourceUrl: String
)

sealed class ArticleBlock {
    data class TextParagraph(val text: String) : ArticleBlock()
    data class ImageBlock(val imageUrl: String, val caption: String?) : ArticleBlock()
}

class NewsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("news_prefs", Context.MODE_PRIVATE)

    // Fonti predefinite con URL RSS stabili e verificati per l'Italia
    val defaultFeeds = linkedMapOf(
        "ANSA" to "https://www.ansa.it/sito/notizie/mondo/mondo_rss.xml",
        "Corriere della Sera" to "https://xml.corriereobjects.it/rss/primapagina.xml",
        "la Repubblica" to "https://www.repubblica.it/rss/homepage/rss2.0.xml",
        "Il Sole 24 Ore" to "https://www.ilsole24ore.com/rss/italia.xml",
        "Gazzetta dello Sport" to "https://www.gazzetta.it/rss/home.xml",
        "Corriere dello Sport" to "https://www.corrieredellosport.it/rss/",
        "Il Mattino" to "https://www.ilmattino.it/rss/primapagina.xml",
        "Il Post" to "https://www.ilpost.it/feed/",
        "Sky TG24" to "https://tg24.sky.it/rss/ ultimora.xml",
        "Adnkronos" to "https://www.adnkronos.com/rss.xml",
        "Il Roma" to "https://www.ilroma.net/sezioni/268/rss"
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

        // Sincronizzazione automatica delle nuove fonti di default senza sovrascrivere le preferenze utente
        var updated = false
        for ((name, url) in defaultFeeds) {
            if (!savedMap.containsKey(name) && !prefs.getBoolean("deleted_$name", false)) {
                savedMap[name] = url
                updated = true
            }
        }

        if (saved == null || updated) {
            saveFeeds(savedMap)
        }

        return savedMap
    }

    fun saveFeeds(feeds: Map<String, String>) {
        val set = mutableSetOf<String>()
        for ((name, url) in feeds) {
            set.add("$name|||$url")
        }
        prefs.edit().putStringSet("custom_feeds", set).apply()
    }

    fun resetDefaultFeeds(): Map<String, String> {
        val editor = prefs.edit()
        for (name in defaultFeeds.keys) {
            editor.remove("deleted_$name")
        }
        editor.remove("custom_feeds")
        editor.apply()
        saveFeeds(defaultFeeds)
        return defaultFeeds
    }

    suspend fun fetchNewsList(): List<NewsPreview> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val allNews = mutableListOf<NewsPreview>()
        val currentFeeds = getSavedFeeds()

        for ((source, feedUrl) in currentFeeds) {
            try {
                // Connessione avanzata con header browser per superare i blocchi anti-bot
                val doc = Jsoup.connect(feedUrl)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
                    .timeout(8000)
                    .followRedirects(true)
                    .parser(Parser.xmlParser())
                    .get()

                // Supporto combinato per RSS (<item>) e Atom (<entry>)
                val items = doc.select("item, entry")
                for (item in items) {
                    val title = item.selectFirst("title")?.text()?.trim() ?: continue
                    
                    // Gestione intelligente del link (supporta tag <link> testuale e attributi href Atom)
                    val linkElement = item.selectFirst("link")
                    val link = linkElement?.text()?.takeIf { it.isNotBlank() && it.startsWith("http") }
                        ?: linkElement?.attr("abs:href")
                        ?: item.selectFirst("link")?.attr("href")
                        ?: continue

                    val pubDateRaw = item.selectFirst("pubDate, published, updated")?.text()?.trim() ?: ""

                    var imgUrl = item.selectFirst("enclosure")?.attr("url")
                        ?: item.selectFirst("media|content")?.attr("url")

                    if (imgUrl.isNullOrEmpty()) {
                        val descHtml = item.selectFirst("description, content")?.text() ?: ""
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
                println("Impossibile caricare il feed da $source ($feedUrl): ${e.message}")
            }
        }

        allNews.sortedByDescending { rawDate -> rawDate.rawDate }
    }

    private fun parseDate(dateStr: String): Pair<String, Date> {
        val formats = arrayOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH),
            SimpleDateFormat("dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
        )

        for (format in formats) {
            try {
                format.isLenient = true
                val date = format.parse(dateStr)
                if (date != null) {
                    val outputFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ITALIAN)
                    return Pair(outputFormat.format(date), date)
                }
            } catch (_: Exception) {}
        }

        return Pair(dateStr, Date(0))
    }

    suspend fun fetchFullArticle(preview: NewsPreview): FullArticle = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(preview.articleUrl)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .timeout(10000)
                .get()

            doc.select("script, style, nav, footer, header, aside, .ads, .cookie-banner").remove()

            val title = doc.selectFirst("h1")?.text() ?: preview.title
            val mainImage = doc.selectFirst("article img, .post-content img, .entry-content img, img")?.attr("abs:src")

            val blocks = mutableListOf<ArticleBlock>()
            val paragraphs = doc.select("article p, .post-content p, .entry-content p, div.content p")

            if (paragraphs.isNotEmpty()) {
                for (p in paragraphs) {
                    val text = p.text().trim()
                    if (text.length > 20) {
                        blocks.add(ArticleBlock.TextParagraph(text))
                    }
                }
            } else {
                doc.select("p").forEach { p ->
                    val text = p.text().trim()
                    if (text.length > 30) {
                        blocks.add(ArticleBlock.TextParagraph(text))
                    }
                }
            }

            if (blocks.isEmpty()) {
                blocks.add(ArticleBlock.TextParagraph("Impossibile estrarre il testo completo formattato per questo articolo."))
            }

            FullArticle(
                title = title,
                publishedDate = preview.publishedDate,
                mainImageUrl = mainImage?.takeIf { it.isNotBlank() } ?: preview.imageUrl,
                blocks = blocks,
                sourceUrl = preview.articleUrl
            )
        } catch (e: Exception) {
            FullArticle(
                title = preview.title,
                publishedDate = preview.publishedDate,
                mainImageUrl = preview.imageUrl,
                blocks = listOf(ArticleBlock.TextParagraph("Errore di caricamento dell'articolo completo: ${e.message}")),
                sourceUrl = preview.articleUrl
            )
        }
    }
}