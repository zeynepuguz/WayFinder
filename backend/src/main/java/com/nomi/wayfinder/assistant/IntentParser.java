package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.entity.StopType;

import java.util.List;

public interface IntentParser {

    AssistantIntent parse(String message, IntentContext context);

    // What the parser may need to know about the conversation state
    record IntentContext(boolean hasRoute, List<StopRef> remainingStops) {
    }

    record StopRef(Long stopId, StopType type, String placeName) {
    }
}
