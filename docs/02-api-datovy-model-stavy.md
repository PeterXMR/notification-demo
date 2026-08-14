# 2 · Návrh API, dátového modelu a stavov

## API

Tri endpointy: vytvorenie kampane, stav kampane, overenie doručení.

### Vytvorenie kampane

```
POST /api/campaigns
Content-Type: application/json
```

```json
{
  "subject": "Planned maintenance",
  "message": "The service will be temporarily unavailable.",
  "recipients": ["john@example.com", "jane@example.com"]
}
```

**Odpoveď `202 Accepted`** — prijaté na spracovanie, nedokončené (preto 202, nie 200/201):

```json
{
  "campaignId": "4bce5887-cfe6-4615-a132-f90fde6cf97a",
  "status": "PROCESSING"
}
```

**Chybové odpovede:**

| Kód | Situácia | Telo |
|---|---|---|
| `400 Bad Request` | prázdny predmet/text, predmet nad 255 znakov, prázdny zoznam, neplatný formát e-mailu, e-mail nad 320 znakov, viac než 100 príjemcov | `{"error": "VALIDATION_ERROR", "details": [...]}` |
| `422 Unprocessable Entity` | syntakticky platný vstup, ale príjemca neexistuje v DB alebo nie je aktívny | `{"error": "INVALID_RECIPIENTS", "unknown": [...], "inactive": [...]}` |

Rozhodnutia okolo sporných vstupov (zdôvodnenie v [analýze](01-analyza-a-riesenie.md)):

- `422` je **all-or-nothing**: kampaň sa odmietne celá, nič sa neuloží. Odpoveď
  vymenuje **všetky** problémové adresy naraz — `unknown` aj `inactive` zvlášť —
  takže volajúci opraví žiadosť na jeden pokus.
- **Duplicitné adresy** sa ticho deduplikujú (case-insensitive); `total` v stavovom
  API odráža unikátnych príjemcov.
- Limit 100 príjemcov sa vyhodnocuje na **poslanom zozname pred dedupliáciou**
  (bean validation na DTO) — 101 položiek je odmietnutých aj s duplicitami.
- E-mail s okolitými medzerami je neplatný formát → `400` (bean validation beží
  pred akoukoľvek normalizáciou).

### Zistenie stavu kampane

```
GET /api/campaigns/{campaignId}
```

**Odpoveď `200 OK`** (kedykoľvek počas spracovania aj po ňom):

```json
{
  "campaignId": "4bce5887-cfe6-4615-a132-f90fde6cf97a",
  "status": "PROCESSING",
  "total": 3,
  "sent": 1,
  "failed": 0,
  "remaining": 2
}
```

`404 Not Found` (`{"error": "CAMPAIGN_NOT_FOUND", ...}`) pre neznáme `campaignId`.

| Pole | Výpočet |
|---|---|
| `total` | počet všetkých príjemcov kampane |
| `sent` | `COUNT(status = SENT)` |
| `failed` | `COUNT(status = FAILED)` |
| `remaining` | `COUNT(status IN (PENDING, SENDING))` |
| `status` | `remaining > 0 → PROCESSING`, inak `COMPLETED` |

Stav sa **počíta agregáciou** pri každom volaní — nikde sa neukladá, takže sa nemôže
rozísť s realitou a je správny aj tesne po reštarte.

### Overenie doručení (demo/verifikačný endpoint)

```
GET /api/campaigns/{campaignId}/deliveries
```

**Odpoveď `200 OK`:**

```json
{
  "campaignId": "4bce5887-cfe6-4615-a132-f90fde6cf97a",
  "recipients": [
    {
      "email": "temp-fail@example.com",
      "status": "SENT",
      "attempts": 3,
      "providerCalls": 3,
      "delivered": 1,
      "duplicatesSuppressed": 0
    }
  ],
  "totalDelivered": 1,
  "totalDuplicatesSuppressed": 0
}
```

`404 Not Found` pre neznáme `campaignId`.

| Pole | Význam |
|---|---|
| `attempts` | claim epizódy zaznamenané v DB |
| `providerCalls` | koľkokrát bol provider skutočne zavolaný (vrátane zlyhaní) |
| `delivered` | koľkokrát bola správa fyzicky doručená — **nikdy viac než 1** |
| `duplicatesSuppressed` | opakované doručenia odmietnuté providerom vďaka idempotenčnému kľúču |

Worker odovzdáva providerovi `campaign_recipient.id` ako idempotenčný kľúč, preto pre
každého odoslaného príjemcu platí `delivered == 1` bez ohľadu na počet retry — priamy,
cez API overiteľný dôkaz kritéria „interné opakovanie nespôsobí opakované odoslanie".
Delivery log je in-memory súčasť simulátora (modeluje idempotenčné okno providera),
nie perzistentný aplikačný stav.

### Výpadok databázy (všetky endpointy)

Ak je databáza nedostupná, každý endpoint vracia `503 Service Unavailable` so
stabilným strojovo čitateľným telom — bez detailov drivera či stack trace:

```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "The service is temporarily unable to process requests. Please retry later."
}
```

