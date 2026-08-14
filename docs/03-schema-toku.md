# 3 · Schéma toku spracovania

## 1. Vytvorenie kampane a asynchrónne odoslanie

```mermaid
sequenceDiagram
    autonumber
    participant K as Klient (Postman)
    participant API as REST Controller
    participant S as CampaignService
    participant DB as PostgreSQL
    participant D as Dispatcher (@Async, AFTER_COMMIT)
    participant W as SendWorker
    participant M as MailSender (simulátor + circuit breaker)

    K->>API: POST /api/campaigns
    API->>S: create(request)
    S->>S: validácia formátu a limitu (400), dedup
    S->>DB: SELECT používatelia podľa e-mailov
    alt neexistujúci / neaktívny príjemca
        S-->>K: 422 INVALID_RECIPIENTS (unknown + inactive, nič sa neuloží)
    else OK
        S->>DB: INSERT campaign + recipients (PENDING) [1 transakcia]
        Note over S,DB: COMMIT — až teraz je kampaň viditeľná
        S-->>K: 202 Accepted {campaignId, PROCESSING}
        S--)D: event CampaignCreated (AFTER_COMMIT)
        D->>W: zaraď príjemcov do executora
        loop pre každého príjemcu (paralelne, malý pool)
            W->>DB: claim: UPDATE → SENDING WHERE status='PENDING' (CAS, attempts+1)
            alt claim vyhral iný worker
                Note over W: 0 riadkov → worker odchádza (žiadny duplicitný pokus)
            else claim úspešný
                W->>M: send(recipientId = idempotenčný kľúč, email, ...)
                alt už doručené (duplicitný pokus)
                    M-->>W: OK (potlačené, delivered ostáva 1)
                    W->>DB: UPDATE → SENT (fence: attempts)
                else úspech
                    M-->>W: OK
                    W->>DB: UPDATE → SENT (fence: attempts)
                else dočasná chyba, attempts < max
                    M-->>W: TransientMailException
                    W->>DB: UPDATE → PENDING (next_attempt_at = backoff, fence)
                else dočasná chyba, attempts == max / trvalá chyba
                    M-->>W: Transient/PermanentMailException
                    W->>DB: UPDATE → FAILED (last_error, fence)
                else circuit breaker otvorený (výpadok providera)
                    M-->>W: MailProviderUnavailableException
                    W->>DB: release → PENDING (attempts - 1, fence)
                end
            end
        end
    end
```

Klient dostáva odpoveď v kroku 8 — **pred** akýmkoľvek pokusom o odoslanie.
Zápisy výsledku sú fencované hodnotou `attempts` z claimu: oneskorený worker
(ktorého claim medzitým prevzal iný) zapíše 0 riadkov a výsledok sa zahodí.

## 2. Zisťovanie stavu (kedykoľvek počas spracovania)

```mermaid
sequenceDiagram
    participant K as Klient (Postman)
    participant API as REST Controller
    participant DB as PostgreSQL

    K->>API: GET /api/campaigns/{id}
    API->>DB: SELECT status, COUNT(*) FROM campaign_recipient<br/>WHERE campaign_id = ? GROUP BY status
    DB-->>API: {PENDING: 2, SENDING: 1, SENT: 5, FAILED: 1}
    API-->>K: 200 {status: PROCESSING, total: 9, sent: 5, failed: 1, remaining: 3}
```

Stav sa **počíta z dát** — žiadne počítadlá v pamäti, odpoveď je správna aj po reštarte.
Rovnakým princípom funguje `GET /api/campaigns/{id}/deliveries` — pridáva pohľad
providera (`providerCalls` / `delivered` / `duplicatesSuppressed`) na overenie, že
nikto nedostal ten istý e-mail dvakrát.

## 3. Zotavenie po reštarte / dočasnej chybe (recovery poller)

```mermaid
sequenceDiagram
    participant P as Recovery poller (@Scheduled)
    participant DB as PostgreSQL
    participant W as SendWorker

    loop každé ~2 s
        P->>DB: UPDATE → PENDING WHERE status='SENDING'<br/>AND updated_at < now() - stuck_timeout
        Note over P,DB: „zaseknuté" SENDING (pád workera) späť do obehu
        P->>DB: SELECT id WHERE status='PENDING'<br/>AND (next_attempt_at IS NULL OR next_attempt_at <= now())
        DB-->>P: dávka dozretých príjemcov
        P->>W: zaraď do executora (každý znova prejde CAS claimom)
    end
```

Dvojité podanie (poller aj dispatcher naraz) je neškodné — CAS claim pustí len jedno
spracovanie. Poller pokrýva tri situácie jedným mechanizmom:

| Situácia | Ako ju poller rieši |
|---|---|
| reštart aplikácie po prijatí kampane | `PENDING` riadky v DB prežili → dokončia sa |
| dočasná chyba s backoffom | `next_attempt_at` dozrel → nový pokus |
| pád workera uprostred odosielania | `SENDING` starší než stuck timeout → späť do `PENDING` |

Asynchrónny dispatch po commite je len **optimalizácia latencie** — **garanciu
dokončenia dáva poller + databáza**.

## 4. Stavový automat príjemcu (súhrn)

```mermaid
stateDiagram-v2
    [*] --> PENDING : vytvorenie kampane
    PENDING --> SENDING : claim (CAS UPDATE, attempts + 1)
    SENDING --> SENT : úspech (alebo potlačený duplikát)
    SENDING --> FAILED : trvalá chyba (hneď)
    SENDING --> PENDING : dočasná chyba, attempts < max (backoff)
    SENDING --> FAILED : dočasná chyba, attempts == max
    SENDING --> PENDING : provider down (release, attempts - 1)
    SENDING --> PENDING : pád workera (stuck reset pollerom)
    SENT --> [*]
    FAILED --> [*]
```

Kampaň: `PROCESSING`, kým existuje `PENDING`/`SENDING` príjemca; potom `COMPLETED`.
