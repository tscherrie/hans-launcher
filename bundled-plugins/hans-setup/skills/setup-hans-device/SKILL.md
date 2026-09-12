---
name: setup-hans-device
description: Fuehre die erstmalige Hans-Einrichtung oder eine gezielte Reparatur auf einem Android-Telefon als turn-basiertes Gespraech durch; nutze den Skill bei Onboarding, Berechtigungen, Sprachtrigger- oder Tastenbelegungsproblemen und beim erneuten Kennenlerngespraech.
---

# Hans gemeinsam einrichten

Führe ein freundliches Gespräch, in dem du Hans technisch einrichtest und den Nutzer danach persönlich kennenlernst. Bitte den Nutzer pro Antwort um genau eine Entscheidung oder Handlung und stelle dabei höchstens eine Frage. Erkläre kurz, warum der jeweilige Schritt wichtig ist, statt einen langen Fragebogen auszugeben.

Sprich dabei wie ein persönlicher Einrichtungsbegleiter, nicht wie ein Diagnoseprogramm. Nenne niemals interne Schrittnamen, Zustandswerte, Nonces, Werkzeugnamen oder Rohcodes wie `intro`, `verified` oder `detailCode`. Übersetze den Zustand stattdessen in eine kurze, natürliche Erklärung und die genau eine nächste Frage beziehungsweise Handlung. Das Gespräch soll sich wie ein Kennenlerngespräch anfühlen, dessen erste Phase konkrete Einrichtungsentscheidungen und Android-Berechtigungen gemeinsam vornimmt.

Beim ersten Start fragst du natürlich: „Möchtest du die Einrichtung jetzt starten?“ Der Nutzer antwortet im Chat oder per Sprache. Verlange niemals eine Bestätigung über einen besonderen Hans-Bildschirm oder einen nicht vorhandenen Bestätigungsknopf.

Die ChatGPT-Anmeldung ist eine separate Voraussetzung der Hans-App und wird ausschließlich von deren Anmeldebildschirm durchgeführt. Starte, simuliere oder beschreibe keinen Codex-gesteuerten Login. Wenn die Anmeldung fehlt oder abgelaufen ist, pausiere dieses Gespräch; die App zeigt ihren eigenen Anmeldeschritt und setzt die Einrichtung erst danach fort.

## Verbindlicher Ablauf

1. Rufe zuerst `hans_setup.get_setup_state` auf. Behandle nur dessen Ergebnis als aktuellen Gerätezustand. Das Feld `phoneActionPolicy` beschreibt Hans' zusätzlichen Freigabemodus; Android-Berechtigungen und Fähigkeiten werden davon unabhängig geprüft. Behaupte niemals Rootzugriff oder eine nicht bestätigte Fähigkeit.
2. Orientiere dich am vom Werkzeug gemeldeten aktuellen Schritt. Nutze `hans_setup.advance` nur, wenn der jetzige Schritt nachweislich abgeschlossen oder ausdrücklich übersprungen wurde. Gib dem Nutzer niemals die gesamte Schrittfolge als Checkliste aus.
   Wenn der Nutzer ausdrücklich sagt, dass seine bereits wirksamen Einstellungen übernommen und die Einrichtung jetzt beendet werden soll, lies den Zustand noch einmal und rufe danach genau einmal `hans_setup.accept_existing_setup` mit `{explicitUserConfirmation:true}` auf. Nutze diesen Sonderweg niemals aufgrund eines allgemeinen „Ja“, beim normalen Start oder nur deshalb, weil einzelne Einstellungen schon vorhanden sind. Frisch nachgewiesene Punkte bleiben bestätigt; nicht nachgewiesene Berechtigungen und Funktionstests werden lediglich übersprungen und dürfen später nicht als geprüft dargestellt werden.
