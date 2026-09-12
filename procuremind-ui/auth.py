"""
OIDC authentication for the ProcureMind Streamlit UI (Phase 6).

Confidential Authorization Code + PKCE flow against the existing auth-service
(Spring Authorization Server). No new identity provider.

- Client: `procuremind-streamlit` (confidential, client_secret_basic + PKCE).
- Front channel (browser -> /oauth2/authorize) uses STREAMLIT_OIDC_BROWSER_ISSUER.
- Back channel (server -> /oauth2/token, /userinfo) uses STREAMLIT_OIDC_INTERNAL_ISSUER
  so the container can reach auth-service over the Docker network.
- Tokens live in st.session_state; the transient PKCE verifier/state survive the external
  redirect via a small process-global map keyed by the OAuth `state`.

See docs/AUTH_IMPLEMENTATION_PLAN.md sections 6, 13, 18.
"""

import os
import time
from urllib.parse import urlencode

import requests
import streamlit as st
from authlib.common.security import generate_token
from authlib.integrations.requests_client import OAuth2Session

# --- Configuration --------------------------------------------------------------------------
BROWSER_ISSUER = os.getenv("STREAMLIT_OIDC_BROWSER_ISSUER", "http://localhost:8083").rstrip("/")
INTERNAL_ISSUER = os.getenv("STREAMLIT_OIDC_INTERNAL_ISSUER", "http://auth-service:8083").rstrip("/")
CLIENT_ID = os.getenv("STREAMLIT_OIDC_CLIENT_ID", "procuremind-streamlit")
CLIENT_SECRET = os.getenv("STREAMLIT_OIDC_CLIENT_SECRET", "")
REDIRECT_URI = os.getenv("STREAMLIT_OIDC_REDIRECT_URI", "http://localhost:8501/")
SCOPE = "openid profile roles"
AUTH_REQUIRED = os.getenv("AUTH_REQUIRED", "true").strip().lower() != "false"

AUTHORIZE_ENDPOINT_BROWSER = f"{BROWSER_ISSUER}/oauth2/authorize"
TOKEN_ENDPOINT = f"{INTERNAL_ISSUER}/oauth2/token"
USERINFO_ENDPOINT = f"{INTERNAL_ISSUER}/userinfo"
LOGOUT_ENDPOINT_BROWSER = f"{BROWSER_ISSUER}/connect/logout"

_ACCESS = "_oidc_access_token"
_REFRESH = "_oidc_refresh_token"
_ID_TOKEN = "_oidc_id_token"
_EXPIRES_AT = "_oidc_expires_at"
_USERINFO = "_oidc_userinfo"

# Process-global: OAuth `state` -> (code_verifier, created_at). Survives the external
# redirect that tears down the Streamlit session. `state` is a 64-char random token.
_PENDING: "dict[str, tuple[str, float]]" = {}
_PENDING_TTL_SECONDS = 600
_PENDING_MAX = 50


def _new_session() -> OAuth2Session:
    return OAuth2Session(
        client_id=CLIENT_ID,
        client_secret=CLIENT_SECRET or None,
        scope=SCOPE,
        redirect_uri=REDIRECT_URI,
        code_challenge_method="S256",
        token_endpoint_auth_method="client_secret_basic",
    )


def _prune_pending() -> None:
    now = time.time()
    stale = [s for s, (_, created) in _PENDING.items() if now - created > _PENDING_TTL_SECONDS]
    for s in stale:
        _PENDING.pop(s, None)
    while len(_PENDING) > _PENDING_MAX:
        _PENDING.pop(next(iter(_PENDING)), None)


def login_url() -> str:
    """Build the /oauth2/authorize URL (browser issuer) and stash the PKCE verifier."""
    _prune_pending()
    verifier = generate_token(64)
    session = _new_session()
    uri, state = session.create_authorization_url(
        AUTHORIZE_ENDPOINT_BROWSER, code_verifier=verifier
    )
    _PENDING[state] = (verifier, time.time())
    return uri


def _store_token(token: dict) -> None:
    st.session_state[_ACCESS] = token.get("access_token")
    if token.get("refresh_token"):
        st.session_state[_REFRESH] = token["refresh_token"]
    if token.get("id_token"):
        st.session_state[_ID_TOKEN] = token["id_token"]
    expires_at = token.get("expires_at")
    if not expires_at and token.get("expires_in"):
        expires_at = time.time() + float(token["expires_in"])
    st.session_state[_EXPIRES_AT] = expires_at or (time.time() + 300)


