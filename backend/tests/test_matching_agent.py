"""Offline unit tests for LangGraph Hybrid Matching Agent, Tavily tool, and guardrails."""

from __future__ import annotations

import json
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock

import google.genai as genai
import httpx
import pytest
from google.genai import _transformers as genai_transformers

from app.agents.tools.tavily_client import search_company_web_evidence
from app.core.config import settings
from app.core.constants import (
    MATCH_TAVILY_TOP_COMPANIES,
    MATCH_VECTOR_THRESHOLD_FALLBACK,
    MATCH_VECTOR_THRESHOLD_PRIMARY,
)
from app.schemas.match import (
    LLMMatchBatchResponse,
    LLMMatchEvaluationItem,
    ScoreBreakdown,
    compute_weighted_match_score,
)
from app.services.enrichment.cache import TTLCache
from app.services.matching_service import MatchingService


def _cand(idx: int, sim: float, cid: int | None = None) -> dict[str, Any]:
    c = cid if cid is not None else idx
    return {
        "job_posting_id": f"jp-{idx}",
        "company_id": f"comp-{c}",
        "company_name": f"TechCorp {c}",
        "company_industry": "Fintech",
        "company_location": "Ho Chi Minh City",
        "company_tech_stack": ["Python", "PostgreSQL"],
        "job_title": f"Backend Engineer #{idx}",
        "job_level": "junior",
        "required_skills": ["Python", "FastAPI"],
        "nice_to_have_skills": ["Docker"],
        "job_description": "Build scalable backend APIs.",
        "similarity": sim,
    }


def _eval(
    jp_id: str,
    comp_id: str,
    skills: int,
    exp: int,
    proj: int,
    *,
    llm_score: int = 0,
    matched: list[str] | None = None,
) -> LLMMatchEvaluationItem:
    return LLMMatchEvaluationItem(
        job_posting_id=jp_id,
        company_id=comp_id,
        match_score=llm_score,
        score_breakdown=ScoreBreakdown(skills=skills, experience=exp, projects=proj),
        matched_skills=matched or ["Python"],
        missing_skills=[],
        reasoning="Phù hợp tốt dựa trên kỹ năng Python đã xác thực trên GitHub.",
    )


def _profile() -> dict[str, Any]:
    return {
        "id": "prof-1",
        "user_id": "user-1",
        "full_name": "Nguyen Van A",
        "headline": "Junior Python Backend Developer",
        "years_of_experience": 1.5,
        "skills": [
            {"name": "Python", "level": "advanced", "verified_by": ["cv", "github"]},
            {"name": "FastAPI", "level": "intermediate", "verified_by": ["cv", "github"]},
        ],
        "experiences": [{"title": "JobRadar API", "technologies": ["FastAPI"]}],
        "enrichment_summary": {"github_highlights": ["jobradar (Python, 10★)"]},
        "profile_summary": "Backend engineer experienced in Python and FastAPI.",
        "embedding": [0.05] * 768,
    }


class _FakeMatchRepo:
    def __init__(self, responses: dict[float, list[dict[str, Any]]]) -> None:
        self.responses = responses
        self.search_calls: list[float] = []
        self.saved_batches: list[list[dict[str, Any]]] = []

    async def search_job_postings(
        self,
        query_embedding: list[float],
        match_threshold: float = 0.45,
        match_count: int = 15,
        filter_level: str | None = None,
    ) -> list[dict[str, Any]]:
        self.search_calls.append(match_threshold)
        return list(self.responses.get(match_threshold, []))

    async def save_match_results(
        self, job_id: str, profile_id: str, results: list[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        self.saved_batches.append(results)
        return [
            {"id": f"mr-{i}", "job_id": job_id, "profile_id": profile_id, **item}
            for i, item in enumerate(results, start=1)
        ]


def _mock_gemini(batch: LLMMatchBatchResponse) -> Any:
    fn = AsyncMock(return_value=SimpleNamespace(parsed=batch, text=batch.model_dump_json()))
    return SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=fn)), _fn=fn)