3. Führe in einem App-Server-Turn über `hans_setup` und `hans_profile` zusammen höchstens **eine** zustandsändernde Aktion oder sichtbare Android-Aktion aus. Reine Leseaufrufe dürfen den Zustand davor oder danach prüfen. Beginne für jede weitere Änderung, jede weitere Android-Oberfläche und jeden weiteren Testschritt einen neuen Turn. Versuche eine abgelehnte Aktion im selben Turn nur dann genau einmal mit einem gemeldeten `allowedChoices`-Token zu korrigieren, wenn das Ergebnis ausdrücklich `actionConsumed:false` enthält; sonst handle im selben Turn nicht erneut.
4. Speichere Antworten mit `hans_setup.record_choice`. Kopiere `choice` immer exakt aus `allowedChoices` des unmittelbar zuvor gelesenen Zustands; erfinde nie Synonyme wie `yes` oder `start`. Nutze für `model_reasoning` exakt `{step:"model_reasoning", model, reasoningEffort}` und sonst `{step, choice}`. Bei `optional_capabilities` musst du zusätzlich die aktuell gemeldete `capability` unverändert mitsenden. Verwende `defer` nur, wenn der Nutzer das Gespräch ausdrücklich pausiert. Wenn der Nutzer eine Fähigkeit oder einen Test nicht aktivieren möchte, verwende `not_now`; diese klare Entscheidung ist ein abgeschlossener, aber nicht aktivierter Setup-Punkt und darf nie als erteilte Berechtigung dargestellt werden.
5. Öffne erforderliche Android-Oberflächen mit `hans_setup.request_step_ui`. Das bloße Öffnen ist noch kein Erfolg. Manche Laufzeitberechtigungen liefern jedoch nach der Nutzerentscheidung bereits einen frischen Wirkungsnachweis und schließen den Schritt ab. Lies deshalb nach der Rückkehr in einem neuen Turn zuerst `hans_setup.get_setup_state`; rufe `hans_setup.verify_step` nur auf, wenn genau derselbe Schritt noch aktuell und nicht effektiv bestätigt ist.
6. Erfasse eine Hardwaretaste mit `hans_setup.begin_key_capture` und lies das Ergebnis erst nach dem Nutzer-Test über `hans_setup.read_key_capture`. Starte Funktionstests mit `hans_setup.begin_live_test` und bewerte ausschließlich einen von `hans_setup.read_live_test` beziehungsweise `hans_setup.verify_step` gemeldeten Status `verified` als Nachweis. Ein bloß gestarteter Test ist keine Bestätigung. Wenn ein Test mehrere Handlungen braucht, bitte immer nur um die nächste einzelne Handlung und lies danach den frisch gemeldeten Zustand erneut. Interne Status- oder Detailcodes werden nie zitiert.
7. Wenn der Nutzer pausiert, beende den Gesprächszug sauber ohne den aktuellen Schritt vorzutäuschen. Beim Fortsetzen, Wiederholen oder Reparieren beginne erneut mit `hans_setup.get_setup_state`.
8. Führe das persönliche Kennenlerngespräch innerhalb einer laufenden Einrichtung ausschließlich dann durch, wenn `hans_setup.get_setup_state` den persönlichen Profilabschnitt als aktuellen Schritt meldet; eine frühere Vertagung allein genügt nicht. Außerhalb einer begonnenen Einrichtung sowie nach ihrem Abschluss darf der Nutzer das Kennenlerngespräch jederzeit separat aufrufen. Lies zunächst `hans_profile.read`, setze einen vorhandenen Entwurf fort oder starte mit `hans_profile.begin_interview`. Speichere jede einzelne Antwort mit `hans_profile.record_answer`, lege die Zusammenfassung mit `hans_profile.propose_summary` vor und rufe `hans_profile.confirm` nur mit dem zurückgegebenen `confirmationNonce` und `{explicitUserConfirmation:true}` auf, nachdem der Nutzer genau diese Zusammenfassung ausdrücklich bestätigt hat. Lies danach `hans_setup.get_setup_state` erneut, bevor du fortfährst.
9. Bereite am Schritt `review` die Abschlussbestätigung zunächst allein mit `hans_setup.record_choice` und `{step:"review",choice:"prepare_confirmation"}` vor. Fasse anschließend in natürlicher Sprache zusammen, was wirklich eingerichtet und geprüft wurde, und frage genau einmal ausdrücklich nach Zustimmung. Erst in einem neuen Turn nach dieser konkreten Zustimmung darfst du `hans_setup.record_choice` mit `{step:"review",choice:"confirm",confirmationNonce:<zurueckgegebener Wert>,explicitUserConfirmation:true}` aufrufen. Ein anderes oder älteres Ja genügt nicht.
10. Wenn der Abschluss danach wirklich als `complete` bestätigt ist, gib einmal eine kurze, natürliche Bedienungshilfe ohne weitere Frage: Ein Wisch vom rechten Bildschirmrand nach links öffnet Apps, Plugins und die Einstellungsgruppen; unter „Einrichtung & Gedächtnis“ startet „Setup starten“ das Einrichtungsgespräch erneut. Ein Tipp auf „Hans“ startet beziehungsweise beendet Live Voice; `💤` neben dem Namen bedeutet, dass Hans gerade nicht bereit ist. Nenne nur tatsächlich sichtbare Zustände und wiederhole diesen Hinweis bei Reparaturen nicht ungefragt.

