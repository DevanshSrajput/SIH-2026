# Officer Console

React 19 + Vite front end for the document screening platform. A thin view over the API —
all screening logic lives in the backend.

## Running

```bash
npm install && npm run dev
```

Serves on <http://localhost:5173> and proxies `/api` to `http://localhost:8080`, so the
browser sees one origin and no CORS preflight is involved. Start the backend first.

```bash
npm run build
```

## Pages

| Route | Purpose |
|---|---|
| `/screen` | Upload a document, optional live capture, optional chip read; shows the verdict and findings |
| `/cases` | Every screening, newest first |
| `/cases/:reference` | Full case: verdict and rationale, extracted identity, module status, findings with evidence, evidence images, ELA heat map, audit trail, officer decision |
| `/watchlist` | Add and deactivate watchlist entries |
| `/dashboard` | Throughput, referral rate, most frequent findings, highest-risk cases |

## Conventions

**Show the reasoning, not just the verdict.** The rationale and each finding's evidence
values are rendered inline rather than hidden behind a click, so an officer can challenge a
recommendation instead of only obeying it.

**Never colour a module green because it finished.** "Ran or not" and "clean or not" are
different questions and are shown as separate statements — a completed module that raised
three findings must not read as reassuring. See `ModuleStatus` in `CaseDetailPage.jsx`.

**Errors surface the server's own message.** Whether a screening failed because the image
was too large or because the service is down demands different responses at the desk.
