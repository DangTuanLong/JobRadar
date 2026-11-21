"""Unit tests with mocked Supabase client for Job, Profile, and Match repositories."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from app.core.exceptions import AppException
from app.db.repositories.job_repository import JobRepository
from app.db.repositories.match_repository import MatchRepository
from app.db.repositories.profile_repository import ProfileRepository


class _FakeQueryBuilder:
    """Fluent mock for Supabase PostgREST table/RPC query builders."""

    def __init__(self, client: _FakeSupabaseClient, table_name: str) -> None:
        self.client = client
        self.table_name = table_name
        self.action = "select"
        self.payload: Any = None
        self.filters: list[tuple[str, Any]] = []
        self.order_args: tuple[str, bool] | None = None
        self.limit_val: int | None = None

    def select(self, columns: str = "*") -> _FakeQueryBuilder:
        self.action = f"select:{columns}"
        return self

    def insert(self, payload: Any) -> _FakeQueryBuilder:
        self.action = "insert"
        self.payload = payload
        return self

    def update(self, payload: Any) -> _FakeQueryBuilder:
        self.action = "update"
        self.payload = payload
        return self

    def eq(self, column: str, value: Any) -> _FakeQueryBuilder:
        self.filters.append((column, value))
        return self

    def order(self, column: str, *, desc: bool = False) -> _FakeQueryBuilder:
        self.order_args = (column, desc)
        return self

    def limit(self, count: int) -> _FakeQueryBuilder:
        self.limit_val = count
        return self

    def execute(self) -> SimpleNamespace:
        self.client.calls.append(
            {
                "table": self.table_name,
                "action": self.action,
                "payload": self.payload,
                "filters": list(self.filters),
                "order": self.order_args,
                "limit": self.limit_val,
            }
        )
        key = (self.table_name, self.action.split(":", maxsplit=1)[0])
        data = self.client.responses.get(key, [])
        return SimpleNamespace(data=data)


class _FakeRpcBuilder:
    def __init__(self, client: _FakeSupabaseClient, fn_name: str, params: dict[str, Any]) -> None:
        self.client = client
        self.fn_name = fn_name
        self.params = params

    def execute(self) -> SimpleNamespace:
        self.client.rpc_calls.append({"fn": self.fn_name, "params": self.params})
        return SimpleNamespace(data=self.client.rpc_responses.get(self.fn_name, []))


class _FakeSupabaseClient:
    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []
        self.rpc_calls: list[dict[str, Any]] = []
        self.responses: dict[tuple[str, str], list[dict[str, Any]]] = {}
        self.rpc_responses: dict[str, list[dict[str, Any]]] = {}

    def table(self, name: str) -> _FakeQueryBuilder:
        return _FakeQueryBuilder(self, name)

    def rpc(self, fn_name: str, params: dict[str, Any]) -> _FakeRpcBuilder:
        return _FakeRpcBuilder(self, fn_name, params)


@pytest.mark.asyncio
async def test_job_repository_crud_and_validation() -> None:
    fake_client = _FakeSupabaseClient()
    fake_client.responses[("analysis_jobs", "insert")] = [{"id": "job-1", "status": "PENDING"}]
    fake_client.responses[("analysis_jobs", "select")] = [{"id": "job-1", "user_id": "u-1"}]
    fake_client.responses[("analysis_jobs", "update")] = [{"id": "job-1", "status": "COMPLETED"}]

    repo = JobRepository(client=fake_client)  # type: ignore[arg-type]

    created = await repo.create_job(user_id="u-1")
    assert created["id"] == "job-1"

    fetched = await repo.get_job_by_id("job-1", "u-1")
    assert fetched is not None
    assert ("id", "job-1") in fake_client.calls[1]["filters"]
    assert ("user_id", "u-1") in fake_client.calls[1]["filters"]

    updated = await repo.update_job_status("job-1", "COMPLETED", 100, current_step_message="Done")
    assert updated is not None
    assert updated["status"] == "COMPLETED"

    with pytest.raises(AppException) as exc_info:
        await repo.update_job_status("job-1", "UNKNOWN_STATE", 50)
    assert exc_info.value.code == "INVALID_JOB_STATUS"


@pytest.mark.asyncio
async def test_profile_repository_filters_non_null_fields_and_enforces_user() -> None:
    fake_client = _FakeSupabaseClient()
    fake_client.responses[("candidate_profiles", "select")] = [{"id": "prof-1", "user_id": "u-1"}]
    fake_client.responses[("candidate_profiles", "insert")] = [{"id": "prof-1", "user_id": "u-1"}]
    fake_client.responses[("candidate_profiles", "update")] = [{"id": "prof-1", "headline": "AI"}]

    repo = ProfileRepository(client=fake_client)  # type: ignore[arg-type]

    latest = await repo.get_latest_profile("u-1")
    assert latest is not None
    assert fake_client.calls[0]["order"] == ("updated_at", True)

    await repo.create_profile(
        "u-1",
        {"full_name": "Test", "skills": None, "ignored_column": "x"},
    )
    insert_payload = fake_client.calls[1]["payload"]
    assert insert_payload["user_id"] == "u-1"
    assert "skills" not in insert_payload
    assert "ignored_column" not in insert_payload

    updated = await repo.update_profile(
        "prof-1",
        "u-1",
        {"headline": "AI", "skills": None},
    )
    assert updated is not None
    update_call = fake_client.calls[2]
    assert "skills" not in update_call["payload"]
    assert ("id", "prof-1") in update_call["filters"]
    assert ("user_id", "u-1") in update_call["filters"]


@pytest.mark.asyncio
async def test_match_repository_rpc_save_and_idor_check() -> None:
    fake_client = _FakeSupabaseClient()
    fake_client.rpc_responses["match_job_postings"] = [
        {"job_posting_id": "jp-1", "similarity": 0.8}
    ]
    fake_client.responses[("match_results", "insert")] = [{"id": "mr-1", "match_score": 88}]

    repo = MatchRepository(client=fake_client)  # type: ignore[arg-type]

    hits = await repo.search_job_postings([0.1, 0.2], match_threshold=0.5, match_count=5)
    assert len(hits) == 1
    assert fake_client.rpc_calls[0]["fn"] == "match_job_postings"

    saved = await repo.save_match_results(
        "job-1",
        "prof-1",
        [{"company_id": "comp-1", "match_score": 88, "matched_skills": ["Python"]}],
    )
    assert len(saved) == 1
    assert fake_client.calls[0]["payload"][0]["job_id"] == "job-1"

    # IDOR check: user does NOT own job-1 -> must return [] without querying match_results.
    fake_client.responses[("analysis_jobs", "select")] = []
    blocked = await repo.get_match_results_by_job("job-1", "other-user")
    assert blocked == []
    assert all(c["table"] != "match_results" or c["action"] != "select" for c in fake_client.calls)

    # Owned job -> returns match_results ordered descending by match_score.
    fake_client.responses[("analysis_jobs", "select")] = [{"id": "job-1"}]
    fake_client.responses[("match_results", "select")] = [{"id": "mr-1", "match_score": 88}]
    owned = await repo.get_match_results_by_job("job-1", "owner-user")
    assert len(owned) == 1
    assert fake_client.calls[-1]["order"] == ("match_score", True)