@pytest.mark.asyncio
async def test_full_hybrid_flow_limits_tavily_to_top_4_and_sorts_desc(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-tavily-key")
    cands = [
        _cand(1, 0.91, cid=1),
        _cand(99, 0.70, cid=1),
        _cand(2, 0.88, cid=2),
        _cand(3, 0.85, cid=3),
        _cand(4, 0.80, cid=4),
        _cand(5, 0.76, cid=5),
        _cand(6, 0.72, cid=6),
    ]
    queries: list[str] = []

    def handler(req: httpx.Request) -> httpx.Response:
        queries.append(json.loads(req.content.decode("utf-8"))["query"])
        res = [{"title": "Blog", "url": f"https://blog.ex.com/{len(queries)}", "content": "Py"}]
        return httpx.Response(200, json={"results": res})

    batch = LLMMatchBatchResponse(
        evaluations=[
            _eval("jp-1", "comp-1", 80, 60, 60, llm_score=10),
            _eval("jp-2", "comp-2", 92, 88, 88, llm_score=10),
            _eval("jp-5", "comp-5", 80, 70, 70, llm_score=50),
        ]
    )
    gemini = _mock_gemini(batch)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as hc:
        svc = MatchingService(
            match_repo=_FakeMatchRepo({MATCH_VECTOR_THRESHOLD_PRIMARY: cands}),  # type: ignore[arg-type]
            gemini_client=gemini,
            tavily_http_client=hc,
            active_tavily_cache=TTLCache(),
        )
        saved = await svc.run_for_job("j1", "p1", "u1", profile_data=_profile())

    assert len(queries) == MATCH_TAVILY_TOP_COMPANIES and gemini._fn.await_count == 1
    assert [r["job_posting_id"] for r in saved] == ["jp-2", "jp-5", "jp-1"]
    assert [r["match_score"] for r in saved] == [90, 75, 70]
    assert len(saved[0]["web_evidence"]) == 1 and saved[1]["web_evidence"] == []


@pytest.mark.asyncio
async def test_graceful_degradation_when_tavily_missing_or_failing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    cands = [_cand(1, 0.85), _cand(2, 0.80), _cand(3, 0.75)]
    batch = LLMMatchBatchResponse(evaluations=[_eval("jp-1", "comp-1", 80, 80, 80)])
    calls = 0

    def timeout_handler(req: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        raise httpx.ReadTimeout("Tavily timeout", request=req)

    monkeypatch.setattr(settings, "TAVILY_API_KEY", "")
    async with httpx.AsyncClient(transport=httpx.MockTransport(timeout_handler)) as hc:
        svc = MatchingService(
            match_repo=_FakeMatchRepo({MATCH_VECTOR_THRESHOLD_PRIMARY: cands}),  # type: ignore[arg-type]
            gemini_client=_mock_gemini(batch),
            tavily_http_client=hc,
            active_tavily_cache=TTLCache(),
        )
        r1 = await svc.run_for_job("j1", "p1", "u1", profile_data=_profile())
    assert calls == 0 and len(r1) == 1 and r1[0]["web_evidence"] == []

    monkeypatch.setattr(settings, "TAVILY_API_KEY", "active-key")
    async with httpx.AsyncClient(transport=httpx.MockTransport(timeout_handler)) as hc:
        svc = MatchingService(
            match_repo=_FakeMatchRepo({MATCH_VECTOR_THRESHOLD_PRIMARY: cands}),  # type: ignore[arg-type]
            gemini_client=_mock_gemini(batch),
            tavily_http_client=hc,
            active_tavily_cache=TTLCache(),
        )
        r2 = await svc.run_for_job("j2", "p1", "u1", profile_data=_profile())
    assert calls == 3 and len(r2) == 1 and r2[0]["web_evidence"] == []


@pytest.mark.asyncio
async def test_vector_fallback_and_empty_retrieval_exits_cleanly(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "")
    fb_repo = _FakeMatchRepo(
        {
            MATCH_VECTOR_THRESHOLD_PRIMARY: [_cand(1, 0.45)],
            MATCH_VECTOR_THRESHOLD_FALLBACK: [_cand(1, 0.45), _cand(2, 0.30), _cand(3, 0.28)],
        }
    )
    batch = LLMMatchBatchResponse(evaluations=[_eval("jp-2", "comp-2", 70, 60, 60)])
    svc = MatchingService(match_repo=fb_repo, gemini_client=_mock_gemini(batch))  # type: ignore[arg-type]
    saved = await svc.run_for_job("jfb", "p1", "u1", profile_data=_profile())
    assert fb_repo.search_calls == [MATCH_VECTOR_THRESHOLD_PRIMARY, MATCH_VECTOR_THRESHOLD_FALLBACK]
    assert len(saved) == 1

    empty_repo = _FakeMatchRepo(
        {MATCH_VECTOR_THRESHOLD_PRIMARY: [], MATCH_VECTOR_THRESHOLD_FALLBACK: []}
    )
    unused = _mock_gemini(batch)
    empty_svc = MatchingService(match_repo=empty_repo, gemini_client=unused)  # type: ignore[arg-type]
    assert await empty_svc.run_for_job("j0", "p1", "u1", profile_data=_profile()) == []
    assert unused._fn.await_count == 0 and empty_repo.saved_batches == []


@pytest.mark.asyncio
async def test_zero_hallucination_guardrails_drop_fake_ids_and_recompute_score(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(settings, "TAVILY_API_KEY", "test-key")
    cands = [_cand(1, 0.90), _cand(2, 0.85), _cand(3, 0.80)]

    def handler(_req: httpx.Request) -> httpx.Response:
        res = [{"title": "Careers", "url": "https://careers.tc1.vn/jobs", "content": "Python."}]
        return httpx.Response(200, json={"results": res})

    batch = LLMMatchBatchResponse(
        evaluations=[
            _eval("jp-1", "comp-1", 80, 60, 40, llm_score=99, matched=["Python", "python"]),
            _eval("jp-fake-999", "comp-1", 95, 95, 95, llm_score=95),
            _eval("jp-2", "comp-wrong", 90, 90, 90, llm_score=90),
        ]
    )
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as hc:
        svc = MatchingService(
            match_repo=_FakeMatchRepo({MATCH_VECTOR_THRESHOLD_PRIMARY: cands}),  # type: ignore[arg-type]
            gemini_client=_mock_gemini(batch),
            tavily_http_client=hc,
            active_tavily_cache=TTLCache(),
        )
        saved = await svc.run_for_job("jg", "p1", "u1", profile_data=_profile())

    assert len(saved) == 1 and saved[0]["job_posting_id"] == "jp-1"
    expected = compute_weighted_match_score(ScoreBreakdown(skills=80, experience=60, projects=40))
    assert saved[0]["match_score"] == expected == 65
    assert saved[0]["matched_skills"] == ["Python"]
    assert saved[0]["web_evidence"] == [
        {"title": "Careers", "url": "https://careers.tc1.vn/jobs", "snippet": "Python."}
    ]


@pytest.mark.asyncio
async def test_tavily_24h_cache_avoids_duplicate_http_calls() -> None:
    calls = 0

    def handler(_req: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        res = [{"title": "VNG", "url": "https://vng.com.vn/eng", "content": "IT"}]
        return httpx.Response(200, json={"results": res})

    cache = TTLCache(maxsize=10, ttl_seconds=86400)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as hc:
        r1 = await search_company_web_evidence("VNG Corp", api_key="k", cache=cache, http_client=hc)
        r2 = await search_company_web_evidence(
            "  vng   corp ", api_key="k", cache=cache, http_client=hc
        )

    assert calls == 1 and r1 == r2 and r1[0]["url"] == "https://vng.com.vn/eng"


def test_llm_match_batch_response_compatible_with_gemini_t_schema() -> None:
    client = genai.Client(api_key="test-offline-key")
    schema = genai_transformers.t_schema(client._api_client, LLMMatchBatchResponse)
    assert schema.defs is None and schema.ref is None
    assert "evaluations" in (schema.properties or {})
