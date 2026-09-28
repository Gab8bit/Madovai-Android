# Madovai per Android

Client Android nativo (Kotlin + Jetpack Compose, Material 3 / Material You, Android 8.0+) per bus/treni Cotral (extraurbano Lazio) **e** bus/tram/metro Atac + Roma TPL (urbano Roma), su un'unica mappa. Nome dell'app: **Madovai** ("ma dove vai?"), package / application id `dev.gab8bit.madovai` — lo stesso bundle id dell'app iOS.

È il port Android dell'app iOS: stesse due pipeline di dati, stesso comportamento, stessa logica di fallback — riscritta da zero in Kotlin, non un wrapper. Come su iOS, l'app parla **direttamente** con l'endpoint interno di Cotral (lo stesso che [`ChromuSx/cotral`](https://github.com/ChromuSx/cotral) proxava da un server Node), con il servizio orari di ASTRAL per i treni e con i feed pubblici GTFS/GTFS-Realtime — non c'è nessun backend da self-hostare.

Pagina di presentazione e download: **https://gab8bit.github.io/Madovai-Android/** · APK nelle [Release](https://github.com/Gab8bit/Madovai-Android/releases).

> **Versione iOS.** Il progetto gemello per iPhone (SwiftUI) è su [github.com/Gab8bit/Madovai-iOS](https://github.com/Gab8bit/Madovai-iOS), con la sua pagina su [gab8bit.github.io/Madovai-iOS](https://gab8bit.github.io/Madovai-iOS/). L'app iOS è il riferimento: questa versione Android ne rispecchia i modelli e la logica (molti file citano il loro equivalente Swift nei commenti). Le descrizioni dettagliate delle stranezze dei dati Cotral/Atac (campi XML, copertura dei veicoli, metro senza tempo reale, ecc.) sono nel README iOS e valgono identiche anche qui.

## Come funziona

### Cotral (extraurbano Lazio)
- **Dati statici (fermate/paline/linee, ricerca):** scaricati una tantum dai feed GTFS pubblici di Cotral — bus (`GTFS_COTRAL.zip`, ~4000 linee) e ferro (`GTFS_FERRO.zip`: Metromare/Roma-Lido, Roma-Viterbo) — estratti in streaming e parsati on-device, poi tenuti in cache nella memoria privata dell'app (`data/gtfs/CotralGtfsStore.kt`, `GtfsIo.kt`).
- **Dati realtime (transiti a una palina, posizione bus):** chiamate dirette all'endpoint interno `PIV.do`/`Automezzi.do` di Cotral, che risponde in XML (`net/CotralClients.kt`, parser XML leggero in `net/XmlNode.kt`, repository in `data/cotral/CotralRepositories.kt`).
- **Copertura veicoli: parziale, per costruzione.** Non esiste un endpoint "tutti i bus Cotral": un veicolo diventa visibile solo interrogando i transiti di una palina e trovando una corsa monitorata con un `vehicleCode`. L'app interroga quindi le paline visibili sullo schermo, con debounce sul pan/zoom (`service/CotralViewportVehicleService.kt`). Il pulsante "Segui" su un transito aggancia quel singolo mezzo e ne aggiorna la posizione ogni 12 s (`service/VehicleTracker.kt`), con un banner esplicito se il tracking si interrompe.

### Treni Cotral: ASTRAL, poi orario statico
Le 3 linee ferroviarie (Metromare, Roma-Viterbo urbana, Roma-Viterbo extraurbana) usano come fonte primaria l'API pubblica di **ASTRAL** (`gestionecorse.astralspa.it/api`, JSON via POST, nessuna autenticazione): elenco stazioni per percorso+direzione e passaggi del giorno per stazione, con ritardo, corse soppresse e bus sostitutivi (`AstralTrainClient` in `net/CotralClients.kt`, modello `CotralTrainRoute` in `model/Cotral.kt`). ASTRAL, GTFS e PIV.do usano tre spazi di id diversi: l'aggancio stazione GTFS ↔ stazione ASTRAL è per nome (vedi `ui/sheets/PoleDetailState.kt`). Se ASTRAL non risponde, la scheda cade sull'orario statico da `stop_times.txt` + `calendar.txt` del feed ferroviario, etichettato onestamente come "da tabella, non in tempo reale".

### Atac + Roma TPL (urbano Roma)
- **Dati statici:** feed GTFS combinato di Roma Servizi per la Mobilità (licenza CC-BY 3.0 Italia), incluse le linee metro A/B/C. `stop_times.txt` (~240 MB / ~5,1 milioni di righe) viene letto **in streaming a livello di byte**, solo per costruire l'appartenenza esatta linea↔fermate: non viene mai caricato come stringa unica né tenuto in memoria per intero (`data/atac/AtacGtfsStore.kt`). Il manifest dichiara comunque `android:largeHeap="true"` per stare larghi sui telefoni con heap piccolo.
- **Dati realtime:** i due feed GTFS-Realtime (vehicle positions + trip updates) in Protocol Buffers, decodificati con i binding Java ufficiali di MobilityData (`data/atac/AtacRealtimeService.kt`). Un'unica chiamata restituisce l'intera flotta attiva, quindi tutti i mezzi Atac/Roma TPL sono sempre sulla mappa (filtrati al riquadro visibile).
- **Metro senza tempo reale** e **transiti per fermata solo da `trip_updates`**: stesse scelte (e stessi motivi) dell'app iOS.

### Mappa
[osmdroid](https://github.com/osmdroid/osmdroid) con tile OpenStreetMap al posto di Google Maps (`ui/map/OsmMap.kt`). Scelta voluta: **nessuna API key, nessun account Google Cloud, nessuna dipendenza dai Google Play Services** — l'APK funziona anche su telefoni senza Play Store. Come su iOS (dove MapKit non espone il layer trasporti), il "layer trasporti pubblici" è ricostruito a mano: tile attenuate (in scuro, grigi invertiti), percorsi delle linee da `shapes.txt`, fermate Atac, paline Cotral, veicoli live con animazione di spostamento, tutto limitato al riquadro visibile. Le richieste alle tile si identificano con uno User-Agent dedicato (`Config.OSM_USER_AGENT`) come richiesto dalla [policy di uso delle tile OSM](https://operations.osmfoundation.org/policies/tiles/), e la cache delle tile sta nella cache privata dell'app (nessun permesso di storage).

### Interfaccia
- **Barra di navigazione** Material 3 con tre schede: **Mappa**, **Linee**, **Preferiti** (`MainActivity.kt`).
- **Mappa:** ricerca unica su entrambe le fonti (linee e fermate), pulsante posizione, banner di caricamento. Toccare una fermata/palina/mezzo apre un **`ModalBottomSheet` M3** con transiti/partenze (`ui/sheets/`), aggiornato ogni 15 s solo mentre è visibile.
- **Isolare una linea:** toccare un veicolo o una linea filtra la mappa a quella sola linea (percorso, mezzi, fermate) finché non premi "Reset", come su iOS.
- **Linee:** elenco senza mappa di treni (metro Atac + treni Cotral) e bus/tram Atac/Roma TPL, con ricerca e dettaglio linea. I ~4000 bus Cotral sono esclusi di proposito, come su iOS.
- **Preferiti:** fermate salvate, Cotral e Atac nella stessa lista, persistite localmente con Jetpack DataStore (`service/FavoritesStore.kt`).
- **Material You:** su Android 12+ lo schema colori è dinamico (dallo sfondo del telefono), altrimenti un tema chiaro/scuro derivato dal blu dell'app iOS `#1E5EA8`. I colori con significato (Cotral arancio, Atac teal, metro/tram/treno, ritardo/anticipo) sono fissi e non seguono il tema (`ui/theme/Theme.kt`).
- **Caricamento non bloccante:** i due dataset statici si scaricano in background all'avvio, indipendenti tra loro; la mappa è subito usabile e un banner mostra avanzamento/errore/"Riprova" per ciascuna fonte.
- **Posizione:** permesso runtime standard (`ACCESS_FINE`/`COARSE_LOCATION`), facoltativo — senza, l'app funziona lo stesso.

Tutta la configurazione (host, id client condiviso Cotral, URL dei feed, intervalli di polling) è in [`app/src/main/java/dev/gab8bit/madovai/Config.kt`](app/src/main/java/dev/gab8bit/madovai/Config.kt), valore per valore uguale a `Config.swift` di iOS.

## Stack tecnico

Dal `app/build.gradle.kts` (AGP 8.7.3, Kotlin 2.1.0, Gradle wrapper 8.11.1, JDK 17, `compileSdk`/`targetSdk` 35, `minSdk` 26):

| Area | Libreria |
|---|---|
| UI | Jetpack Compose (BOM 2024.12.01), Material 3, material-icons-extended |
| Navigazione | navigation-compose 2.8.5 |
| Activity/lifecycle | activity-compose 1.9.3, lifecycle-runtime-compose 2.8.7, core-ktx 1.15.0 |
| Persistenza | datastore-preferences 1.1.1 (preferiti) |
| Concorrenza | kotlinx-coroutines-android 1.9.0 |
| Rete | OkHttp 4.12.0 |
| Mappa | osmdroid-android 6.1.20 |
| GTFS-Realtime | org.mobilitydata:gtfs-realtime-bindings 0.2.0 |
| Test | JUnit 4 + org.json/kxml2 per far girare il data layer sulla JVM |

Nessun Google Play Services, nessuna libreria di analytics, nessun backend.

## Build

Serve solo la riga di comando, niente Android Studio. Una tantum (macOS, Homebrew):

```bash
brew install --cask android-commandlinetools
brew install openjdk@17

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-35" "build-tools;35.0.0" "build-tools;34.0.0"
```

(`build-tools;34.0.0` è la versione di default di AGP 8.7.3: se manca Gradle la scarica da solo, a patto che le licenze siano già state accettate.)

Poi:

```bash
./build_apk.sh
# → app/build/outputs/apk/debug/app-debug.apk
```

`build_apk.sh` imposta `JAVA_HOME` (default `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`, sovrascrivibile con `JAVA_HOME_17`) e `ANDROID_HOME` (default `/opt/homebrew/share/android-commandlinetools`) solo per la build — il `java` di sistema non viene toccato — crea `local.properties` se manca e lancia `./gradlew assembleDebug`. Il wrapper Gradle scarica da sé Gradle 8.11.1: **non** serve il formula `gradle` di Homebrew. AGP 8.x richiede JDK 17 (con JDK più recenti la build può fallire).

**Test end-to-end sui feed reali (opzionale):** `./gradlew testDebugUnitTest -PliveTests` fa girare il vero data layer (GTFS Cotral + Atac, PIV.do, ASTRAL, GTFS-RT) sulla JVM, senza emulatore. È opt-in perché scarica ~60 MB di GTFS (~300 MB una volta estratti, in `app/build/live-gtfs`, riusati nelle esecuzioni successive).

**Firma e aggiornamenti:** l'APK pubblicato è una build *debug*, firmata con la chiave di debug locale (`~/.android/debug.keystore`, generata dalla prima build). Android installa un aggiornamento sopra una versione esistente **solo se la firma è la stessa**: se quel keystore va perso, la release successiva sarà firmata con una chiave diversa e chi l'ha installata dovrà disinstallare (perdendo i preferiti) prima di reinstallare. Conservare `~/.android/debug.keystore`, o passare a un keystore di release dedicato prima della prossima versione.

## Problemi incontrati (da sapere se ci rimetti le mani)

### ASTRAL: catena di certificati incompleta → `network_security_config.xml`
`gestionecorse.astralspa.it` invia **solo il certificato foglia**, senza l'intermedio "Sectigo Public Server Authentication CA OV R36". iOS/macOS (e i browser) scaricano da soli l'intermedio mancante tramite l'URL AIA del certificato, quindi su iOS funzionava tutto; **Android non fa il fetch AIA**, e ogni chiamata ad ASTRAL falliva con un errore TLS di "trust anchor not found". Soluzione, senza disattivare nessuna verifica: l'intermedio pubblico autentico (scaricato dall'URL AIA del certificato stesso di ASTRAL, valido fino al 2036) è incluso in `app/src/main/res/raw/astral_sectigo_ov_r36.pem` e aggiunto come trust anchor **solo per quell'host** in [`res/xml/network_security_config.xml`](app/src/main/res/xml/network_security_config.xml), insieme a quelli di sistema. Hostname, scadenza e firma continuano a essere verificati normalmente. Se ASTRAL un giorno sistema la catena, il file resta innocuo; se cambia CA, va aggiornato il `.pem`. Il test live (`LiveFeedsSmokeTest`) ricostruisce lo stesso trust store sulla JVM per verificare la cosa senza emulatore.

### Cotral in HTTP semplice (cleartext solo per un host)
Come su iOS, i dati live e i GTFS di Cotral stanno su `http://travel.mob.cotralspa.it:7777` (la porta HTTPS 4443 ha un certificato il cui SAN non copre l'host). Da Android 9 il cleartext è vietato di default: lo stesso `network_security_config.xml` lo consente **solo per `travel.mob.cotralspa.it`** (`cleartextTrafficPermitted="false"` resta la base per tutto il resto), l'equivalente dell'eccezione ATS dell'app iOS.

### Rete sul main thread (`NetworkOnMainThreadException`)
La prima versione avvolgeva `enqueue()` di OkHttp in una coroutine (`Call.await()` in `net/Http.kt`) e leggeva il body dopo: ma `await()` riprende sul dispatcher del chiamante, cioè spesso il main thread dell'UI, e la **lettura del body** (`body.bytes()`/`string()`) è I/O di rete a tutti gli effetti — Android lancia `NetworkOnMainThreadException` e la chiamata fallisce. Corretto facendo girare l'intero scambio, lettura del body inclusa, dentro `withContext(Dispatchers.IO)` (`getBytes` in `net/Http.kt`, `postArray` di `AstralTrainClient`); il parsing XML gira poi su `Dispatchers.Default`. Se aggiungi una nuova chiamata di rete, passa da questi helper.

### Regex: Android (ICU) rifiuta il flag `(?U)`
Per ripulire nomi di fermate/linee dal suffisso ` #codice` il server Node di riferimento usa ` #\s*\w+$`; sulla JVM desktop l'equivalente Unicode-aware si scrive con `(?U)` (`UNICODE_CHARACTER_CLASS`), ma il motore regex di Android è basato su ICU e **quel flag non è supportato** (`PatternSyntaxException` sul dispositivo, mentre sulla JVM desktop dei test funziona). Corretto usando una classe di caratteri Unicode esplicita, `" #\\s*[\\p{L}\\p{N}_]+$"` (`GtfsTextUtils` in `data/gtfs/GtfsIo.kt`). Morale: una regex che funziona nei test JVM non è garantito che funzioni su Android — evitare i flag inline "esotici".

### Coordinate Cotral invertite
`PIV.do cmd=1` restituisce per molte paline latitudine e longitudine scambiate. Nel Lazio la latitudine è ~41–43 e la longitudine ~11–14, quindi se la "latitudine" è sotto 20 i due valori vengono scambiati (`CotralTimeUtils.normalizeLatLon`), come sull'app iOS.

## Crediti dati
- Fermate e linee: feed GTFS pubblici di Cotral e di Roma Servizi per la Mobilità (CC-BY 3.0 Italia) per Atac/Roma TPL.
- Tempo reale: endpoint pubblici di Cotral, API orari di ASTRAL, feed GTFS-Realtime di Roma Servizi per la Mobilità.
- Mappa: © OpenStreetMap contributors (ODbL), via osmdroid.

Progetto indipendente, non affiliato con Cotral, ASTRAL o Roma Capitale/Atac.
