# procuremind-react

## Purpose

The primary user interface: dashboards, document intelligence, vendor portfolio, AI chat,
contract upload and the admin user-management screen. It talks only to the API gateway and to
auth-service.

## Technology

React 19, Vite, Tailwind CSS v4, Recharts, lucide-react, `oidc-client-ts` with
`react-oidc-context`, `jspdf` + `jspdf-autotable` (client-side PDF export), oxlint, Vitest.

## Entry point and shell

```
main.jsx        wraps <App/> in <AuthProvider {...oidcConfig}> from react-oidc-context
App.jsx         auth gate, then AppProvider + Sidebar + Header + MainContent
```

**There is no router.** Navigation is a single `activeTab` string held in `AppContext`.
`MainContent` switches on it:

| `activeTab` | Renders |
|---|---|
| `dashboard` | `KPICards`, `ExposureChart`, `RiskDonutChart`, `RecentContractsTable` |
| `intelligence` | `IntelligenceScreen` (`TOCNav`, `LegalReadingPane`, `RiskSidePanel`) |
| `vendors` | `VendorPortfolio` |
| `chat` | `ChatInterface` |
| `admin` | `UserManagement` if admin, otherwise `Unauthorized` |

`App` renders `<LoginScreen/>` when `AUTH_REQUIRED` is on and the user is not authenticated.

Because there is no router, "navigating to the admin route" means setting `activeTab` to
`admin`. The guard is in `MainContent`, and the real boundary is the backend.

## Directory structure

```
src/
├── main.jsx, App.jsx
├── auth/         OIDC configuration, login screen, role helpers, silent renew
├── api/          the single API client
├── context/      AppContext, the only global state
├── components/
│   ├── layout/       Header, Sidebar
│   ├── dashboard/    KPI and chart widgets, RecentContractsTable ("Export CSV")
│   ├── intelligence/ TOC, reading pane, risk side panel, IntelligenceScreen ("Export PDF")
│   ├── vendors/      VendorPortfolio
│   ├── chat/         ChatInterface
│   ├── upload/       UploadModal
│   ├── admin/        UserManagement, Unauthorized
│   └── ui/           Button, Card, Modal, Badge, Skeleton, EmptyState
├── utils/        exportUtils.js — CSV/PDF export (see below)
└── index.css
```

## Authentication

Authorization Code with PKCE against auth-service. No password is ever entered in React and
there is no client secret.

| File | Role |
|---|---|
| `auth/authConfig.js` | `oidcConfig`: authority, public client id, `response_type: 'code'`, scope `openid profile roles`, `automaticSilentRenew`, `silent_redirect_uri`, and both token stores pinned to **sessionStorage** |
| `auth/authFlags.js` | `AUTH_REQUIRED`, kept separate because `authConfig` touches `window.location` at import time |
| `auth/LoginScreen.jsx` | Pre-redirect gate; the button calls `auth.signinRedirect()` |
| `auth/silentRenew.js` + `/silent-renew.html` | The hidden renewal iframe's callback, calling `signinSilentCallback()` |
| `auth/roles.js` | `claimedRoles(user)`, `hasWriteAccess(roles)`, `isAdminRole(roles)`, and the `useRoles()` hook |

**Silent renew needs its own page.** `oidc-client-ts` defaults `silent_redirect_uri` to
`redirect_uri`, which would boot the whole application inside the renewal iframe where nothing
calls `signinSilentCallback`, so every renewal would time out. `silent-renew.html` is a second
Vite entry point declared in `vite.config.js`, and its URI must be registered with
auth-service.

No refresh token is issued to this client; see `auth-service/README.md`.

`onSigninCallback` strips `?code=&state=` from the URL so the tab-based shell is untouched.

### Roles in the UI

`useRoles()` reads `auth.user.profile.roles`, which comes from the id token.

| Helper | Effect |
|---|---|
| `canWrite` | Shows the upload button in `Header` and `Sidebar`, and the AI chat nav item |
| `isAdmin` | Shows the User Management nav item and gates the admin screen |

`hasWriteAccess` returns true when the login gate is disabled; `isAdminRole` deliberately does
not, because without a token the admin screen's requests would all fail anyway.

**This is presentation only.** Hiding a control is a convenience; the gateway and each service
authorize every request independently.

## API client

`src/api/client.js` is the only place that calls the backend.

| Export | Purpose |
|---|---|
| `setAccessToken(token)` | Called from `AppContext` whenever the OIDC user changes |
| `setOnAuthExpired(fn)` | Handler used on a 401 |
| `ApiClient` | All endpoint methods |

Two request helpers:

