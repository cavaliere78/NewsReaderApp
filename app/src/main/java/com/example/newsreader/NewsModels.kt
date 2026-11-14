package com.example.newsreader

data class NewsPreview(
    val id: String,
    val title: String,
    val publishedDate: String,
    val imageUrl: String?,
    val articleUrl: String,
    val sourceName: String
)

sealed class ArticleBlock {
    data class TextParagraph(val text: String) : ArticleBlock()
    data class ImageBlock(val imageUrl: String, val caption: String? = null) : ArticleBlock()
}

data class FullArticle(
    val title: String,
    val publishedDate: String,
    val mainImageUrl: String?,
    val blocks: List<ArticleBlock>,
    val sourceUrl: String
)
