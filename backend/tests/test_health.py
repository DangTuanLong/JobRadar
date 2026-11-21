from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_check() -> None:
    response = client.get("/api/v1/health")
    assert response.status_code == 200

    payload = response.json()
    assert payload["success"] is True
    assert payload["error"] is None
    assert payload["data"] is not None
    assert payload["data"]["status"] == "ok"
    assert "environment" in payload["data"]
