# procuremind-ui

## Purpose

A Streamlit dashboard over the same gateway API as the React SPA. It is a server-side
application: the Python process holds the session and makes the API calls, so the browser
never talks to the gateway directly.

## Technology

Python 3.13, Streamlit, Pandas, Plotly, `requests`, `authlib` (`OAuth2Session`), managed with
`uv` (`pyproject.toml`, `uv.lock`). Tests use pytest.

## Files

```
main.py       the whole application: config, API helpers, five screens, router
auth.py       OIDC login, token storage, refresh, logout, role helpers
test_auth.py  unit tests for the pure helpers in auth.py
Dockerfile    container image
```

There is no package structure; `main.py` is a single module of render functions.

## Entry point

`main()` at the bottom of `main.py`, invoked under `if __name__ == "__main__"`. In order it
calls `oidc_auth.handle_callback()`, `oidc_auth.require_login()`, `render_header()`,
`render_sidebar()` for the tab choice, `load_merged_contracts()`, then dispatches to one
screen.

`st.set_page_config` runs at import time, before any rendering.

## Screens

| Sidebar entry | Function | Content |
|---|---|---|
| BI Dashboard | `render_dashboard(df)` | KPIs, financial exposure, risk distribution, recent contracts |
| Document Intelligence | `render_document_intelligence(df)` | Table of contents, clause reading pane, identified risks |
| Vendors Portfolio | `render_vendors_portfolio(df)` | Per-vendor aggregation |
| Ingestion Pipeline | `render_upload_modal()` | Upload form. **Shown only when `can_write()`** |
| ProcureMind AI Assistant | `render_agentic_chat_page()` | Chat against `/api/analysis/chat`. **Shown only when `can_write()`** |

The two write-capable entries are filtered out of the sidebar for a VIEWER, and the router
re-checks `can_write()` before dispatching.

## API layer

`GATEWAY_URL` defaults to `http://localhost:8080`; Compose sets it to
`http://api-gateway:8080` because the call is server-side, not from the browser.

| Function | Endpoint |
|---|---|
| `safe_get_api(endpoint)` | Shared GET helper: attaches `auth_headers()`, retries once after refresh on 401 |
| `fetch_contracts_api()` | `GET /api/contracts` |
| `fetch_dashboard_metrics_api()` | `GET /api/analysis/dashboard-metrics` |
| `fetch_financial_exposure_api()` | `GET /api/analysis/financial-exposure` |
| `fetch_risk_distribution_api()` | `GET /api/analysis/risks/distribution` |
| `fetch_contract_analysis_api(id)` | `GET /api/analysis/{id}` |
| `fetch_contract_toc_api(id)` | `GET /api/analysis/{id}/toc` |
| `fetch_node_detail_api(node_id)` | `GET /api/analysis/node/{node_id}` |
| `fetch_contract_risks_api(id)` | `GET /api/analysis/{id}/risks` |
| `upload_contract_api(file, vendor_name, contract_type, amount)` | `POST /api/contracts/upload` |
| `check_status_api(id)` | `GET /api/contracts/{id}/status` |
| `send_chat_api(user_message, conversation_id)` | `POST /api/analysis/chat` |

`load_merged_contracts()` joins `/api/contracts` with per-contract `/api/analysis/{id}` into a
Pandas DataFrame, the same client-side CQRS merge the React app does.

## Authentication

`auth.py` implements Authorization Code with PKCE for a **confidential** client using
`authlib`. Unlike the React client it has a secret, which is safe because the exchange happens
server-side.

| Constant | Default | Why |
|---|---|---|
| `BROWSER_ISSUER` | `http://localhost:8083` | Used for `/oauth2/authorize` and `/connect/logout`, which the browser follows |
| `INTERNAL_ISSUER` | `http://auth-service:8083` | Used for `/oauth2/token` and `/userinfo`, called server-side |
| `CLIENT_ID` | `procuremind-streamlit` | |
| `CLIENT_SECRET` | empty | Must be set, or auth-service never registers the client |
| `REDIRECT_URI` | `http://localhost:8501/` | Registered with auth-service |
| `AUTH_REQUIRED` | `true` | `"false"` skips the login gate |

Splitting the issuer in two is the point: the browser must be sent to a URL it can resolve,
while the token exchange stays on the container network.

| Function | Purpose |
|---|---|
| `login_url()` | Builds the authorize URL with a PKCE challenge and records the verifier |
| `handle_callback()` | Exchanges `?code=` for tokens, stores them, fetches userinfo |
| `require_login()` | Renders the sign-in gate and calls `st.stop()` unless a token is present |
| `get_access_token()` | Returns a valid token, refreshing first if it is close to expiry |
| `auth_headers()` | `{"Authorization": "Bearer ..."}` or `{}` |
| `refresh_or_relogin()` | Refresh once; on failure clear the session and rerun into the gate |
| `current_user()`, `current_roles()`, `can_write()` | Role helpers; `can_write()` is true for ANALYST or ADMIN |
| `logout_url()`, `logout()` | RP-initiated logout with `id_token_hint`, then clear local state |

Tokens live in `st.session_state` under the `_oidc_*` keys and never reach the browser.
`_PENDING` holds in-flight PKCE verifiers with a 600 second TTL and a 50 entry cap.

Unlike the React client, the confidential Streamlit client **does** receive a refresh token,
which is why `_refresh()` exists here and silent renew does not.

## Running

```bash
uv sync
uv run streamlit run main.py          # http://localhost:8501
uv run --with pytest python -m pytest -q
```

In Compose the container is built from `Dockerfile` and published on 8501.

## Not clearly established

The Dockerfile installs its Python dependencies directly rather than from `pyproject.toml` and
`uv.lock`, so the container and a local `uv sync` can drift. Whether that is deliberate is not
clearly established from the current implementation.
