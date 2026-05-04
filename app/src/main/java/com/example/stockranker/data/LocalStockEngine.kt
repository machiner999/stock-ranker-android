package com.example.stockranker.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class LocalStockEngine(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val cache = mutableMapOf<String, List<PriceBar>>()
    private val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val disclaimer = "この情報は研究・分析用の確率シグナルであり、投資助言や売買推奨ではありません。"

    fun latestRanking(): RankingResponse {
        val spyBars = barsFor("SPY")
        val items = universe
            .filterNot { it.ticker == "SPY" }
            .mapNotNull { stock ->
                runCatching {
                    val signal = calculateSignal(stock.ticker, barsFor(stock.ticker), spyBars)
                    RankingItem(
                        ticker = stock.ticker,
                        name = stock.name,
                        sector = stock.sector,
                        score = signal.score,
                        probability = signal.probability,
                        targetReturn = signal.targetReturn,
                        closePrice = signal.closePrice,
                        reasons = signal.reasons,
                        risks = signal.risks
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.score }
            .take(30)
        return RankingResponse(items.firstOrNull()?.let { barsFor(it.ticker).lastOrNull()?.tradeDate }, disclaimer, items)
    }

    fun stockDetail(ticker: String): StockDetailResponse {
        val stock = universe.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }
            ?: Stock(ticker.uppercase(), ticker.uppercase(), "Unknown")
        val bars = barsFor(stock.ticker)
        val spyBars = barsFor("SPY")
        val signal = runCatching { calculateSignal(stock.ticker, bars, spyBars) }.getOrNull()
        return StockDetailResponse(stock, signal, bars.takeLast(120))
    }

    fun backtestSummary(): BacktestSummary = BacktestSummary(
        evaluatedSignals = 0,
        hitRate = 0.0,
        averageReturn = 0.0,
        maxDrawdown = 0.0,
        note = "スマホ単体版では最新シグナルを端末内で生成します。過去検証は履歴保存を追加すると有効になります。"
    )

    fun clearCache() {
        cache.clear()
    }

    private fun barsFor(ticker: String): List<PriceBar> = cache.getOrPut(ticker.uppercase()) {
        fetchBars(ticker.uppercase())
    }

    private fun fetchBars(ticker: String): List<PriceBar> {
        val end = LocalDate.now()
        val start = end.minusDays(460)
        val stooqTicker = ticker.replace(".", "-").lowercase() + ".us"
        val url = "https://stooq.com/q/d/l/?s=$stooqTicker&i=d&d1=${dateFormatter.format(start)}&d2=${dateFormatter.format(end)}"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Stooq request failed for $ticker: ${response.code}")
            val csv = response.body?.string().orEmpty()
            return csv.lineSequence()
                .drop(1)
                .filter { it.isNotBlank() && !it.contains("No data", ignoreCase = true) }
                .mapNotNull { line ->
                    val parts = line.split(",")
                    if (parts.size < 6) return@mapNotNull null
                    PriceBar(
                        ticker = ticker,
                        tradeDate = parts[0],
                        open = parts[1].toDouble(),
                        high = parts[2].toDouble(),
                        low = parts[3].toDouble(),
                        close = parts[4].toDouble(),
                        volume = parts[5].toLong()
                    )
                }
                .toList()
        }
    }

    private fun calculateSignal(ticker: String, bars: List<PriceBar>, spyBars: List<PriceBar>): Signal {
        require(bars.size >= 220) { "At least 220 bars are required for $ticker" }
        val close = bars.last().close
        val momentum1m = returnSince(bars, 21)
        val momentum3m = returnSince(bars, 63)
        val momentum6m = returnSince(bars, 126)
        val sma20 = sma(bars, 20)
        val sma50 = sma(bars, 50)
        val sma200 = sma(bars, 200)
        val rsi14 = rsi(bars, 14)
        val volumeRatio = averageVolume(bars, 20) / max(1.0, averageVolume(bars, 60))
        val highDistance = close / highestClose(bars, 126) - 1.0
        val volatility = annualizedVolatility(bars, 63)
        val relativeStrength = if (spyBars.size >= 126) momentum3m - returnSince(spyBars, 63) else momentum3m

        var score = 50.0
        score += clamp(momentum1m * 160.0, -12.0, 14.0)
        score += clamp(momentum3m * 120.0, -14.0, 18.0)
        score += clamp(momentum6m * 65.0, -10.0, 12.0)
        score += if (close > sma20) 5.0 else -5.0
        score += if (close > sma50) 7.0 else -7.0
        score += if (close > sma200) 8.0 else -8.0
        score += clamp((volumeRatio - 1.0) * 12.0, -5.0, 8.0)
        score += if (rsi14 in 45.0..68.0) 7.0 else if (rsi14 > 78) -9.0 else -4.0
        score += clamp(relativeStrength * 100.0, -10.0, 10.0)
        score += if (highDistance > -0.08) 5.0 else -3.0
        score -= clamp((volatility - 0.32) * 30.0, 0.0, 10.0)
        score = clamp(score, 0.0, 100.0)

        return Signal(
            ticker = ticker,
            signalDate = bars.last().tradeDate,
            score = round(score),
            probability = round(clamp(0.18 + (score / 100.0) * 0.52, 0.05, 0.82)),
            targetReturn = round(clamp(0.06 + (score / 100.0) * 0.16, 0.02, 0.26)),
            reasons = reasons(momentum3m, close, sma50, rsi14, relativeStrength),
            risks = risks(volatility, rsi14, highDistance),
            closePrice = close
        )
    }

    private fun reasons(momentum3m: Double, close: Double, sma50: Double, rsi14: Double, relativeStrength: Double): List<String> {
        val values = mutableListOf<String>()
        if (momentum3m > 0.08) values += "3ヶ月モメンタムが強い"
        if (close > sma50) values += "50日移動平均を上回る"
        if (rsi14 in 45.0..68.0) values += "RSIが過熱しすぎていない"
        if (relativeStrength > 0) values += "SPYより相対的に強い"
        return values.ifEmpty { listOf("複数指標の総合スコアが相対的に高い") }
    }

    private fun risks(volatility: Double, rsi14: Double, highDistance: Double): List<String> {
        val values = mutableListOf<String>()
        if (volatility > 0.45) values += "価格変動が大きい"
        if (rsi14 > 72) values += "短期的な過熱感がある"
        if (highDistance < -0.18) values += "直近高値から大きく下落中"
        return values.ifEmpty { listOf("市場全体の急落や決算イベントに注意") }
    }

    private fun returnSince(bars: List<PriceBar>, days: Int): Double =
        bars.last().close / bars[max(0, bars.lastIndex - days)].close - 1.0

    private fun sma(bars: List<PriceBar>, days: Int): Double =
        bars.takeLast(days).map { it.close }.average()

    private fun averageVolume(bars: List<PriceBar>, days: Int): Double =
        bars.takeLast(days).map { it.volume }.average()

    private fun highestClose(bars: List<PriceBar>, days: Int): Double =
        bars.takeLast(days).maxOf { it.close }

    private fun rsi(bars: List<PriceBar>, days: Int): Double {
        var gains = 0.0
        var losses = 0.0
        for (i in bars.size - days until bars.size) {
            val diff = bars[i].close - bars[i - 1].close
            if (diff >= 0) gains += diff else losses -= diff
        }
        if (losses == 0.0) return 100.0
        val rs = gains / losses
        return 100.0 - (100.0 / (1.0 + rs))
    }

    private fun annualizedVolatility(bars: List<PriceBar>, days: Int): Double {
        val returns = (max(1, bars.size - days) until bars.size).map { i ->
            ln(bars[i].close / bars[i - 1].close)
        }
        val mean = returns.average()
        val variance = returns.map { (it - mean).pow(2) }.average()
        return sqrt(variance) * sqrt(252.0)
    }

    private fun clamp(value: Double, min: Double, max: Double): Double = max(min, min(max, value))
    private fun round(value: Double): Double = kotlin.math.round(value * 1000.0) / 1000.0

    private val universe = listOf(
        Stock("AAPL", "Apple", "Information Technology"),
        Stock("MSFT", "Microsoft", "Information Technology"),
        Stock("NVDA", "NVIDIA", "Information Technology"),
        Stock("AMZN", "Amazon", "Consumer Discretionary"),
        Stock("META", "Meta Platforms", "Communication Services"),
        Stock("GOOGL", "Alphabet Class A", "Communication Services"),
        Stock("GOOG", "Alphabet Class C", "Communication Services"),
        Stock("LLY", "Eli Lilly", "Health Care"),
        Stock("AVGO", "Broadcom", "Information Technology"),
        Stock("JPM", "JPMorgan Chase", "Financials"),
        Stock("TSLA", "Tesla", "Consumer Discretionary"),
        Stock("V", "Visa", "Financials"),
        Stock("XOM", "Exxon Mobil", "Energy"),
        Stock("UNH", "UnitedHealth Group", "Health Care"),
        Stock("MA", "Mastercard", "Financials"),
        Stock("COST", "Costco", "Consumer Staples"),
        Stock("PG", "Procter & Gamble", "Consumer Staples"),
        Stock("HD", "Home Depot", "Consumer Discretionary"),
        Stock("NFLX", "Netflix", "Communication Services"),
        Stock("JNJ", "Johnson & Johnson", "Health Care"),
        Stock("ABBV", "AbbVie", "Health Care"),
        Stock("CRM", "Salesforce", "Information Technology"),
        Stock("AMD", "Advanced Micro Devices", "Information Technology"),
        Stock("KO", "Coca-Cola", "Consumer Staples"),
        Stock("PEP", "PepsiCo", "Consumer Staples"),
        Stock("BAC", "Bank of America", "Financials"),
        Stock("WMT", "Walmart", "Consumer Staples"),
        Stock("DIS", "Walt Disney", "Communication Services"),
        Stock("ADBE", "Adobe", "Information Technology"),
        Stock("CSCO", "Cisco Systems", "Information Technology"),
        Stock("ORCL", "Oracle", "Information Technology"),
        Stock("MCD", "McDonald's", "Consumer Discretionary"),
        Stock("INTC", "Intel", "Information Technology"),
        Stock("QCOM", "Qualcomm", "Information Technology"),
        Stock("SPY", "SPDR S&P 500 ETF", "Benchmark")
    )
}
