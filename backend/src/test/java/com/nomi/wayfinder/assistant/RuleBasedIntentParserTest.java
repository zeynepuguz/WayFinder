package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.IntentParser.IntentContext;
import com.nomi.wayfinder.entity.StopType;
import com.nomi.wayfinder.entity.WalkingTolerance;
import com.nomi.wayfinder.planning.ReplanType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedIntentParserTest {

    private static final IntentContext NO_ROUTE = new IntentContext(false, List.of());
    private static final IntentContext WITH_ROUTE = new IntentContext(true, List.of());

    private final RuleBasedIntentParser parser = new RuleBasedIntentParser();

    @Test
    void parsesTheFullPlanRequestFromTheProjectBrief() {
        AssistantIntent intent = parser.parse("""
                Kadıköy'deyim.
                2 kişiyiz.
                700 TL bütçemiz var.
                Çok yürümek istemiyoruz.
                Kahvaltı, kahve ve akşam yemeği istiyoruz.
                Hava çok sıcak.""", NO_ROUTE);

        assertThat(intent.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(intent.plan().partySize()).isEqualTo(2);
        assertThat(intent.plan().budget()).isEqualTo(700);
        assertThat(intent.plan().walkingTolerance()).isEqualTo(WalkingTolerance.LOW);
        assertThat(intent.plan().stops())
                .containsExactlyInAnyOrder(StopType.BREAKFAST, StopType.COFFEE, StopType.DINNER);
    }

    @Test
    void newPlanWinsOverReplanWhenBudgetAndStopsAreGiven() {
        AssistantIntent intent = parser.parse(
                "2 kişiyiz, 700 TL bütçemiz var, çok yürümek istemiyoruz, kahvaltı ve kahve istiyoruz", WITH_ROUTE);

        assertThat(intent.type()).isEqualTo(IntentType.PLAN_ROUTE);
    }

    @Test
    void dayTripWithExplicitStopsAlsoGetsSightseeing() {
        AssistantIntent intent = parser.parse(
                "Kadıköy'e ilk defa gidiyorum. Bir günlük uygun bütçeli bir gezi yapmak istiyorum. "
                        + "Kahvaltı, güzel bir yemek, kahve ve tatlı da olsun.", NO_ROUTE);

        assertThat(intent.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(intent.plan().stops()).contains(
                StopType.BREAKFAST, StopType.LUNCH, StopType.COFFEE, StopType.DESSERT, StopType.SIGHTSEEING);
        assertThat(intent.plan().interests()).contains("budget");
    }

    @Test
    void tiredAndRainAreReplans() {
        assertThat(parser.parse("Çok yorulduk.", WITH_ROUTE).edits())
                .extracting(AssistantIntent.RouteEdit::type).containsExactly(ReplanType.TIRED);
        assertThat(parser.parse("Yağmur başladı", WITH_ROUTE).edits())
                .extracting(AssistantIntent.RouteEdit::type).containsExactly(ReplanType.WEATHER_CHANGED);
    }

    @Test
    void removeAndAddInOneSentence() {
        AssistantIntent intent = parser.parse("Akşam yemeğini çıkar, onun yerine tatlı ekle", WITH_ROUTE);

        assertThat(intent.type()).isEqualTo(IntentType.REPLAN);
        assertThat(intent.edits()).hasSize(2);
        assertThat(intent.edits().get(0).type()).isEqualTo(ReplanType.REMOVE_STOP);
        assertThat(intent.edits().get(0).targetStopType()).isEqualTo(StopType.DINNER);
        assertThat(intent.edits().get(1).type()).isEqualTo(ReplanType.ADD_STOP);
        assertThat(intent.edits().get(1).stopType()).isEqualTo(StopType.DESSERT);
    }

    @Test
    void removeCurrentPlaceAndAddInterest() {
        AssistantIntent remove = parser.parse("Burayı çıkar", WITH_ROUTE);
        assertThat(remove.edits().getFirst().type()).isEqualTo(ReplanType.REMOVE_STOP);
        assertThat(remove.edits().getFirst().targetIsCurrent()).isTrue();

        AssistantIntent history = parser.parse("Biraz daha tarihi yerler ekle", WITH_ROUTE);
        assertThat(history.edits().getFirst().type()).isEqualTo(ReplanType.ADD_INTEREST);
        assertThat(history.edits().getFirst().interest()).isEqualTo("history");
    }

    @Test
    void recommendAndWeatherQuestions() {
        AssistantIntent coffee = parser.parse("Yakında iyi bir kahveci öner", NO_ROUTE);
        assertThat(coffee.type()).isEqualTo(IntentType.RECOMMEND);
        assertThat(coffee.recommendType()).isEqualTo(StopType.COFFEE);

        assertThat(parser.parse("Hava nasıl?", NO_ROUTE).type()).isEqualTo(IntentType.WEATHER);
        assertThat(parser.parse("Merhaba", NO_ROUTE).type()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    void parsesNumbersInDifferentFormats() {
        assertThat(RuleBasedIntentParser.budget("1.500 tl bütçe")).isEqualTo(1500);
        assertThat(RuleBasedIntentParser.budget("500₺")).isEqualTo(500);
        assertThat(RuleBasedIntentParser.partySize("sevgilimle geziyoruz")).isEqualTo(2);
        assertThat(RuleBasedIntentParser.partySize("4 kişiyiz")).isEqualTo(4);
        assertThat(RuleBasedIntentParser.startTime("saat 10:30'da başlayalım"))
                .isEqualTo(java.time.LocalTime.of(10, 30));
    }
}
