package com.billscanner.app.ui.list

import java.io.Serializable

/**
 * The different "view modes" the app supports for browsing bills:
 * all bills, this week, this month, filtered by one category, or filtered
 * by one store. Serializable so it can ride along in an Intent extra.
 */
sealed class ListFilter : Serializable {
    object All : ListFilter()
    object ThisWeek : ListFilter()
    object ThisMonth : ListFilter()
    data class ByCategory(val categoryId: Long, val categoryName: String) : ListFilter()
    data class ByStore(val storeName: String) : ListFilter()
}
