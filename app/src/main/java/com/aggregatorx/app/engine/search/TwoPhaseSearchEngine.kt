package com.aggregatorx.app.engine.search

import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URLEncoder

/**
 * Two-Phase Search Engine - Delivers query-tailored fresh results
 * 
 * Phase 1: Direct Query Search
 * - Performs actual searches on provider sites as if user typed query there
 * - Disables all caching with HTTP headers
 * - Extracts results exactly as displayed on the site
 * - High confidence (0.95f) for direct site results
 * 
 * Phase 2: Preference-Based Ranking & Related Content
 * - Re-ranks Phase 1 results by user preferences (liked keywords, quality, providers)
 * - Finds semantically similar content
 * - Low confidence (0.60f-0.80f) for preference/related results
 * - Uses token-based and learning-based matching
 */
@Singleton
class TwoPhaseSearchEngine @Inject constructor() {

    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val DEFAULT_USER_AGENT = EngineUtils.DEFAULT_USER_AGENT
    }

    /**
     * Execute complete two-phase search: direct query -> preference ranking
     */
    suspend fun performTwoPhaseSearch(
        baseUrl: String,
        query: String,
        providerId: String,
        preferences: SearchPreferences = SearchPreferences()
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        // Phase 1: Get actual search results from site
        val directResults = performDirectQuery(baseUrl, query, providerId)
        
        if (directResults.isEmpty()) {
            return@withContext emptyList()
        }
        
        // Phase 2: Re-rank and find related content
        val enhancedResults = rankByPreference(directResults, query, preferences)
        val relatedResults = findSemanticallySimilar(directResults, query)
        
        // Merge: direct results first (high confidence), then related (lower confidence)
        (enhancedResults + relatedResults).distinctBy { it.url }
    }

    /**
     * Phase 1: Direct Query Search
     * Performs actual search on the provider site as if user typed the query
     * Always fetches fresh - no caching
     */
    suspend fun performDirectQuery(
        baseUrl: String,
        query: String,
        providerId: String
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            // Build the actual search URL as the site would construct it
            val searchUrl = buildActualSearchUrl(baseUrl, query)
            
            // Fetch document with explicit cache-busting headers
            val document = fetchDocumentNoCached(searchUrl)
                ?: return@withContext emptyList()
            
            // Parse results exactly as they appear on the site
            parseDirectSearchResults(document, baseUrl, providerId, query)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetch document without any caching - forces fresh content
     */
    private suspend fun fetchDocumentNoCached(searchUrl: String): Document? {
        return try {
            Jsoup.connect(searchUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(DEFAULT_TIMEOUT)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(true)
                // Cache-busting headers
                .header("Cache-Control", "no-cache, no-store, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .header("If-Modified-Since", "0")
                .get()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build the actual search URL as the provider site would construct it
     * Detects common search patterns
     */
    private fun buildActualSearchUrl(baseUrl: String, query: String): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val lowerBase = baseUrl.lowercase()
        
        // Detect specific search patterns
        return when {
            lowerBase.contains("google") -> "https://www.google.com/search?q=$encodedQuery"
            lowerBase.contains("bing") -> "https://www.bing.com/search?q=$encodedQuery"
            lowerBase.contains("duckduckgo") -> "https://duckduckgo.com/?q=$encodedQuery"
            lowerBase.contains("youtube") -> "https://www.youtube.com/results?search_query=$encodedQuery"
            
            // Generic patterns - try most common first
            else -> {
                // Try in order: /search?q=, /search?query=, /?s=, /find/
                val patterns = listOf(
                    "$baseUrl/search?q=$encodedQuery&nocache=${System.currentTimeMillis()}",
                    "$baseUrl/search?query=$encodedQuery",
                    "$baseUrl/?s=$encodedQuery",
                    "$baseUrl/find/$encodedQuery"
                )
                patterns[0] // Use first with cache-busting parameter
            }
        }
    }

    /**
     * Parse ACTUAL search results from the site - extracts what users see
     * Uses multiple common selectors to find results
     */
    private fun parseDirectSearchResults(
        document: Document,
        baseUrl: String,
        providerId: String,
        query: String
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        
        // Common search result selectors - organized by likelihood
        val resultSelectors = listOf(
            // Generic container selectors
            "div.search-result",
            "div.result",
            "div.result-item",
            "article.result",
            "li.search-item",
            "li.result",
            "div[data-result]",
            "div.item",
            
            // Video platforms
            "div.yt-lockup-tile",
            "div.ytd-video-renderer",
            
            // News/blog style
            "article",
            "div.news-item",
            "div.post",
            
            // Grid/card layouts
            "div.card",
            "div.result-card"
        )
        
        // Title selectors - in priority order
        val titleSelectors = listOf(
            "h2, h3, h4, .title, [data-title], .result-title",
            "a[href]",
            ".link-title"
        )
        
        // URL selectors - in priority order
        val urlSelectors = listOf(
            "a[href]",
            "[data-url]",
            "a.result-link"
        )
        
        // Try each container selector
        for (containerSelector in resultSelectors) {
            document.select(containerSelector).forEach { element ->
                try {
                    var title = ""
                    var url = ""
                    
                    // Extract title
                    for (titleSelector in titleSelectors) {
                        val titleEl = element.selectFirst(titleSelector)
                        if (titleEl != null) {
                            title = titleEl.text().trim()
                            if (title.isNotEmpty() && title.length > 3) break
                        }
                    }
                    
                    // Extract URL
                    for (urlSelector in urlSelectors) {
                        val urlEl = element.selectFirst(urlSelector)
                        if (urlEl != null) {
                            url = urlEl.attr("href").trim()
                            if (urlEl.attr("data-url").isNotEmpty()) {
                                url = urlEl.attr("data-url")
                            }
                            if (url.isNotEmpty()) break
                        }
                    }
                    
                    // Extract thumbnail if present
                    val thumbnail = element.selectFirst("img")?.attr("src")
                    
                    // Extract description
                    val description = element.select(".description, .snippet, p").firstOrNull()?.text()
                    
                    // Validate and add result
                    if (title.isNotEmpty() && title.length > 3 && url.isNotEmpty()) {
                        // Ensure absolute URL
                        val absoluteUrl = if (url.startsWith("http")) url else {
                            if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url"
                        }
                        
                        // Avoid duplicates
                        if (results.none { it.url == absoluteUrl }) {
                            results.add(
                                SearchResult(
                                    providerId = providerId,
                                    providerName = baseUrl,
                                    title = title,
                                    url = absoluteUrl,
                                    thumbnailUrl = thumbnail?.ifEmpty { null },
                                    description = description?.ifEmpty { null },
                                    relevanceScore = 0.95f, // High confidence for direct results
                                    matchedTerms = extractMatchedTerms(title, query)
                                )
                            )
                            
                            // Stop if we have enough results
                            if (results.size >= 50) return results
                        }
                    }
                } catch (e: Exception) {
                    // Skip malformed results, continue with next
                    continue
                }
            }
            
            // If we found good results, stop trying other selectors
            if (results.size >= 10) break
        }
        
        return results.sortedByDescending { it.relevanceScore }
    }

    /**
     * Phase 2: Re-rank results by user preferences
     */
    private fun rankByPreference(
        items: List<SearchResult>,
        query: String,
        prefs: SearchPreferences
    ): List<SearchResult> {
        return items.map { item ->
            var score = item.relevanceScore
            
            // Boost by quality preference
            if (prefs.preferHighQuality && item.quality != null) {
                when {
                    item.quality.contains("4K", ignoreCase = true) -> score += 0.25f
                    item.quality.contains("1080", ignoreCase = true) -> score += 0.15f
                    item.quality.contains("720", ignoreCase = true) -> score += 0.10f
                }
            }
            
            // Boost by rating
            item.rating?.let { rating ->
                score += (rating / 10f) * 0.15f
            }
            
            // Boost if matches liked keywords
            if (prefs.likedKeywords.any { keyword ->
                item.title.contains(keyword, ignoreCase = true) ||
                (item.description?.contains(keyword, ignoreCase = true) ?: false)
            }) {
                score += 0.20f
            }
            
            // Penalize if contains excluded keywords
            if (prefs.excludeKeywords.any { keyword ->
                item.title.contains(keyword, ignoreCase = true)
            }) {
                score -= 0.30f
            }
            
            // Penalize if from disliked providers
            if (item.providerId in prefs.dislikedProviderIds) {
                score -= 0.25f
            }
            
            item.copy(relevanceScore = score.coerceIn(0f, 1f))
        }.sortedByDescending { it.relevanceScore }
    }

    /**
     * Phase 2: Find semantically similar content
     */
    private fun findSemanticallySimilar(
        items: List<SearchResult>,
        query: String
    ): List<SearchResult> {
        val queryTokens = query.lowercase().split(Regex("\\s+"))
            .filter { it.length > 2 }
            .toSet()
        
        return items.mapNotNull { item ->
            val titleTokens = item.title.lowercase().split(Regex("\\s+"))
                .filter { it.length > 2 }
                .toSet()
            
            // Calculate Jaccard similarity
            val intersection = queryTokens.intersect(titleTokens).size.toFloat()
            val union = queryTokens.union(titleTokens).size.toFloat()
            val similarity = if (union > 0) intersection / union else 0f
            
            // Include if at least 30% similar
            if (similarity >= 0.3f) {
                item.copy(
                    relevanceScore = 0.60f + (similarity * 0.2f), // 0.60-0.80 range
                    matchedTerms = queryTokens.intersect(titleTokens).toList()
                )
            } else {
                null
            }
        }.sortedByDescending { it.relevanceScore }
    }

    /**
     * Extract query terms that matched in the title
     */
    private fun extractMatchedTerms(title: String, query: String): List<String> {
        val queryTerms = query.lowercase().split(Regex("\\s+"))
        val titleLower = title.lowercase()
        
        return queryTerms.filter { term ->
            titleLower.contains(term)
        }
    }
}

/**
 * Search Preferences - User preferences for ranking and filtering
 */
data class SearchPreferences(
    val preferHighQuality: Boolean = true,
    val likedKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val dislikedProviderIds: Set<String> = emptySet(),
    val maxResults: Int = 50,
    val preferredQualities: List<String> = listOf("1080p", "720p")
)
