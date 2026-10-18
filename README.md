# Entradio Tisk – náhrada QZ Tray pro Sunmi D3 mini

Android aplikace, která se pro webovou pokladnu (entradio.cz) tváří jako **QZ Tray**.
Pokladna v Chromu zavolá `qz.print(...)` stejně jako na počítači a vstupenka se **bez dialogu**
vytiskne na **vestavěnou termotiskárnu Sunmi**. Web se nemusí měnit.

```
Chrome (entradio.cz) ──qz-tray.js──▶ ws://localhost:8182 / wss://localhost:8181
                                         │  (aplikace Entradio Tisk)
                                         ▼
                        PDF / HTML / obrázek / ESC/POS → černobílá bitmapa 576 bodů
                                         ▼
                              Sunmi printer service → tisk + ustřižení
```

## Co aplikace umí

| QZ volání | Chování |
|---|---|
| `qz.websocket.connect()` | přijme připojení (jen z tohoto zařízení), podpis/certifikát webu ignoruje |
| `qz.api.getVersion()` | vrací `2.2.4` |
| `qz.printers.find()` / `getDefault()` / `details()` | vrací „SUNMI vestavěná tiskárna“; na `find("cokoli")` odpoví tím jménem (lze vypnout) |
| `qz.print(config, data)` | `pixel`/`raw` + `pdf`, `image`, `html` (base64 / file URL / plain) a `command` (plain / base64 / hex) |
| volba `copies` | počet kopií |

- Každá **stránka PDF = jedna vstupenka**, za každou se posune papír a ustřihne (nastavitelné).
- Prázdné místo na konci stránky se ořízne (šablona má `@page { size: 80mm auto }`, takže to sedí).
- **Log v aplikaci ukazuje přesně, co web posílá** (typ, formát, flavor, URL/velikost dat).
  Tím zjistíte, jak entradio volá QZ, bez programátorů.

## Sestavení APK

Vyberte jednu z možností:

**A) GitHub (bez instalace čehokoli)**
1. Vytvořte na GitHubu **soukromý** repozitář a nahrajte do něj obsah této složky.
2. Záložka **Actions** → workflow „Sestavit APK“ se spustí sám (nebo ho spusťte ručně).
3. Po doběhnutí (~5 min) stáhněte artefakt `entradio-tisk-apk` → uvnitř je `app-release.apk`.

**B) Android Studio**
Otevřete složku v Android Studiu → *Build → Build APK(s)*.

## Instalace na D3 mini

1. APK přeneste na zařízení (USB, e-mail, odkaz) a nainstalujte (povolte instalaci z neznámých zdrojů).
2. Spusťte **Entradio Tisk** a povolte oznámení. Stav musí ukazovat ✔ u služby i tiskárny.
3. Klikněte na **Testovací tisk**, měl by vyjet testovací lístek.
4. Doporučeno: **Výjimka z úspory baterie** → Entradio Tisk → Nepovolovat optimalizaci.
5. Aplikace se po restartu zařízení spouští sama.

## Zabezpečené spojení (wss) – důležité

Na stránce **https** se `qz-tray.js` ve výchozím stavu připojuje jen přes **wss://localhost:8181**.
Na počítači to řeší QZ Tray instalací vlastního certifikátu; tady je potřeba udělat to jednou ručně:

1. V aplikaci **Uložit certifikát** → soubor `entradio-tisk-ca.crt` se uloží do Stažených souborů.
2. **Nastavení → Zabezpečení → Šifrování a pověření → Instalovat certifikát → Certifikát CA** → vyberte soubor.
3. Restartujte Chrome (zavřít z přehledu aplikací).

Certifikát se generuje na každém zařízení zvlášť a je technicky omezen (Name Constraints) jen na
`localhost` / `127.0.0.1`, takže neumožňuje podvrhnout žádný jiný web.

**Alternativa bez certifikátu:** programátoři entradio mohou při připojení na tomto zařízení použít
`qz.websocket.connect({ usingSecure: false })`. Chrome povoluje `ws://localhost` i z https stránky.

Novější Chrome se při prvním připojení může zeptat na **přístup k místní síti / zařízením**, je potřeba povolit.

## Nastavení

- Ustřihnout papír za každou vstupenkou (pokud D3 mini nemá řezačku, vypněte, v logu uvidíte chybu)
- Posun papíru za vstupenkou (řádky)
- Práh černé (při bledém tisku zvyšte)
- Povolené domény, např. `entradio.cz` (doporučeno, jinak by mohl tisknout jakýkoli web otevřený v Chromu)

## Ladění

- Log v aplikaci: řádky `Volání: print …` ukazují parametry z webu.
- `adb logcat -s EntradioTisk` pro detailní log.
- Když web hlásí „Unable to establish connection with QZ Tray“: zkontrolujte ✔ u wss a nainstalovaný certifikát
  (v Chromu otevřete `https://localhost:8181`, nesmí se objevit varování o certifikátu).

## Struktura

```
app/src/main/java/cz/cliqplace/printbridge/
  QzServer.kt      – protokol QZ Tray (WebSocket)
  PrintEngine.kt   – dekódování dat z qz.print()
  Renderers.kt     – PDF/HTML/obrázek → černobílá bitmapa
  SunmiPrinter.kt  – vestavěná tiskárna Sunmi (printerlibrary)
  CertManager.kt   – lokální CA a certifikát pro wss://localhost
  BridgeService.kt – služba na popředí (porty 8181 a 8182)
  MainActivity.kt  – obrazovka se stavem, nastavením a logem
```

Soubor `app/signing.keystore` (heslo `entradio`) slouží k podpisu, aby šly aktualizace instalovat přes
starou verzi. Repozitář držte soukromý.