Ide o dočasný výpadok infraštruktúry, nie chybu aplikácie: `503` hovorí klientom,
load balancerom a retry middleware, aby požiadavku zopakovali neskôr. Pri úplnom
výpadku to zodpovedá aj stavu `/actuator/health` (`DOWN` → 503); rovnaké `503` však
vracia aj prechodné zlyhanie databázy (query timeout, lock konflikt), pri ktorom
health môže hlásiť `UP`. Pokrýva aj zlyhanie spojenia počas commitu; commit
odmietnutý z iného dôvodu než výpadku spojenia zostáva `500` (nebolo by pravda
„skúste znova").

## Dátový model

```
users
  id          UUID PK
  email       VARCHAR(320) UNIQUE NOT NULL   -- lowercase (CHECK constraint)
  active      BOOLEAN NOT NULL

campaign
  id          UUID PK
  subject     VARCHAR(255) NOT NULL
  message     TEXT NOT NULL
  created_at  TIMESTAMPTZ NOT NULL

campaign_recipient
  id               UUID PK                   -- zároveň idempotenčný kľúč pre providera
  campaign_id      UUID NOT NULL FK -> campaign
  user_id          UUID NOT NULL FK -> users
  email            VARCHAR(320) NOT NULL     -- snapshot adresy v čase vytvorenia
  status           VARCHAR(16) NOT NULL      -- PENDING | SENDING | SENT | FAILED
  attempts         INT NOT NULL DEFAULT 0    -- rastie s každým claimom; slúži aj ako fencing token
  next_attempt_at  TIMESTAMPTZ               -- dozretie backoffu (NULL = hneď)
  last_error       VARCHAR(1000)
  updated_at       TIMESTAMPTZ NOT NULL

  UNIQUE (campaign_id, user_id)              -- deduplikácia: používateľ raz na kampaň
  INDEX  (campaign_id, status)               -- agregácia pre stavové API
  INDEX  (status, next_attempt_at)           -- výber práce pre worker/poller
```

Poznámky:

- `campaign_recipient` je zároveň **jednotka práce aj jednotka stavu** — žiadne
  in-memory počítadlá ani fronty, všetko prežije reštart.
- Stav kampane sa **neukladá** — je odvodený agregáciou.
- Používateľov napĺňajú Flyway migrácie — 101 účtov, z toho 5 neaktívnych
  (`inactive@example.com`, `user97@`–`user100@example.com`), vrátane špeciálnych
  adries pre simulátor: `temp-fail@`, `always-fail@`, `bounce@`, `slow@`.

## Stavy

### Stavový automat príjemcu (= jednej notifikácie)

```
                     ┌── úspech ───────────────────► SENT
PENDING ── claim ───► SENDING
   ▲                 ├── trvalá chyba ─────────────► FAILED   (hneď, bez opakovania)
   │                 ├── dočasná chyba ──┬─────────► PENDING  (attempts < max, backoff)
   │                 │                   └─────────► FAILED   (attempts == max)
   │                 └── provider down ────────────► PENDING  (release, attempts - 1)
   └─────────────────────────────────────────────────┘
```

| Stav | Význam | Koncový |
|---|---|---|
| `PENDING` | čaká na spracovanie (aj po dočasnej chybe, s odloženým `next_attempt_at`) | nie |
| `SENDING` | prebraný workerom, prebieha pokus o odoslanie | nie |
| `SENT` | úspešne odoslaný | áno |
| `FAILED` | trvalo odmietnutý, alebo vyčerpaný limit pokusov | áno |

Vlastnosti prechodov:

- Každý prechod je **jeden podmienený UPDATE**. Claim (`WHERE status = 'PENDING' AND
  next_attempt_at dozrel`) pustí z ľubovoľného počtu workerov presne jedného.
- Zápis výsledku je **fencovaný** (`WHERE status = 'SENDING' AND attempts = :fence`):
  `attempts` rastie s každým claimom a funguje ako token vlastníctva epizódy —
  oneskorený worker, ktorého claim medzitým prevzal iný, zapíše 0 riadkov a jeho
  výsledok sa korektne zahodí.
- Dočasná chyba **nie je stav** — je to návrat do `PENDING` s exponenciálnym backoffom
  (`next_attempt_at = now + backoff · 2^(attempts-1)`) a limitom `max-attempts`.
- Výpadok providera (otvorený circuit breaker) je **release**: návrat do `PENDING`
  s `attempts - 1` — výpadok providera nemíňa retry rozpočet adresy.

### Stav kampane (odvodený)

| Stav | Podmienka |
|---|---|
| `PROCESSING` | existuje príjemca v `PENDING` alebo `SENDING` |
| `COMPLETED` | všetci príjemcovia v koncovom stave (`SENT` / `FAILED`) |

Kampaň nemá stav `FAILED` — aj kampaň so zlyhanými príjemcami skončí `COMPLETED`
(spracovanie dobehlo); výsledok je v počtoch `sent` / `failed`.

### Mapovanie stavov na akceptačné kritériá zadania

| Kritérium zadania | Riešenie |
|---|---|
| odpoveď bez čakania, s identifikátorom | `202` + `campaignId` po jednej INSERT transakcii |
| `PROCESSING` a priebežné počty | agregácia `GROUP BY status` nad `campaign_recipient` |
| `COMPLETED` po dokončení | odvodené: žiadny `PENDING`/`SENDING` |
| zlyhanie jedného nezastaví ostatných | zlyhanie je koncový stav jedného riadku |
| dočasná chyba → obmedzený počet opakovaní | slučka `PENDING ↔ SENDING` ohraničená `attempts == max-attempts` → `FAILED` |
| trvalá chyba zaznamenaná ako neúspešná | `FAILED` + `last_error`, bez opakovania |
| po reštarte kampaň nestratená | perzistentný `PENDING` + recovery poller |
| interné opakovanie ≠ opakované odoslanie | CAS claim + fencing token + idempotenčný kľúč na hranici providera; overiteľné cez `GET …/deliveries` (`delivered == 1`) |
