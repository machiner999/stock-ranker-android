package com.example.stockranker.data

data class RankingResponse(
    val signalDate: String?,
    val disclaimer: String,
    val items: List<RankingItem>
)

data class RankingItem(
    val ticker: String,
    val name: String,
    val sector: String,
    val score: Double,
    val probability: Double,
    val targetReturn: Double,
    val closePrice: Double,
    val reasons: List<String>,
    val risks: List<String>
)

data class StockDetailResponse(
    val stock: Stock,
    val latestSignal: Signal?,
    val chart: List<PriceBar>
)

data class Stock(
    val ticker: String,
    val name: String,
    val sector: String
)

data class Signal(
    val ticker: String,
    val signalDate: String,
    val score: Double,
    val probability: Double,
    val targetReturn: Double,
    val reasons: List<String>,
    val risks: List<String>,
    val closePrice: Double
)

data class PriceBar(
    val ticker: String,
    val tradeDate: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
)

data class BacktestSummary(
    val evaluatedSignals: Int,
    val hitRate: Double,
    val averageReturn: Double,
    val maxDrawdown: Double,
    val note: String
)
