package com.mp.player.ai.llm

/**
 * Alias auf den eingebetteten Brain – Tests und ältere Aufrufe bleiben gültig.
 * Implementierung = [EmbeddedIntentBrain] (fest in der APK).
 */
class HeuristicLlmInterpreter : OptionalLlmInterpreter by EmbeddedIntentBrain
