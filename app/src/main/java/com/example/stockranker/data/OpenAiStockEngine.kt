package com.example.stockranker.data

import com.example.stockranker.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class OpenAiStockEngine(
    private val client: OkHttpClient = OkHttpClient()
) {
    private val cache = mutableMapOf<String, List<PriceBar>>()
    private val dateFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val jsonMediaType = "application/json".toMediaType()
    private val disclaimer = "この情報は研究・分析用の確率シグナルであり、投資助言や売買推奨ではありません。"
    private val maxOpenAiAttempts = 4

    fun latestRanking(forceRefresh: Boolean = false): RankingResponse {
        if (forceRefresh) clearCache()
        val spyBars = barsFor("SPY")
        val summaries = universe
            .filterNot { it.ticker == "SPY" }
            .mapNotNull { stock -> runCatching { indicatorSummary(stock, barsFor(stock.ticker), spyBars) }.getOrNull() }
        val aiItems = validateAiItems(requestOpenAiRanking(summaries), summaries).take(30)
        if (aiItems.isEmpty()) error("OpenAI response had no valid ranking items")
        return RankingResponse(
            signalDate = summaries.firstOrNull()?.signalDate,
            disclaimer = disclaimer,
            items = aiItems.map { item ->
                val summary = summaries.first { it.ticker == item.ticker }
                RankingItem(
                    ticker = summary.ticker,
                    name = summary.name,
                    sector = summary.sector,
                    score = item.score,
                    probability = item.probability,
                    targetReturn = item.targetReturn,
                    closePrice = summary.closePrice,
                    reasons = item.reasons,
                    risks = item.risks
                )
            }
        )
    }

    fun stockDetail(ticker: String, forceRefresh: Boolean = false): StockDetailResponse {
        if (forceRefresh) clearCache()
        val stock = universe.firstOrNull { it.ticker.equals(ticker, ignoreCase = true) }
            ?: Stock(ticker.uppercase(), ticker.uppercase(), "Unknown")
        val bars = barsFor(stock.ticker)
        val summary = indicatorSummary(stock, bars, barsFor("SPY"))
        val aiItem = validateAiItems(requestOpenAiRanking(listOf(summary)), listOf(summary)).firstOrNull()
            ?: error("OpenAI response had no valid signal for ${stock.ticker}")
        return StockDetailResponse(
            stock = stock,
            latestSignal = Signal(
                ticker = summary.ticker,
                signalDate = summary.signalDate,
                score = aiItem.score,
                probability = aiItem.probability,
                targetReturn = aiItem.targetReturn,
                reasons = aiItem.reasons,
                risks = aiItem.risks,
                closePrice = summary.closePrice
            ),
            chart = bars.takeLast(120)
        )
    }

    fun backtestSummary(): BacktestSummary = BacktestSummary(
        evaluatedSignals = 0,
        hitRate = 0.0,
        averageReturn = 0.0,
        maxDrawdown = 0.0,
        note = "Androidアプリ内でStooqデータを取得し、Responses APIによる最新シグナルを生成します。過去検証は履歴保存を追加すると有効になります。"
    )

    fun clearCache() {
        cache.clear()
    }

    private fun requestOpenAiRanking(summaries: List<IndicatorSummary>): List<AiRankingItem> {
        val apiKey = BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
            ?: error("OPENAI_API_KEYがアプリに設定されていません。-PopenAiApiKey または環境変数 OPENAI_API_KEY を指定してAPKをビルドしてください。")
        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(createOpenAiRequest(summaries).toString().toRequestBody(jsonMediaType))
            .build()
        var lastError = "OpenAI Responses API failed"
        for (attempt in 1..maxOpenAiAttempts) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) return parseOpenAiRanking(body)

                lastError = openAiErrorMessage(response.code, response.headers, body)
                val retryDelayMillis = retryDelayMillis(response.code, response.header("Retry-After"), response.headers["x-ratelimit-reset-requests"], attempt)
                if (retryDelayMillis == null || attempt == maxOpenAiAttempts) {
                    error(lastError)
                }
                Thread.sleep(retryDelayMillis)
            }
        }
        error(lastError)
    }

    private fun createOpenAiRequest(summaries: List<IndicatorSummary>): JSONObject = JSONObject()
        .put("model", BuildConfig.OPENAI_MODEL)
        .put(
            "instructions",
            """
            You evaluate US stock indicator summaries for a Japanese research app.
            This is research and analysis only, not investment advice or a buy/sell recommendation.
            Use only the supplied tickers. Do not add tickers.
            Return concise Japanese reasons and risks.
            Keep score in 0..100, probability in 0.05..0.82, and targetReturn in 0.02..0.26.
            """.trimIndent()
        )
        .put(
            "input",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "input_text")
                                .put("text", "Rank these indicator summaries. JSON data:\n${summaries.toJsonArray()}")
                        )
                    )
            )
        )
        .put(
            "text",
            JSONObject().put(
                "format",
                JSONObject()
                    .put("type", "json_schema")
                    .put("name", "stock_ranking")
                    .put("strict", true)
                    .put("schema", rankingSchema())
            )
        )
        .put("reasoning", JSONObject().put("effort", "none"))

    private fun retryDelayMillis(
        statusCode: Int,
        retryAfter: String?,
        resetRequests: String?,
        attempt: Int
    ): Long? {
        if (statusCode != 429 && statusCode != 500 && statusCode != 503) return null
        val serverDelay = retryAfter?.let(::parseRetryAfterMillis)
            ?: resetRequests?.let(::parseOpenAiResetMillis)
        val fallbackDelay = 1_000L * (1 shl (attempt - 1))
        return (serverDelay ?: fallbackDelay).coerceIn(1_000L, 20_000L)
    }

    private fun parseRetryAfterMillis(value: String): Long? {
        value.toLongOrNull()?.let { return it * 1_000L }
        return try {
            val retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            max(0L, retryAt.toEpochMilli() - System.currentTimeMillis())
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseOpenAiResetMillis(value: String): Long? {
        val pattern = Regex("""(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?""")
        val match = pattern.matchEntire(value.trim()) ?: return null
        val minutes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
        val seconds = match.groupValues.getOrNull(2)?.toLongOrNull() ?: 0L
        val millis = match.groupValues.getOrNull(3)?.toLongOrNull() ?: 0L
        val total = minutes * 60_000L + seconds * 1_000L + millis
        return total.takeIf { it > 0L }
    }

    private fun openAiErrorMessage(statusCode: Int, headers: okhttp3.Headers, body: String): String {
        val errorObject = runCatching {
            JSONObject(body).optJSONObject("error")
        }.getOrNull()
        val apiMessage = errorObject?.optString("message").orEmpty()
        val apiType = errorObject?.optString("type").orEmpty()
        val apiCode = errorObject?.optString("code").orEmpty()
        val requestId = headers["x-request-id"].orEmpty()
        val retryAfter = headers["Retry-After"].orEmpty()
        val rateLimitDetails = listOf(
            "x-ratelimit-limit-requests",
            "x-ratelimit-remaining-requests",
            "x-ratelimit-reset-requests",
            "x-ratelimit-limit-tokens",
            "x-ratelimit-remaining-tokens",
            "x-ratelimit-reset-tokens"
        ).mapNotNull { headerName ->
            headers[headerName]?.takeIf { it.isNotBlank() }?.let { "$headerName=$it" }
        }
        val bodyPreview = body.take(800).ifBlank { "(empty)" }

        if (statusCode == 429) {
            val quotaHint = if (
                apiMessage.contains("quota", ignoreCase = true) ||
                apiMessage.contains("billing", ignoreCase = true) ||
                apiMessage.contains("insufficient_quota", ignoreCase = true)
            ) {
                " APIキーの請求設定・利用上限・残高を確認してください。"
            } else {
                " 少し待ってから再試行してください。"
            }
            return buildString {
                appendLine("OpenAI Responses API rate limit/quota error: HTTP 429.$quotaHint")
                appendOpenAiErrorDetails(statusCode, apiMessage, apiType, apiCode, requestId, retryAfter, rateLimitDetails, bodyPreview)
            }.trim()
        }

        return buildString {
            appendLine("OpenAI Responses API failed: HTTP $statusCode.")
            appendOpenAiErrorDetails(statusCode, apiMessage, apiType, apiCode, requestId, retryAfter, rateLimitDetails, bodyPreview)
        }.trim()
    }

    private fun StringBuilder.appendOpenAiErrorDetails(
        statusCode: Int,
        apiMessage: String,
        apiType: String,
        apiCode: String,
        requestId: String,
        retryAfter: String,
        rateLimitDetails: List<String>,
        bodyPreview: String
    ) {
        appendLine("status=$statusCode")
        appendLine("message=${apiMessage.ifBlank { "(none)" }}")
        appendLine("type=${apiType.ifBlank { "(none)" }}")
        appendLine("code=${apiCode.ifBlank { "(none)" }}")
        appendLine("request_id=${requestId.ifBlank { "(none)" }}")
        appendLine("retry_after=${retryAfter.ifBlank { "(none)" }}")
        appendLine("rate_limits=${rateLimitDetails.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        append("body=$bodyPreview")
    }

    private fun rankingSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", false)
        .put("required", JSONArray().put("items"))
        .put(
            "properties",
            JSONObject().put(
                "items",
                JSONObject()
                    .put("type", "array")
                    .put("minItems", 1)
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "object")
                            .put("additionalProperties", false)
                            .put("required", JSONArray().put("ticker").put("score").put("probability").put("targetReturn").put("reasons").put("risks"))
                            .put(
                                "properties",
                                JSONObject()
                                    .put("ticker", JSONObject().put("type", "string"))
                                    .put("score", JSONObject().put("type", "number").put("minimum", 0).put("maximum", 100))
                                    .put("probability", JSONObject().put("type", "number").put("minimum", 0.05).put("maximum", 0.82))
                                    .put("targetReturn", JSONObject().put("type", "number").put("minimum", 0.02).put("maximum", 0.26))
                                    .put("reasons", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                                    .put("risks", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                            )
                    )
            )
        )

    private fun parseOpenAiRanking(body: String): List<AiRankingItem> {
        val root = JSONObject(body)
        val output = root.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val content = output.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.getJSONObject(j)
                if (item.optString("refusal").isNotBlank()) error("OpenAI refused the ranking request")
                if (item.optString("type") == "output_text") {
                    return parseAiItems(JSONObject(item.getString("text")).getJSONArray("items"))
                }
            }
        }
        root.optString("output_text").takeIf { it.isNotBlank() }?.let {
            return parseAiItems(JSONObject(it).getJSONArray("items"))
        }
        error("OpenAI response did not include output_text")
    }

    private fun parseAiItems(items: JSONArray): List<AiRankingItem> = (0 until items.length()).map { index ->
        val item = items.getJSONObject(index)
        AiRankingItem(
            ticker = item.getString("ticker"),
            score = item.getDouble("score"),
            probability = item.getDouble("probability"),
            targetReturn = item.getDouble("targetReturn"),
            reasons = item.getJSONArray("reasons").toStringList(),
            risks = item.getJSONArray("risks").toStringList()
        )
    }

    private fun validateAiItems(aiItems: List<AiRankingItem>, summaries: List<IndicatorSummary>): List<AiRankingItem> {
        val allowedTickers = summaries.map { it.ticker }.toSet()
        val seen = mutableSetOf<String>()
        return aiItems
            .asSequence()
            .map { it.copy(ticker = it.ticker.uppercase()) }
            .filter { it.ticker in allowedTickers && seen.add(it.ticker) }
            .map {
                it.copy(
                    score = round(clamp(it.score, 0.0, 100.0)),
                    probability = round(clamp(it.probability, 0.05, 0.82)),
                    targetReturn = round(clamp(it.targetReturn, 0.02, 0.26)),
                    reasons = cleanTextList(it.reasons, "複数指標の総合評価が高い"),
                    risks = cleanTextList(it.risks, "市場全体の急落や決算イベントに注意")
                )
            }
            .sortedByDescending { it.score }
            .toList()
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
            return response.body?.string().orEmpty()
                .lineSequence()
                .drop(1)
                .filter { it.isNotBlank() && !it.contains("No data", ignoreCase = true) }
                .mapNotNull { line -> parsePriceBar(ticker, line) }
                .toList()
        }
    }

    private fun parsePriceBar(ticker: String, line: String): PriceBar? {
        val parts = line.split(",")
        if (parts.size < 6) return null
        return PriceBar(
            ticker = ticker,
            tradeDate = parts[0],
            open = parts[1].toDouble(),
            high = parts[2].toDouble(),
            low = parts[3].toDouble(),
            close = parts[4].toDouble(),
            volume = parts[5].toLong()
        )
    }

    private fun indicatorSummary(stock: Stock, bars: List<PriceBar>, spyBars: List<PriceBar>): IndicatorSummary {
        require(bars.size >= 220) { "At least 220 bars are required for ${stock.ticker}" }
        val close = bars.last().close
        val momentum3m = returnSince(bars, 63)
        return IndicatorSummary(
            ticker = stock.ticker,
            name = stock.name,
            sector = stock.sector,
            signalDate = bars.last().tradeDate,
            closePrice = close,
            momentum1m = round(returnSince(bars, 21)),
            momentum3m = round(momentum3m),
            momentum6m = round(returnSince(bars, 126)),
            sma20 = round(sma(bars, 20)),
            sma50 = round(sma(bars, 50)),
            sma200 = round(sma(bars, 200)),
            rsi14 = round(rsi(bars, 14)),
            volumeRatio = round(averageVolume(bars, 20) / max(1.0, averageVolume(bars, 60))),
            highDistance = round(close / highestClose(bars, 126) - 1.0),
            volatility = round(annualizedVolatility(bars, 63)),
            relativeStrength = round(if (spyBars.size >= 126) momentum3m - returnSince(spyBars, 63) else momentum3m)
        )
    }

    private fun List<IndicatorSummary>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach { summary ->
            array.put(
                JSONObject()
                    .put("ticker", summary.ticker)
                    .put("name", summary.name)
                    .put("sector", summary.sector)
                    .put("signalDate", summary.signalDate)
                    .put("closePrice", summary.closePrice)
                    .put("momentum1m", summary.momentum1m)
                    .put("momentum3m", summary.momentum3m)
                    .put("momentum6m", summary.momentum6m)
                    .put("sma20", summary.sma20)
                    .put("sma50", summary.sma50)
                    .put("sma200", summary.sma200)
                    .put("rsi14", summary.rsi14)
                    .put("volumeRatio", summary.volumeRatio)
                    .put("highDistance", summary.highDistance)
                    .put("volatility", summary.volatility)
                    .put("relativeStrength", summary.relativeStrength)
            )
        }
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun cleanTextList(values: List<String>, fallback: String): List<String> =
        values.map { it.trim() }.filter { it.isNotBlank() }.take(4).ifEmpty { listOf(fallback) }

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

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double =
        max(minValue, min(maxValue, value))

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

private data class IndicatorSummary(
    val ticker: String,
    val name: String,
    val sector: String,
    val signalDate: String,
    val closePrice: Double,
    val momentum1m: Double,
    val momentum3m: Double,
    val momentum6m: Double,
    val sma20: Double,
    val sma50: Double,
    val sma200: Double,
    val rsi14: Double,
    val volumeRatio: Double,
    val highDistance: Double,
    val volatility: Double,
    val relativeStrength: Double
)

private data class AiRankingItem(
    val ticker: String,
    val score: Double,
    val probability: Double,
    val targetReturn: Double,
    val reasons: List<String>,
    val risks: List<String>
)
