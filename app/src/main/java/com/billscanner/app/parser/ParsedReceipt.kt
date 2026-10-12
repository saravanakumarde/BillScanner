package com.billscanner.app.parser

data class ParsedItem(
    val nameOriginal: String,
    val quantity: Double = 1.0,
    val unitPrice: Double? = null,
    val totalPrice: Double
)

data class ParsedReceipt(
    val storeName: String?,
    val dateMillis: Long?,
    val dateWasFound: Boolean,
    val total: Double?,
    val currency: String,
    val language: String,       // "de", "en", or "unknown"
    val items: List<ParsedItem>
)
