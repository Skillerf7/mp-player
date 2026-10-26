# Befund: Offline Conversation Intelligence

## Verarbeitungskette

```
Chat-Text
  → IntentEngine.understand()          (Regeln / Keywords)
  → LlmCoordinator / PlayerBrain       (nur bei komplexen Musik-Intents)
  → Assistant.route()
      Emotion → emotion()
      Chat(kind) → chat(kind)          ← Templates aus Persona
      Unknown → fallback(text)         ← oft chatContinue(topic)
  → Reply(text, quickReplies)
```

Es gibt **kein generatives Sprachmodell**. Antworten sind Template-Picks (`Persona.pick`) plus Intent-Routing.

## Konkrete Ursachen für „Klingt schwer“ / Fun Facts

1. **`Persona.chatContinue(topic)`** (Persona.kt ~Z.40)  
   Erste Variante: `"Mmm. Klingt schwer."` — wird **immer** aus dem Pool gezogen, unabhängig vom Inhalt (Witz, Alltagsstory, neutrale Info).

2. **`fallback()` / `ChatKind.CONTINUE`**  
   Nutzen nur `state.topic`, **nicht** den aktuellen Nutzersatz. Der konkrete Inhalt geht nach der Intent-Klassifikation verloren.

3. **`ChatKind.TELL` → `persona.funFact()`**  
   Intent: `noPlay && t.contains("erzaehl")` → immer Musik-Fun-Facts.  
   Im SUPPORT/Venting-Kontext („erzähl mir was“) unpassend.

4. **ResponsePlanner**  
   Existiert, steuert aber kaum die Chat-Antwortwahl; Wiederholungen von Floskeln werden nicht gegen den letzten Reply geprüft.

5. **ConversationState**  
   Speichert Emotion/Topic/recent, wird in Chat-Antworten aber kaum inhaltlich zitiert (nur Topic-String).

## Architektur-Fazit (S20 FE)

| Option | Bewertung |
|--------|-----------|
| Templates + Inhaltssignale + Memory | **Geeignet**, offline, wenig RAM |
| Quantisiertes 1B–3B GGUF on-device | 0,6–2 GB, CPU-lastig, neben Media3 riskant |
| Cloud-LLM | **Verboten** laut Anforderung |

Empfehlung: Dialogfähigkeit über **inhaltsbewusste Reaktion** (DialogReactor), Anti-Wiederholung und Kontext-Gates verbessern — kein Fake-LLM.

## Gezielte Änderungen

- `DialogReactor`: Ton (schwer/leicht/Humor/Frage/Share) + Snippet aus Usertext
- `Persona.chatContinue` / Support-Antworten: an Ton koppeln, „Klingt schwer“ nur bei heavy
- `TELL` im SUPPORT → zuhören statt Fun Fact
- Anti-Repeat letzter Antworten
- Tests: Kontext, Rückfrage, Themenwechsel, Emotion, kein Fun-Fact im Venting
