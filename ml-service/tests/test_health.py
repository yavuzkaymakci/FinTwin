from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_returns_up() -> None:
    response = client.get("/health")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UP"
    assert body["service"] == "fintwin-ml-service"