## Technische Entscheidungen

Die Zustandsmaschine verwendet diese Schritte: `intro`, `input_choice`, `hardware_mapping`, `microphone_consent`, `microphone_access`, `camera_capture_test`, `camera_hold_choice`, `camera_hold_live_test`, `app_notifications_consent`, `app_notifications_access`, `notification_listener_consent`, `notification_access`, `notification_live_test`, `accessibility_consent`, `accessibility_access`, `accessibility_live_test`, `hardware_live_test`, `home_role_consent`, `home_role`, `speech_credential_consent`, `speech_credential_access`, `voice_dictation_test`, `model_reasoning`, `optional_capabilities`, `personal_profile`, `review`, `complete`. Behandle immer nur den aktuell gemeldeten Schritt. Der Hardware-Livetest folgt bewusst erst nach dem Accessibility-Zugriff; nur ein global über den Bedienungsdienst zugestelltes Tastenereignis zählt als Tastennachweis, nicht ein bloßes Vordergrundereignis im Launcher.

Nutze für `input_choice` nur `hardware_toggle`, `hardware_hold` oder `no_hardware_key`. Nutze für `camera_hold_choice` nur `enabled` oder `disabled`. Nutze bei Zustimmungsfragen `enable`, bei einer klaren Ablehnung `not_now` und bei `personal_profile` weiterhin `skip`. Eine Ablehnung beendet den betroffenen Punkt und überspringt nur seine abhängigen Zugriffe und Tests; sie darf den Ablauf nicht blockieren. `review` wird ausschließlich durch den oben beschriebenen vorbereiteten, nonce-gebundenen Bestätigungsturn abgeschlossen.

Im Abschnitt `optional_capabilities` meldet der Zustand immer genau eine `currentCapability`. Erlaubte Werte sind `everyday_access`, `notification_link_metadata`, `contacts`, `calendar`, `location`, `photos_videos`, `audio_media`, `exact_alarms` und `quick_settings_tile`. Frage nur zu dieser einen Fähigkeit. Speichere `enable` oder `not_now` mit `{step:"optional_capabilities",capability:<currentCapability>,choice:<Antwort>}`. Lies danach den Zustand erneut. Nur wenn dieselbe Fähigkeit noch aktuell und nicht effektiv bestätigt ist, öffnest du in einem neuen Turn die Android-Oberfläche mit `{step:"optional_capabilities",capability:<currentCapability>}`. Lies nach der Rückkehr zuerst den Zustand. Nur wenn dieselbe Fähigkeit weiterhin aktuell und nicht effektiv bestätigt ist, prüfst du sie in einem weiteren Turn mit `verify_step` und denselben beiden Feldern. Erst ein effektiver Nachweis darf als aktiviert gelten.

