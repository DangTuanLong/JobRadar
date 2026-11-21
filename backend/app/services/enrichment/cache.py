"""In-memory bounded TTL cache for profile enrichment responses."""

from __future__ import annotations

import copy
import time
from collections import OrderedDict
from typing import Any

from app.core.constants import ENRICHMENT_CACHE_MAXSIZE, ENRICHMENT_CACHE_TTL_SECONDS


class TTLCache:
    """Bounded in-memory OrderedDict cache with per-entry TTL expiration."""

    def __init__(
        self,
        maxsize: int = ENRICHMENT_CACHE_MAXSIZE,
        ttl_seconds: float = ENRICHMENT_CACHE_TTL_SECONDS,
    ) -> None:
        self.maxsize = max(1, maxsize)
        self.ttl_seconds = max(0.0, ttl_seconds)
        self._store: OrderedDict[str, tuple[float, Any]] = OrderedDict()

    def _evict_expired(self, now: float) -> None:
        expired_keys = [
            key for key, (expires_at, _) in self._store.items() if now >= expires_at
        ]
        for key in expired_keys:
            self._store.pop(key, None)

    def get(self, key: str) -> Any | None:
        """Retrieve a non-expired cached value or None."""
        entry = self._store.get(key)
        if entry is None:
            return None
        expires_at, value = entry
        if time.monotonic() >= expires_at:
            self._store.pop(key, None)
            return None
        self._store.move_to_end(key)
        return copy.deepcopy(value)

    def set(self, key: str, value: Any) -> None:
        """Insert or update a cached value, evicting expired/oldest entries if full."""
        now = time.monotonic()
        self._evict_expired(now)
        if key in self._store:
            self._store.move_to_end(key)
        else:
            while len(self._store) >= self.maxsize:
                self._store.popitem(last=False)
        self._store[key] = (now + self.ttl_seconds, copy.deepcopy(value))

    def clear(self) -> None:
        """Remove all cached entries."""
        self._store.clear()

    def __len__(self) -> int:
        self._evict_expired(time.monotonic())
        return len(self._store)


def make_github_cache_key(username: str) -> str:
    """Build normalized cache key for a GitHub user."""
    return f"github:{username.strip().lower()}"


def make_jina_cache_key(normalized_url: str) -> str:
    """Build normalized cache key for a Jina Reader portfolio URL."""
    return f"jina:{normalized_url.strip()}"


enrichment_cache = TTLCache()