- `apiFetch` for reads. Adds `Accept` and the bearer token, applies a timeout via
  `AbortController`, retries once after a successful refresh on 401, and **swallows errors,
  returning `null`**.
- `authedFetch` for writes. Returns the raw `Response` so callers can branch on status.

| Method | Endpoint |
|---|---|
| `getContracts()` | `GET /api/contracts` |
| `getContractAnalysis(id)` | `GET /api/analysis/{id}` |
| `getMergedContracts()` | Both of the above, merged client-side |
| `getDashboardMetrics()`, `getFinancialExposure()`, `getRiskDistribution()` | dashboard reads |
| `getContractTOC(id)`, `getContractRisks(id)`, `getNodeDetail(nodeId)` | intelligence screen |
| `uploadContract(file, vendorName, contractType, amount)` | `POST /api/contracts/upload` |
| `checkContractStatus(id)` | `GET /api/contracts/{id}/status` |
| `sendChatMessage(userMessage, conversationId)` | `POST /api/analysis/chat` |
| `listUsers()`, `createUser({...})` | `GET`/`POST /api/users`, throwing errors carrying `status` and `fieldErrors` |

`getMergedContracts` does N+1 calls by design: one `/api/contracts` then one
`/api/analysis/{id}` per contract.

Note: `uploadContract` sends `contractType` and `amount`, which **contract-service ignores**.

## State

`context/AppContext.jsx` is the only global state: `activeTab`, contracts, metrics, exposure,
risk distribution, loading and error flags, the upload modal flag, the mobile sidebar flag, a
generated `conversationId` and chat history.

It also keeps the API client's token in sync and installs the expiry handler, which tries
`auth.signinSilent()` once and falls back to `auth.signinRedirect()`.

## Upload flow

`Header` or `Sidebar` (both gated on `canWrite`) set `uploadModalOpen`. `UploadModal`:

1. A file is chosen by drag-and-drop onto the zone, or through the "Browse File" label that
   opens a hidden `<input type="file" accept=".pdf">`. The zone itself has no click handler.
2. The submit button is `disabled={!file}`.
3. `handleSubmit` returns early if no file, then calls `ApiClient.uploadContract`, polls
   `checkContractStatus` once, calls `refreshData()` and closes.
4. Any thrown error sets the message `"Upload failed. Check gateway connection."`

## Export/reporting

Implements the "Export/reporting (PDF/CSV)" item from `ARCHITECTURE_REVIEW.md`. Both exports
are client-side only — no dedicated backend endpoint — built from data the app has already
fetched.

`src/utils/exportUtils.js`:

| Function | Used from | Produces |
|---|---|---|
| `exportContractsToCsv(contracts)` | `RecentContractsTable`'s "Export CSV" button | The currently filtered/visible rows (vendor, file name, type, status, risk score, value, recommendation, uploaded date) as an RFC-4180-escaped CSV, downloaded via `Blob` + a synthetic `<a download>` click. Hand-rolled escaping, not a library — the rules needed (comma/quote/newline) are small |
| `exportContractAnalysisToPdf(contract, risks)` | `IntelligenceScreen`'s "Export PDF" button | A one-page report for the currently selected contract — vendor, file, type, status, value, risk score, uploaded date, recommendation, summary, and a risks table via `jspdf-autotable` — via `jsPDF`, saved directly with `doc.save(...)` |

Both buttons disable themselves (with an explanatory `title` tooltip) when there's nothing to
export: an empty contracts list, or no contract currently selected on the Intelligence screen.

Because `contractType`/`amount`/`summary` on the merged-contracts shape depend on the backend
DTO fields ai-service's `AnalysisResponseDto`/`FinancialExposureDto` expose (see
`ai-service/README.md` and finding #11), both export paths read them with `?? fallback`
defaults (`"Agreement"`, `$0`, `"No summary available."`) so they degrade gracefully rather
than breaking regardless of backend rollout order.

## Build and run

```bash
npm install
npm run dev      # Vite dev server on 5173, proxies /api to localhost:8080
npm run build    # builds index.html and silent-renew.html into dist/
npm test         # Vitest
npm run lint     # oxlint
```

In Docker the app is built into static files and served by nginx on port 5173. `VITE_*` values
are **inlined at build time**, so changing one requires an image rebuild, not a restart. The
port must stay 5173 because auth-service only accepts redirects to `http://localhost:5173/`
and `/silent-renew.html`.

Environment variables are documented in `.env.example`.

## Not clearly established

`src/api/mockData.js` is present but its role in the running application is not clearly
established from the current implementation.

`components/intelligence/RiskSidePanel.jsx` renders `risk.category` and `risk.recommendation`.
The `AnalysisRisk` entity has only `severity` and `description`, so those branches never
receive data.