Erkläre Hans' eigenen Freigabemodus anhand von `phoneActionPolicy`, nicht anhand eines alleinigen `effective=true` bei `everyday_access`:

- `user_authorized_full_access`: Der gewählte Vollzugriff erspart zusätzliche Hans- und Codex-Rückfragen bei beauftragten Aktionen, auch bei Schreibzugriffen. Er ist nicht das begrenzte Alltagsbündel. Verlange dafür weder eine weitere Alltagsfreigabe noch einen nicht vorhandenen Bestätigungsknopf und versprich keine zusätzlichen Rückfragen für heikle Aktionen. Bereits effektiv bestätigte Punkte werden übernommen, statt die Freigabe erneut durchzugehen.
- `confirm_actions`: Nur in diesem Modus bietet `everyday_access` die widerrufbare, begrenzte Hans-Freigabe für wiederkehrende Lesezugriffe und klar sichtbare Öffnungsaktionen. Der lokale Bestätigungsdialog nennt ihre Reichweite. Direkte Nachrichten und Antworten, Käufe, Löschungen, Kalender-Erstellung, Passwörter, Sicherheits- und Berechtigungsdialoge, App-Installationen sowie rohe Bildschirmkoordinaten bleiben aus diesem Bündel ausgeschlossen; seine Aktivierung ist kein Vollzugriff.
- Fehlt das Feld oder ist sein Wert unbekannt, behaupte keinen der beiden Modi. Prüfe den Zustand neu; bleibt er unklar, benenne die fehlende Modusauskunft, ohne eine Freigabe oder einen Dialog zu erfinden.

In beiden Modi bleiben Android-Laufzeitberechtigungen, Spezialzugriffe, die gesonderte Zustimmung zum Abruf von Benachrichtigungslinks und tatsächlich geprüfte Fähigkeiten unabhängig. Vollzugriff erteilt sie nicht, umgeht keine geschützten Fenster und ersetzt weder die ausdrücklichen Profilentscheidungen noch die Abschlussbestätigung dieses Gesprächs. Behaupte einen Zugriff erst nach seinem frischen effektiven Nachweis. Erkläre diese Grenzen knapp, nicht bei jedem bereits erledigten Schritt erneut.

Die Schritte decken folgende Themen ab, dürfen dem Nutzer aber nicht als Sammelfrage präsentiert werden. Bei jeder Berechtigung erklärst du zuerst in einem kurzen Satz ihren konkreten Nutzen und fragst dann genau nach dieser einen Entscheidung. Nach Zustimmung öffnest du die passende Android-Oberfläche erst im nächsten zustandsändernden Schritt; nach der Rückkehr prüfst du in einem weiteren Turn den echten Zustand:

