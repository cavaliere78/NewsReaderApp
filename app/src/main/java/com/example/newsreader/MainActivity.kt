package com.example.newsreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = NewsRepository(this)

        setContent {
            MaterialTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Feed) }

                when (val screen = currentScreen) {
                    is Screen.Feed -> {
                        NewsFeedScreen(
                            repository = repository,
                            onNewsClick = { news -> currentScreen = Screen.Reader(news) },
                            onOpenSettings = { currentScreen = Screen.Settings }
                        )
                    }
                    is Screen.Reader -> {
                        ArticleReaderScreen(
                            preview = screen.news,
                            repository = repository,
                            onBack = { currentScreen = Screen.Feed }
                        )
                    }
                    is Screen.Settings -> {
                        ManageSourcesScreen(
                            repository = repository,
                            onBack = { currentScreen = Screen.Feed }
                        )
                    }
                }
            }
        }
    }
}

sealed class Screen {
    object Feed : Screen()
    data class Reader(val news: NewsPreview) : Screen()
    object Settings : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsFeedScreen(
    repository: NewsRepository,
    onNewsClick: (NewsPreview) -> Unit,
    onOpenSettings: () -> Unit
) {
    var newsList by remember { mutableStateOf<List<NewsPreview>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedSource by remember { mutableStateOf("Tutte") }
    
    val coroutineScope = rememberCoroutineScope()

    val loadNews: (Boolean) -> Unit = { refreshing ->
        if (refreshing) isRefreshing = true else isLoading = true
        coroutineScope.launch {
            newsList = repository.fetchNewsList()
            isLoading = false
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        loadNews(false)
    }

    val availableSources = remember(newsList) {
        listOf("Tutte") + newsList.map { it.sourceName }.distinct()
    }

    val filteredList = if (selectedSource == "Tutte") {
        newsList
    } else {
        newsList.filter { it.sourceName == selectedSource }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ultime Notizie", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Gestisci Fonti")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableSources.forEach { source ->
                    FilterChip(
                        selected = selectedSource == source,
                        onClick = { selectedSource = source },
                        label = { Text(source) }
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { loadNews(true) },
                modifier = Modifier.fillMaxSize()
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(filteredList) { news ->
                            NewsCard(news = news, onClick = { onNewsClick(news) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageSourcesScreen(
    repository: NewsRepository,
    onBack: () -> Unit
) {
    var feeds by remember { mutableStateOf(repository.getSavedFeeds().toList()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestisci Fonti RSS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Aggiungi Fonte")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(feeds) { (name, url) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(text = url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            val mutableMap = feeds.toMap().toMutableMap()
                            mutableMap.remove(name)
                            repository.saveFeeds(mutableMap)
                            feeds = mutableMap.toList()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Aggiungi nuova testata") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Nome Testata (es. Ansa)") }
                        )
                        OutlinedTextField(
                            value = newUrl,
                            onValueChange = { newUrl = it },
                            label = { Text("URL Feed RSS") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank() && newUrl.isNotBlank()) {
                            val mutableMap = feeds.toMap().toMutableMap()
                            mutableMap[newName.trim()] = newUrl.trim()
                            repository.saveFeeds(mutableMap)
                            feeds = mutableMap.toList()
                            newName = ""
                            newUrl = ""
                            showAddDialog = false
                        }
                    }) {
                        Text("Aggiungi")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Annulla")
                    }
                }
            )
        }
    }
}

@Composable
fun NewsCard(news: NewsPreview, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column {
            if (!news.imageUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = news.imageUrl,
                    contentDescription = news.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${news.sourceName} • ${news.publishedDate}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = news.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleReaderScreen(
    preview: NewsPreview,
    repository: NewsRepository,
    onBack: () -> Unit
) {
    var article by remember { mutableStateOf<FullArticle?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    val context = LocalContext.current

    LaunchedEffect(preview.articleUrl) {
        article = repository.fetchFullArticle(preview)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(preview.sourceName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            article?.let { fullArticle ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = fullArticle.publishedDate,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    item {
                        Text(
                            text = fullArticle.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (!fullArticle.mainImageUrl.isNullOrEmpty()) {
                        item {
                            AsyncImage(
                                model = fullArticle.mainImageUrl,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }

                    items(fullArticle.blocks) { block ->
                        when (block) {
                            is ArticleBlock.TextParagraph -> {
                                Text(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    lineHeight = 26.sp
                                )
                            }
                            is ArticleBlock.ImageBlock -> {
                                Column {
                                    AsyncImage(
                                        model = block.imageUrl,
                                        contentDescription = block.caption,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.FillWidth
                                    )
                                    if (!block.caption.isNullOrBlank()) {
                                        Text(
                                            text = block.caption,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        Column(modifier = Modifier.padding(bottom = 24.dp)) {
                            Text(
                                text = "Fonte originale:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = fullArticle.sourceUrl,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline
                                ),
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullArticle.sourceUrl))
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}