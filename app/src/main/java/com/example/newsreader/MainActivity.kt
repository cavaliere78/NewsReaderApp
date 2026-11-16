package com.example.newsreader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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

                var newsList by remember { mutableStateOf<List<NewsPreview>>(emptyList()) }
                var isLoading by remember { mutableStateOf(true) }
                var isRefreshing by remember { mutableStateOf(false) }
                var selectedSource by remember { mutableStateOf("Tutte") }
                val coroutineScope = rememberCoroutineScope()
                val feedListState = rememberLazyListState()

                val loadNews: (Boolean) -> Unit = { refreshing ->
                    if (refreshing) isRefreshing = true else isLoading = true
                    coroutineScope.launch {
                        newsList = repository.fetchNewsList()
                        isLoading = false
                        isRefreshing = false
                        if (refreshing) {
                            feedListState.scrollToItem(0)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    loadNews(false)
                }

                when (val screen = currentScreen) {
                    is Screen.Feed -> {
                        NewsFeedScreen(
                            newsList = newsList,
                            isLoading = isLoading,
                            isRefreshing = isRefreshing,
                            selectedSource = selectedSource,
                            listState = feedListState,
                            onSelectSource = { newSource ->
                                selectedSource = newSource
                                coroutineScope.launch { feedListState.scrollToItem(0) }
                            },
                            onRefresh = { loadNews(true) },
                            onNewsClick = { news -> currentScreen = Screen.Reader(news) },
                            onOpenSettings = { currentScreen = Screen.Settings }
                        )
                    }
                    is Screen.Reader -> {
                        BackHandler { currentScreen = Screen.Feed }
                        ArticleReaderScreen(
                            preview = screen.news,
                            repository = repository,
                            onBack = { currentScreen = Screen.Feed }
                        )
                    }
                    is Screen.Settings -> {
                        BackHandler {
                            currentScreen = Screen.Feed
                            loadNews(true)
                        }
                        ManageSourcesScreen(
                            repository = repository,
                            onBack = {
                                currentScreen = Screen.Feed
                                loadNews(true)
                            }
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
    newsList: List<NewsPreview>,
    isLoading: Boolean,
    isRefreshing: Boolean,
    selectedSource: String,
    listState: LazyListState,
    onSelectSource: (String) -> Unit,
    onRefresh: () -> Unit,
    onNewsClick: (NewsPreview) -> Unit,
    onOpenSettings: () -> Unit
) {
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
                        onClick = { onSelectSource(source) },
                        label = { Text(source) }
                    )
                }
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(filteredList, key = { it.id }) { news ->
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
    val context = LocalContext.current
    var feeds by remember { mutableStateOf(repository.getSavedFeeds().toList()) }
    var showDialog by remember { mutableStateOf(false) }

    var editingOriginalName by remember { mutableStateOf<String?>(null) }
    var inputName by remember { mutableStateOf("") }
    var inputUrl by remember { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            try {
                val jsonContent = repository.exportFeedsToJson()
                context.contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(jsonContent.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Lista esportata con successo!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Errore esportazione: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val jsonContent = context.contentResolver.openInputStream(it)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    reader.readText()
                }
                if (!jsonContent.isNullOrBlank()) {
                    feeds = repository.importFeedsFromJson(jsonContent).toList()
                    Toast.makeText(context, "Lista importata (${feeds.size} fonti)!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "File non valido o errore lettura: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestisci Fonti RSS") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro")
                    }
                },
                actions = {
                    IconButton(onClick = { exportLauncher.launch("fonti_rss_backup.json") }) {
                        Icon(Icons.Default.Share, contentDescription = "Esporta su file")
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Icon(Icons.Default.AddCircle, contentDescription = "Importa da file")
                    }
                    IconButton(onClick = {
                        feeds = repository.resetDefaultFeeds().toList()
                        Toast.makeText(context, "Fonti predefinite ripristinate", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Ripristina Predefinite")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editingOriginalName = null
                inputName = ""
                inputUrl = ""
                showDialog = true
            }) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            editingOriginalName = name
                            inputName = name
                            inputUrl = url
                            showDialog = true
                        },
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

                            context.getSharedPreferences("news_prefs", Context.MODE_PRIVATE)
                                .edit().putBoolean("deleted_$name", true).apply()

                            feeds = mutableMap.toList()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Elimina", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }

        if (showDialog) {
            val isEditing = editingOriginalName != null
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text(if (isEditing) "Modifica testata" else "Aggiungi nuova testata") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = inputName,
                            onValueChange = { inputName = it },
                            label = { Text("Nome Testata") }
                        )
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            label = { Text("URL Feed RSS") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (inputName.isNotBlank() && inputUrl.isNotBlank()) {
                            val mutableMap = feeds.toMap().toMutableMap()

                            if (isEditing && editingOriginalName != null && editingOriginalName != inputName.trim()) {
                                mutableMap.remove(editingOriginalName)
                            }

                            mutableMap[inputName.trim()] = inputUrl.trim()
                            repository.saveFeeds(mutableMap)

                            context.getSharedPreferences("news_prefs", Context.MODE_PRIVATE)
                                .edit().remove("deleted_${inputName.trim()}").apply()

                            feeds = mutableMap.toList()
                            showDialog = false
                        }
                    }) {
                        Text(if (isEditing) "Salva" else "Aggiungi")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDialog = false }) {
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