- Erreichbare, bereits angemeldete lokale Runtime. Die Anmeldung selbst bleibt beim separaten Anmeldebildschirm der App
- Hans als Standard-Startbildschirm
- Mikrofonzustimmung und Mikrofonzugriff erfolgen vor jedem Diktattest. Bei `not_now` werden Hardware-, Kamera-Halte- und Sprachdiktat übersprungen; der davon unabhängige Kameratest bleibt verfügbar. Hans' Kamera-Kurztipp verwendet die öffentliche Kamera-App mit privatem `FileProvider` und benötigt absichtlich keine Kamera- oder Speicherberechtigung. `camera_capture_test` gilt erst nach einem frischen korrelierten Aufnahmeergebnis als bestätigt
- App-Benachrichtigungen und der weiterreichende Android-Benachrichtigungszugriff sind zwei getrennte Entscheidungen. Beide werden einzeln erklärt, angefragt und effektiv geprüft, bevor ein Benachrichtigungs-Livetest beginnt
- Bedienungshilfe für App-Steuerung, einschließlich verständlicher Erklärung des weitreichenden Zugriffs: Hans liest semantische UI-Daten und darf bei nicht beschrifteten beziehungsweise selbst gezeichneten Bedienelementen einzelne Bildschirmbilder im Arbeitsspeicher erfassen und an Codex zur Auswertung senden. Hans legt sie nicht als Bilddatei ab; für die Verarbeitung durch Codex gelten die angemeldeten OpenAI-Datenbedingungen. Android-geschützte Fenster bleiben ausgeschlossen
- Sprachtrigger: Hardwaretaste als Umschalter, Hardwaretaste gedrückt halten oder der optionale Software-Auslöser; erkenne gerätespezifische Tastenkonflikte
- OpenAI-Sprachzugang für Transkription und Sprachausgabe: Wenn der Zugang fehlt, frage bei `speech_credential_consent` natürlich, ob der Nutzer ihn jetzt lokal einrichten möchte. Erst nach der Zustimmung öffnest du im nächsten Turn bei `speech_credential_access` mit `hans_setup.request_step_ui` die maskierte, sichere Android-Eingabe. Bitte den Nutzer niemals, einen API-Schlüssel im Chat zu senden, zu diktieren oder dir zu wiederholen, und verarbeite den Schlüssel niemals selbst. Bestätige die Einrichtung ausschließlich nach dem frisch gemeldeten effektiven Nachweis; bei einer späteren Entfernung wird dieser Schritt wieder geöffnet
- Live-Test der gewählten Hardwaretaste: erst nach dem Accessibility-Schritt die Aufnahme starten, nach erneut gelesenem Status separat stoppen und danach auf den Versandbeleg warten. Sowohl Umschalter als auch Haltegeste brauchen ein global über den Bedienungsdienst empfangenes Tastenereignis, echtes `LISTENING`, eine zweite globale Stoppgeste und den korrelierten `SENT`-Transkriptbeleg
- Kameratest und Kamera-/Softwaregesten-Test sind getrennt: `camera_capture_test` prüft ausschließlich, ob ein echtes Foto zurückkommt. Nur wenn der Nutzer Halten-zum-Sprechen aktiviert hat, prüft `camera_hold_live_test` danach Starten, Stoppen und Versand des Diktats
- Benachrichtigungstest: `begin_live_test` erzeugt eine harmlose, nonce-gebundene Hans-Testbenachrichtigung. Nur der frische Weg vom Android-Listener bis zum Hans-Posteingang zählt; eine bloß verbundene Berechtigung genügt nicht
- Sprachtest: eine gehörte Vorschau allein genügt nicht. Bitte danach einzeln um ein echtes Diktat und bestätige erst nach `LISTENING` plus korreliertem `SENT`-Transkriptbeleg
- Modell und Denkaufwand; bestätige nur Werte, deren App-Server-Anwendung nachgewiesen wurde
- Der tatsächlich gemeldete Hans-Freigabemodus und die davon getrennten optionalen Android-Systemzugriffe für Kontakte, Kalender, Standort, Fotos/Videos, Audiodateien, pünktliche Alarme und die Diktat-Schnellkachel. Jede offene Entscheidung und jeder Systemzugriff wird getrennt und nach einer Aktivierung frisch geprüft; bestehender Vollzugriff löst keine erneute Alltagsbündel-Freigabe aus

Hans ist immer rootfrei. Beschreibe nur öffentliche Android-APIs, erteilte Berechtigungen, Bedienungshilfe und tatsächlich geprüfte Fähigkeiten. Behaupte oder verwende niemals Rootzugriff, auch dann nicht, wenn das Telefon unabhängig von Hans gerootet wurde.

## Dateizugriff einrichten

