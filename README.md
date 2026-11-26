# SMS Gateway (Android)

Stuurt binnenkomende sms'jes als JSON (HTTP POST) door naar één of meer webhooks,
op basis van regels. Plus een heartbeat die op een vast interval laat weten dat de telefoon nog leeft.

## Bouwen
1. Open deze map in Android Studio (Ladybug of nieuwer) en laat Gradle synchroniseren.
2. Build → Build App Bundle(s) / APK(s) → Build APK(s).
3. Installeer de APK op de telefoon.

## Instellen
1. Open de app en geef SMS-toestemming.
   Android 13+ meldt "Beperkte instelling"? Ga naar App-info → ⋮ → "Beperkte instellingen toestaan".
2. Tik op "Batterij-optimalisatie uitzetten".
3. Voeg regels toe, zet "Doorsturen actief" aan en tik op "Test naar alle actieve regels".
4. Optioneel: vul de heartbeat-URL en het interval in en zet de heartbeat aan.

## Regels
Elke regel heeft een eigen webhook (en optionele header) en een van deze voorwaarden:

| Type | Voorbeeldwaarde | Werking |
|---|---|---|
| Alle sms | – | Elke sms |
| Van bepaald nummer / afzender | `0612345678, ING` | Nummers worden in elke notatie herkend (06…, +316…, 00316…); afzendernamen zonder hoofdlettergevoeligheid |
| Bevat bepaald woord | `alarm, code` | De sms bevat een van de woorden (hoofdletters maakt niet uit) |

Een sms gaat naar **elke** actieve regel die past. Zo kun je bijvoorbeeld een regel "Alle sms" naar je logboek sturen
en daarnaast een regel "Bevat: alarm" naar Home Assistant.

### SMS-payload
```json
{
  "type": "sms",
  "from": "+31612345678",
  "message": "Hallo!",
  "timestamp": 1764169200000,
  "received_at": "2026-11-26T16:00:00+01:00",
  "subscription_id": 1,
  "sim_slot": 0,
  "rule": "Bank",
  "device": "Google Pixel 8",
  "test": false
}
```

## Heartbeat
Stuurt elke *N* minuten een POST naar de heartbeat-URL. Werkt goed met bijvoorbeeld
Healthchecks.io, Uptime Kuma (push-monitor) of een Home Assistant-webhook.

```json
{
  "type": "heartbeat",
  "timestamp": 1764169200000,
  "sent_at": "2026-11-26T16:00:00+01:00",
  "interval_minutes": 15,
  "device": "Google Pixel 8",
  "android": "15",
  "battery_level": 87,
  "charging": true,
  "forwarding_enabled": true,
  "active_rules": 2,
  "sms_permission": true
}
```

Let op: in diepe slaapstand (Doze) kan Android korte intervallen (< 15 min) vertragen.
Zet de batterij-optimalisatie uit en houd de telefoon liefst aan de lader.
Stel de monitor aan de serverkant iets ruimer in dan het interval (bv. interval + 5 min).

## Gedrag bij fouten
- Geen netwerk? Het bericht wacht tot er verbinding is.
- Fout of time-out: opnieuw proberen met oplopende wachttijd (max 20 pogingen), ook na een herstart.
- HTTP 4xx (behalve 408/429) wordt niet herhaald.

## Let op
- Google Play staat RECEIVE_SMS niet toe voor dit soort apps, dus installeer de APK zelf.
- https heeft de voorkeur; http werkt ook (handig binnen je eigen netwerk).
