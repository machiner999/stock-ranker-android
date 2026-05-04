package com.example.stockranker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.stockranker.data.BacktestSummary
import com.example.stockranker.data.PriceBar
import com.example.stockranker.data.RankingItem
import com.example.stockranker.data.RankingResponse
import com.example.stockranker.data.StockApiClient
import com.example.stockranker.data.StockDetailResponse
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StockRankerApp()
        }
    }
}

class StockRankerViewModel : ViewModel() {
    private val api = StockApiClient()
    var ranking by mutableStateOf<RankingResponse?>(null)
        private set
    var detail by mutableStateOf<StockDetailResponse?>(null)
        private set
    var backtest by mutableStateOf<BacktestSummary?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    api.latestRanking() to api.backtestSummary()
                }
            }.onSuccess { (latestRanking, latestBacktest) ->
                ranking = latestRanking
                backtest = latestBacktest
            }.onFailure { error = it.message }
            loading = false
        }
    }

    fun openTicker(ticker: String) {
        viewModelScope.launch {
            loading = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) { api.stockDetail(ticker) }
            }.onSuccess { detail = it }
                .onFailure { error = it.message }
            loading = false
        }
    }

    fun registerPushToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { api.registerDevice(token) }
        }
    }
}

@Composable
fun StockRankerApp(viewModel: StockRankerViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    var showDisclaimer by remember { mutableStateOf(true) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        viewModel.load()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        runCatching {
            FirebaseMessaging.getInstance().token.await()
        }.onSuccess { token -> viewModel.registerPushToken(token) }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (showDisclaimer) {
                AlertDialog(
                    onDismissRequest = { showDisclaimer = false },
                    title = { Text("免責事項") },
                    text = { Text("このアプリは研究・分析用の確率シグナルを表示します。投資助言や売買推奨ではありません。") },
                    confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text("理解しました") } }
                )
            }
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        listOf(
                            TabItem("ランキング", Icons.Default.BarChart),
                            TabItem("詳細", Icons.Default.Notifications),
                            TabItem("検証", Icons.Default.History),
                            TabItem("設定", Icons.Default.Settings)
                        ).forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) }
                            )
                        }
                    }
                }
            ) { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    if (viewModel.error != null) {
                        ErrorBanner(viewModel.error.orEmpty(), onRetry = viewModel::load)
                    }
                    when (tab) {
                        0 -> RankingScreen(viewModel.ranking, viewModel.loading) {
                            viewModel.openTicker(it)
                            tab = 1
                        }
                        1 -> StockDetailScreen(viewModel.detail)
                        2 -> HistoryScreen(viewModel.backtest)
                        3 -> SettingsScreen(BuildConfig.API_BASE_URL, onRegister = {
                            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                                viewModel.registerPushToken(token)
                            }
                        })
                    }
                }
            }
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector)

@Composable
fun RankingScreen(ranking: RankingResponse?, loading: Boolean, onTickerClick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("今日の上昇候補", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(ranking?.signalDate ?: "未更新", color = Color.Gray)
            if (loading) Text("読み込み中...")
        }
        items(ranking?.items.orEmpty()) { item ->
            RankingCard(item, onTickerClick)
        }
    }
}

@Composable
fun RankingCard(item: RankingItem, onTickerClick: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onTickerClick(item.ticker) },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7FAF8))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(item.ticker, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(item.name, style = MaterialTheme.typography.bodyMedium)
                }
                Column {
                    Text("Score ${item.score.format1()}", fontWeight = FontWeight.Bold)
                    Text("確率 ${(item.probability * 100).format1()}%")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(item.sector) })
                AssistChip(onClick = {}, label = { Text("目標 ${(item.targetReturn * 100).format1()}%") })
            }
            Text("根拠: ${item.reasons.joinToString("、")}")
            Text("注意: ${item.risks.joinToString("、")}", color = Color(0xFF7A4A00))
        }
    }
}

@Composable
fun StockDetailScreen(detail: StockDetailResponse?) {
    if (detail == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("ランキングから銘柄を選択してください")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("${detail.stock.ticker} ${detail.stock.name}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail.stock.sector, color = Color.Gray)
        }
        item { PriceChart(detail.chart) }
        detail.latestSignal?.let { signal ->
            item {
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最新シグナル ${signal.signalDate}", fontWeight = FontWeight.Bold)
                        Text("Score ${signal.score.format1()} / 確率 ${(signal.probability * 100).format1()}%")
                        Text("根拠: ${signal.reasons.joinToString("、")}")
                        Text("注意: ${signal.risks.joinToString("、")}")
                    }
                }
            }
        }
    }
}

@Composable
fun PriceChart(bars: List<PriceBar>) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text("価格チャート", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                if (bars.size < 2) return@Canvas
                val min = bars.minOf { it.close }
                val max = bars.maxOf { it.close }
                val range = (max - min).takeIf { it > 0 } ?: 1.0
                val path = Path()
                bars.forEachIndexed { index, bar ->
                    val x = size.width * index / (bars.lastIndex.coerceAtLeast(1)).toFloat()
                    val y = size.height - ((bar.close - min) / range).toFloat() * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawLine(Color(0xFFE0E0E0), Offset(0f, size.height), Offset(size.width, size.height))
                drawPath(path, Color(0xFF25685A), style = Stroke(width = 4.dp.toPx()))
            }
        }
    }
}

@Composable
fun HistoryScreen(summary: BacktestSummary?) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("過去シグナル検証", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (summary == null) {
            Text("まだ検証データがありません")
        } else {
            Metric("評価シグナル", summary.evaluatedSignals.toString())
            Metric("命中率", "${(summary.hitRate * 100).format1()}%")
            Metric("平均リターン", "${(summary.averageReturn * 100).format1()}%")
            Metric("最大下落", "${(summary.maxDrawdown * 100).format1()}%")
            Text(summary.note)
        }
    }
}

@Composable
fun SettingsScreen(apiBaseUrl: String, onRegister: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("設定", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Metric("API", apiBaseUrl)
        Button(onClick = onRegister) {
            Icon(Icons.Default.Notifications, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("通知トークンを登録")
        }
        Text("このアプリは研究・分析用の確率シグナルを表示します。投資助言や売買推奨ではありません。")
    }
}

@Composable
fun Metric(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ErrorBanner(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2F0))) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(message, modifier = Modifier.weight(1f), color = Color(0xFF8A1F11))
            TextButton(onClick = onRetry) { Text("再試行") }
        }
    }
}

private fun Double.format1(): String = String.format("%.1f", this)
