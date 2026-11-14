package com.example.newsreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale

class NewsRepository {

    private val rssFeeds = listOf(
        "ANSA" to "https://www.ansa.it/sito/ansait_rss.xml",
        "Il Post" to "https://www.ilpost.it/feed/",
        "Il Mattino" to "https://www.ilmattino.it/rss/home.xml",
        "Il Roma" to "https://www.ilroma.net/sezioni/268/rss",
        "Corriere della Sera" to "https://xml2.corriereobjects.it/rss/homepage.xml",
        "la Repubblica" to "https://www.repubblica.it/rss/homepage/rss2.0.xml",
        "Il Sole 24 Ore" to "https://www.ilsole24ore.com/rss/primapagina.xml"
    )

    suspend fun fetchNewsList(): List<NewsPreview> = withContext(Dispatchers.IO) {
        val allNews = mutableListOf<NewsPreview>()

        for ((source, feedUrl) in rssFeeds) {
            try {
                val doc = Jsoup.connect(feedUrl)
                    .userAgent("Mozilla/5.0")
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

                    allNews.add(
                        NewsPreview(
                            id = link.hashCode().toString(),
                            title = title,
                            publishedDate = formatRssDate(pubDateRaw),
                            imageUrl = imgUrl?.takeIf { it.isNotBlank() },
                            articleUrl = link,
                            sourceName = source
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        allNews
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

            doc.select("script, style, nav, header, footer, aside, .ads, .related, .cookie").remove()

            val articleContainer = doc.selectFirst("article")
                ?: doc.selectFirst("[itemprop=articleBody]")
                ?: doc.selectFirst("main")
                ?: doc.body()

            val elements = articleContainer.select("p, img, h2, h3")
            val seenImages = mutableSetOf<String>()
            if (heroImage != null) seenImages.add(heroImage)

            for (el in elements) {
                when (el.tagName()) {
                    "p", "h2", "h3" -> {
                        val text = el.text().trim()
                        if (text.length > 35) {
                            blocks.add(ArticleBlock.TextParagraph(text))
                        }
                    }
                    "img" -> {
                        val src = el.attr("abs:src").ifEmpty { el.attr("abs:data-src") }
                        if (src.isNotBlank() && src.startsWith("http") && !seenImages.contains(src)) {
                            if (!src.contains("icon") && !src.contains("logo") && !src.contains("pixel")) {
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

    private fun formatRssDate(rawDate: String): String {
        return try {
            val inputFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH)
            val date = inputFormat.parse(rawDate)
            val outputFormat = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.ITALIAN)
            if (date != null) outputFormat.format(date) else rawDate
        } catch (e: Exception) {
            rawDate
        }
    }
}
