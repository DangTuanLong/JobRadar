package com.jobradar.app.data.cache

import com.jobradar.app.domain.model.MatchResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe in-memory cache of match results keyed by analysis job ID.
 * Decoupled from Retrofit so [com.jobradar.app.data.repository.AuthRepositoryImpl]
 * can clear cached match data on sign-out without introducing a DI cycle.
 */
@Singleton
class MatchCacheStore @Inject constructor() {

    private val cache = ConcurrentHashMap<String, List<MatchResult>>()

    fun get(jobId: String): List<MatchResult>? = cache[jobId]

    fun put(jobId: String, results: List<MatchResult>) {
        cache[jobId] = results
    }

    fun clear() {
        cache.clear()
    }
}