def _clear_session() -> None:
    for key in (_ACCESS, _REFRESH, _ID_TOKEN, _EXPIRES_AT, _USERINFO):
        st.session_state.pop(key, None)


def _fetch_userinfo() -> None:
    token = st.session_state.get(_ACCESS)
    if not token:
        return
    try:
        res = requests.get(
            USERINFO_ENDPOINT,
            headers={"Authorization": f"Bearer {token}"},
            timeout=10,
        )
        if res.status_code == 200:
            st.session_state[_USERINFO] = res.json()
    except requests.RequestException:
        pass


def handle_callback() -> None:
    """Exchange `?code=&state=` for tokens, then clear the query string and rerun."""
    params = st.query_params
    code = params.get("code")
    state = params.get("state")
    if not code:
        return

    pending = _PENDING.pop(state, None) if state else None
    if not pending:
        # Stale / unknown callback — drop the query string and fall through to the gate.
        st.query_params.clear()
        return
    verifier, _ = pending

    session = _new_session()
    try:
        token = session.fetch_token(
            TOKEN_ENDPOINT,
            grant_type="authorization_code",
            code=code,
            code_verifier=verifier,
            redirect_uri=REDIRECT_URI,
        )
    except Exception as exc:  # noqa: BLE001 - surface any token-exchange failure to the user
        _clear_session()
        st.query_params.clear()
        st.error(f"Sign-in failed: {exc}")
        return

    _store_token(token)
    _fetch_userinfo()
    st.query_params.clear()
    st.rerun()


def _refresh() -> bool:
    refresh_token = st.session_state.get(_REFRESH)
    if not refresh_token:
        return False
    session = _new_session()
    try:
        token = session.refresh_token(TOKEN_ENDPOINT, refresh_token=refresh_token)
    except Exception:  # noqa: BLE001
        _clear_session()
        return False
    _store_token(token)
    return True


def get_access_token():
    """Current access token, transparently refreshed when within 60s of expiry."""
    token = st.session_state.get(_ACCESS)
    if not token:
        return None
    expires_at = st.session_state.get(_EXPIRES_AT, 0)
    if expires_at and (expires_at - time.time()) <= 60:
        _refresh()
        token = st.session_state.get(_ACCESS)
    return token


def auth_headers() -> dict:
    token = get_access_token()
    return {"Authorization": f"Bearer {token}"} if token else {}


def refresh_or_relogin() -> bool:
    """Called after a 401. Returns True if a fresh token is ready; otherwise reruns into
    the login gate (this call does not return in that case)."""
    if _refresh():
        return True
    _clear_session()
    st.rerun()
    return False  # unreachable


def current_user() -> dict:
    return st.session_state.get(_USERINFO, {}) or {}


WRITE_ROLES = {"ANALYST", "ADMIN"}


def current_roles() -> list:
    """Roles from the `roles` claim auth-service mints into the ID token / userinfo."""
    roles = current_user().get("roles")
    return list(roles) if isinstance(roles, (list, tuple)) else []


def can_write() -> bool:
    """
    True when the signed-in user may ingest contracts and use the assistant.

    Presentation only: hiding a module is a convenience, not a security boundary. The
    gateway and each service authorize every request independently, so a VIEWER who
    reaches these endpoints another way still gets a 403. See
    docs/AUTH_IMPLEMENTATION_PLAN.md section 12.
    """
    if not AUTH_REQUIRED:
        return True
    return any(role in WRITE_ROLES for role in current_roles())


def logout_url() -> str:
    params = {"post_logout_redirect_uri": REDIRECT_URI}
    id_token = st.session_state.get(_ID_TOKEN)
    if id_token:
        params["id_token_hint"] = id_token
    return LOGOUT_ENDPOINT_BROWSER + "?" + urlencode(params)


def logout() -> None:
    """Clear the local session and rerun into the login gate."""
    _clear_session()
    st.rerun()


def _render_gate() -> None:
    st.markdown("## 🛡️ ProcureMind")
    st.write("Sign in with your ProcureMind ID to continue.")
    st.link_button("Sign in with ProcureMind ID", login_url(), type="primary")
    st.caption(f"[Sign out of ProcureMind ID completely]({logout_url()})")


def require_login() -> None:
    """Stop rendering unless the user has a usable access token (Phase 6 gate)."""
    if not AUTH_REQUIRED:
        return
    if get_access_token():
        return
    _render_gate()
    st.stop()
