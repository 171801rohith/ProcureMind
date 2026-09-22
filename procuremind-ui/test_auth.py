"""Focused unit tests for procuremind-ui/auth.py (Phase 6).

Covers the pure OIDC helpers (authorize-URL + PKCE, token/header helpers, env parsing)
without a running Streamlit runtime — ``st.session_state`` is replaced with a plain dict.
"""
import importlib
from urllib.parse import parse_qs, urlparse

import pytest


@pytest.fixture
def auth(monkeypatch):
    monkeypatch.setenv("STREAMLIT_OIDC_CLIENT_ID", "procuremind-streamlit")
    monkeypatch.setenv("STREAMLIT_OIDC_CLIENT_SECRET", "test-secret")
    monkeypatch.setenv("STREAMLIT_OIDC_BROWSER_ISSUER", "http://localhost:8083")
    monkeypatch.setenv("STREAMLIT_OIDC_INTERNAL_ISSUER", "http://auth-service:8083")
    monkeypatch.setenv("STREAMLIT_OIDC_REDIRECT_URI", "http://localhost:8501/")
    monkeypatch.setenv("AUTH_REQUIRED", "true")
    import auth as mod
    importlib.reload(mod)
    monkeypatch.setattr(mod.st, "session_state", {}, raising=False)
    mod._PENDING.clear()
    return mod


def test_auth_required_env_parsing(monkeypatch):
    import auth as mod
    monkeypatch.setenv("AUTH_REQUIRED", "false")
    importlib.reload(mod)
    assert mod.AUTH_REQUIRED is False
    monkeypatch.setenv("AUTH_REQUIRED", "TRUE")
    importlib.reload(mod)
    assert mod.AUTH_REQUIRED is True


def test_login_url_is_a_pkce_authorize_url(auth):
    url = auth.login_url()
    parsed = urlparse(url)

    assert parsed.path.endswith("/oauth2/authorize")
    assert f"{parsed.scheme}://{parsed.netloc}" == "http://localhost:8083"

    q = parse_qs(parsed.query)
    assert q["response_type"] == ["code"]
    assert q["client_id"] == ["procuremind-streamlit"]
    assert q["redirect_uri"] == ["http://localhost:8501/"]
    assert q["code_challenge_method"] == ["S256"]
    assert q["code_challenge"][0]
    assert "openid" in q["scope"][0] and "roles" in q["scope"][0]

    state = q["state"][0]
    assert state in auth._PENDING
    verifier, _created = auth._PENDING[state]
    assert 43 <= len(verifier) <= 128


def test_auth_headers_and_token_helpers(auth):
    assert auth.auth_headers() == {}
    assert auth.get_access_token() is None

    auth.st.session_state[auth._ACCESS] = "tok-abc"
    auth.st.session_state[auth._EXPIRES_AT] = auth.time.time() + 3600

    assert auth.get_access_token() == "tok-abc"
    assert auth.auth_headers() == {"Authorization": "Bearer tok-abc"}


def test_get_access_token_near_expiry_without_refresh_token(auth):
    auth.st.session_state[auth._ACCESS] = "tok-old"
    auth.st.session_state[auth._EXPIRES_AT] = auth.time.time() + 5  # inside the 60s window

    # No refresh token in session -> _refresh() returns False and the old token is returned.
    assert auth.get_access_token() == "tok-old"


def test_current_user_defaults_to_empty(auth):
    assert auth.current_user() == {}


def test_logout_url_points_at_connect_logout(auth):
    auth.st.session_state[auth._ID_TOKEN] = "idtok"
    url = auth.logout_url()
    parsed = urlparse(url)
    assert parsed.path.endswith("/connect/logout")
    q = parse_qs(parsed.query)
    assert q["post_logout_redirect_uri"] == ["http://localhost:8501/"]
    assert q["id_token_hint"] == ["idtok"]


def test_can_write_follows_the_roles_claim(auth, monkeypatch):
    """Role gating is presentation only, but it has to read the same claim the gateway does."""
    monkeypatch.setattr(auth, "current_user", lambda: {"roles": ["VIEWER"]})
    assert auth.current_roles() == ["VIEWER"]
    assert auth.can_write() is False

    monkeypatch.setattr(auth, "current_user", lambda: {"roles": ["VIEWER", "ANALYST"]})
    assert auth.can_write() is True

    monkeypatch.setattr(auth, "current_user", lambda: {"roles": ["ADMIN"]})
    assert auth.can_write() is True


def test_a_missing_or_malformed_roles_claim_denies_write(auth, monkeypatch):
    monkeypatch.setattr(auth, "current_user", lambda: {})
    assert auth.current_roles() == []
    assert auth.can_write() is False

    monkeypatch.setattr(auth, "current_user", lambda: {"roles": "ADMIN"})
    assert auth.current_roles() == []
    assert auth.can_write() is False


def test_disabling_the_login_gate_restores_the_pre_auth_experience(monkeypatch):
    import auth as mod
    monkeypatch.setenv("AUTH_REQUIRED", "false")
    importlib.reload(mod)
    monkeypatch.setattr(mod, "current_user", lambda: {})
    assert mod.can_write() is True