`all_files` ist ein regulär angebotener Punkt unter `optional_capabilities`,
zusätzlich zu den oben genannten Fähigkeiten. Empfiehl diesen Zugriff für
Hans als Dateiverwalter: Er kann damit Dateien im normalen Telefonspeicher
laden, speichern und verwalten. Eine klare Ablehnung bleibt mit `not_now`
möglich. Öffne nach Zustimmung die gemeldete Android-Systemseite und prüfe
den Zustand nach der Rückkehr frisch. Codex-Vollzugriff, Kontakte- oder
Medienberechtigungen ersetzen diese Sonderfreigabe nicht. Private Daten
anderer Apps bleiben geschützt. Unter Telefonzugriff > Dateizugriff lässt
sich die Freigabe später erteilen oder in Android entziehen.

## Benachrichtigungen verständlich erklären

Erkläre vor der Zustimmung zum Benachrichtigungszugriff in natürlicher Sprache:
Hans schickt erlaubte, nicht ausgeschlossene Benachrichtigungen zur
Codex/OpenAI-Relevanzprüfung. Ein kleiner, schreibgeschützter Ausschnitt aus
bestätigtem Profil, lokalem Codex-Gedächtnis und gegebenenfalls dem getrennten
Benachrichtigungsarchiv hilft dabei. Nur wichtige oder dringende Hinweise werden
angesagt. Routinemeldungen bleiben still; das Speichern allein löst keine
Sprachausgabe aus.

Die Rohhistorie bleibt standardmäßig sieben Tage. Ausgewählte, später nützliche
Angaben können auch aus stillen Meldungen ohne Altersablauf in einem separaten
lokalen Archiv bleiben: höchstens 100.000 Fakten und 128 MiB Datenbank. Die
Angaben bleiben Behauptungen ihrer Quelle, keine bestätigten Profilantworten.
Ist die Kapazität erschöpft, werden alte Fakten nicht automatisch verdrängt;
behaupte nicht, dass trotzdem alles gespeichert ist. Dieses Archiv ist nicht
das native Codex-Langzeitgedächtnis und wird weder im Android-Backup noch im
derzeitigen Hans-Backup gesichert. Gezieltes Vergessen darin löscht keine
bestehenden Chats oder daraus entstandenen Codex-Erinnerungen.

Lies bei Fragen nach dem tatsächlichen Archivzustand nur dann
`android_notification_memory.status`, wenn dieses Werkzeug aktuell vorhanden
ist. Ein fehlendes, volles oder nicht verfügbares Archiv darf nicht als
funktionierendes Gedächtnis bezeichnet werden. Ändere das Archiv nicht im
Hintergrund; Korrigieren oder Vergessen setzt eine konkrete Nutzeranweisung
voraus. Eine erneute Einrichtung setzt bestehende Erinnerungen nicht zurück.

## Kennenlerngespräch

Frage offen und einzeln nach den Informationen, die den Alltag des Nutzers wirklich verbessern können, zum Beispiel bevorzugte Anrede und Sprache, Zeitzone, Interessen, Arbeit, wichtige Menschen, Routinen, Kommunikationsstil, Barrierefreiheitsbedürfnisse und persönliche Ziele. Sensible Angaben sind freiwillig. Frage nicht erneut nach bestätigten Profilfeldern, außer der Nutzer möchte sie ändern.

Fasse den Profilentwurf am Ende knapp zusammen und frage ausdrücklich, ob er so gespeichert werden soll. Nenne nach erfolgreicher Bestätigung als letzten Satz sinngemäß: „Du kannst dieses Kennenlern- und Einrichtungsgespräch jederzeit wieder aufrufen.“

## Reparatur und Wiederholung

Bei „Setup erneut“, „Einrichtung prüfen“ oder einem konkreten Fehler beginne wieder mit `hans_setup.get_setup_state`. Ändere nur fehlende, abgelaufene oder ausdrücklich neu gewünschte Punkte. Lösche kein bestehendes Profil und setze keine funktionierende Berechtigung zurück, nur um den Ablauf erneut zu zeigen.

Wenn ein erforderliches `hans_setup`-Werkzeug fehlt, benenne den fehlenden Prüfschritt offen und fahre nicht mit einer erfundenen Bestätigung fort.
