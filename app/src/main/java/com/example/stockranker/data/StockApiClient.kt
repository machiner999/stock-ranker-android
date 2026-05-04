package com.example.stockranker.data

import com.example.stockranker.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class StockApiClient(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    private val client: OkHttpClient = OkHttpClient()
) {
    fun latestRanking(): RankingResponse {
        val json = getJson("/api/rankings/latest")
        return RankingResponse(
            signalDate = json.optString("signalDate").ifBlank { null },
            disclaimer = json.optString("disclaimer"),
            items = json.getJSONArray("items").mapObjects { item ->
                RankingItem(
                    ticker = item.getString("ticker"),
                    name = item.getString("name"),
                    sector = item.getString("sector"),
                    score = item.getDouble("score"),
                    probability = item.getDouble("probability"),
                    targetReturn = item.getDouble("targetReturn"),
                    closePrice = item.getDouble("closePrice"),
                    reasons = item.getJSONArray("reasons").toStringList(),
                    risks = item.getJSONArray("risks").toStringList()
                )
            }
        )
    }

    fun stockDetail(ticker: String): StockDetailResponse {
        val json = getJson("/api/stocks/$ticker")
        val stock = json.getJSONObject("stock")
        val signal = json.optJSONObject("latestSignal")
        return StockDetailResponse(
            stock = Stock(stock.getString("ticker"), stock.getString("name"), stock.getString("sector")),
            latestSignal = signal?.let {
                Signal(
                    ticker = it.getString("ticker"),
                    signalDate = it.getString("signalDate"),
                    score = it.getDouble("score"),
                    probability = it.getDouble("probability"),
                    targetReturn = it.getDouble("targetReturn"),
                    reasons = it.getJSONArray("reasons").toStringList(),
                    risks = it.getJSONArray("risks").toStringList(),
                    closePrice = it.getDouble("closePrice")
                )
            },
            chart = json.getJSONArray("chart").mapObjects {
                PriceBar(
                    ticker = it.getString("ticker"),
                    tradeDate = it.getString("tradeDate"),
                    open = it.getDouble("open"),
                    high = it.getDouble("high"),
                    low = it.getDouble("low"),
                    close = it.getDouble("close"),
                    volume = it.getLong("volume")
                )
            }
        )
    }

    fun backtestSummary(): BacktestSummary {
        val json = getJson("/api/backtest/summary")
        return BacktestSummary(
            evaluatedSignals = json.getInt("evaluatedSignals"),
            hitRate = json.getDouble("hitRate"),
            averageReturn = json.getDouble("averageReturn"),
            maxDrawdown = json.getDouble("maxDrawdown"),
            note = json.getString("note")
        )
    }

    fun registerDevice(token: String) {
        val body = JSONObject()
            .put("token", token)
            .put("platform", "android")
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/devices/register")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Device registration failed: ${response.code}")
        }
    }

    private fun getJson(path: String): JSONObject {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("API request failed: ${response.code}")
            return JSONObject(response.body?.string().orEmpty())
        }
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { index -> getString(index) }

private fun <T> JSONArray.mapObjects(mapper: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> mapper(getJSONObject(index)) }
