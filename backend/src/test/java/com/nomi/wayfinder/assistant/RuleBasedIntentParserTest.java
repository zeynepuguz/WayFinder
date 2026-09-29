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

    // ---------- English (tourists) ----------

    @Test
    void parsesAnEnglishPlanRequest() {
        AssistantIntent intent = parser.parse(
                "We are 2 people, budget 700 TL. We want breakfast, lunch and dinner, and we don't want to walk much.",
                NO_ROUTE);

        assertThat(intent.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(intent.plan().partySize()).isEqualTo(2);
        assertThat(intent.plan().budget()).isEqualTo(700);
        assertThat(intent.plan().walkingTolerance()).isEqualTo(WalkingTolerance.LOW);
        assertThat(intent.plan().stops())
                .containsExactlyInAnyOrder(StopType.BREAKFAST, StopType.LUNCH, StopType.DINNER);
    }

    @Test
    void parsesEnglishInterestsAndDayPlans() {
        AssistantIntent day = parser.parse("Plan a day in Kadıköy", NO_ROUTE);
        assertThat(day.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(day.plan().stops()).isEmpty();

        AssistantIntent trip = parser.parse(
                "It's my first time here. I love museums, history, art and the sea. Coffee and dessert too, 500 lira.",
                NO_ROUTE);
        assertThat(trip.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(trip.plan().budget()).isEqualTo(500);
        assertThat(trip.plan().interests()).contains("museum", "history", "art", "sea");
        assertThat(trip.plan().stops()).contains(StopType.COFFEE, StopType.DESSERT, StopType.SIGHTSEEING);
    }

    @Test
    void understandsTheEnglishExamplesTheAppShows() {
        assertThat(parser.parse("We are 2 people, our budget is 700 TL, we want breakfast and coffee", NO_ROUTE).plan())
                .satisfies(p -> {
                    assertThat(p.partySize()).isEqualTo(2);
                    assertThat(p.budget()).isEqualTo(700);
                    assertThat(p.stops()).containsExactlyInAnyOrder(StopType.BREAKFAST, StopType.COFFEE);
                });
        assertThat(parser.parse("Plan a budget-friendly day in Kadıköy today", NO_ROUTE).plan().interests())
                .containsExactly("budget");
        assertThat(parser.parse("Create a route with historical places and dessert", NO_ROUTE).plan().stops())
                .contains(StopType.DESSERT, StopType.SIGHTSEEING);
        assertThat(parser.parse("Recommend a good coffee place nearby", NO_ROUTE).recommendType())
                .isEqualTo(StopType.COFFEE);
    }

    @Test
    void englishSituationsAreReplans() {
        assertThat(parser.parse("We're tired.", WITH_ROUTE).edits())
                .extracting(AssistantIntent.RouteEdit::type).containsExactly(ReplanType.TIRED);
        assertThat(parser.parse("It started raining!", WITH_ROUTE).edits())
                .extracting(AssistantIntent.RouteEdit::type).containsExactly(ReplanType.WEATHER_CHANGED);
        assertThat(parser.parse("Less walking please", WITH_ROUTE).edits())
                .extracting(AssistantIntent.RouteEdit::type).containsExactly(ReplanType.LESS_WALKING);
    }

    @Test
    void englishRemoveAndAdd() {
        AssistantIntent remove = parser.parse("Remove Çiya", WITH_ROUTE);
        assertThat(remove.type()).isEqualTo(IntentType.REPLAN);
        assertThat(remove.edits().getFirst().type()).isEqualTo(ReplanType.REMOVE_STOP);
        assertThat(remove.edits().getFirst().targetText()).contains("çiya");

        AssistantIntent here = parser.parse("Remove this place", WITH_ROUTE);
        assertThat(here.edits().getFirst().targetIsCurrent()).isTrue();

        AssistantIntent swap = parser.parse("Remove dinner and add dessert instead", WITH_ROUTE);
        assertThat(swap.edits()).extracting(AssistantIntent.RouteEdit::type)
                .containsExactly(ReplanType.REMOVE_STOP, ReplanType.ADD_STOP);
        assertThat(swap.edits().get(0).targetStopType()).isEqualTo(StopType.DINNER);
        assertThat(swap.edits().get(1).stopType()).isEqualTo(StopType.DESSERT);

        AssistantIntent coffee = parser.parse("Add a coffee stop", WITH_ROUTE);
        assertThat(coffee.edits()).hasSize(1);
        assertThat(coffee.edits().getFirst().type()).isEqualTo(ReplanType.ADD_STOP);
        assertThat(coffee.edits().getFirst().stopType()).isEqualTo(StopType.COFFEE);

        AssistantIntent history = parser.parse("Add some more historical places", WITH_ROUTE);
        assertThat(history.edits().getFirst().type()).isEqualTo(ReplanType.ADD_INTEREST);
        assertThat(history.edits().getFirst().interest()).isEqualTo("history");
    }

    @Test
    void englishRecommendWeatherAndShowRoute() {
        AssistantIntent cafe = parser.parse("Recommend a café nearby", NO_ROUTE);
        assertThat(cafe.type()).isEqualTo(IntentType.RECOMMEND);
        assertThat(cafe.recommendType()).isEqualTo(StopType.COFFEE);

        assertThat(parser.parse("What's the weather like?", NO_ROUTE).type()).isEqualTo(IntentType.WEATHER);
        assertThat(parser.parse("Show my route", WITH_ROUTE).type()).isEqualTo(IntentType.SHOW_ROUTE);
        assertThat(parser.parse("Hello", NO_ROUTE).type()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test
    void englishWordsDoNotMatchInsideTurkishWords() {
        // "artık" must not be read as "art"
        assertThat(RuleBasedIntentParser.interests("artık tarihi yerler görelim")).containsExactly("history");
    }

    @Test
    void parsesEnglishNumbers() {
        assertThat(RuleBasedIntentParser.budget("budget of 1,500")).isEqualTo(1500);
        assertThat(RuleBasedIntentParser.budget("700 lira")).isEqualTo(700);
        assertThat(RuleBasedIntentParser.partySize("there are 3 of us")).isEqualTo(3);
        assertThat(RuleBasedIntentParser.partySize("with my wife")).isEqualTo(2);
        assertThat(RuleBasedIntentParser.startTime("start at 10:30")).isEqualTo(java.time.LocalTime.of(10, 30));
        assertThat(RuleBasedIntentParser.startTime("from 2pm")).isEqualTo(java.time.LocalTime.of(14, 0));
        assertThat(RuleBasedIntentParser.startTime("we are 2 people")).isNull();
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

    @Test
    void aRouteInAnotherCityIsANewPlan() {
        AssistantIntent ankara = parser.parse("yarın Ankara'da Kızılay'dan başlayan bir rota", NO_ROUTE);
        assertThat(ankara.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(parser.parse("İzmir'de bir rota istiyorum", NO_ROUTE).type()).isEqualTo(IntentType.PLAN_ROUTE);
        // Asking for the current route is still SHOW_ROUTE
        assertThat(parser.parse("rotamı göster", WITH_ROUTE).type()).isEqualTo(IntentType.SHOW_ROUTE);
    }

    @Test
    void aGroupTripWithAPerPersonBudgetAndFamousSights() {
        AssistantIntent intent = parser.parse("bursaya 4 arkadaş gezmeye gideceğiz bize az yürüyeceğimiz şekilde ünlü "
                + "bir rota oluşturur musun her birimizin ayrı ayrı bütçesi 1000'er TL", NO_ROUTE);

        assertThat(intent.type()).isEqualTo(AssistantIntent.IntentType.PLAN_ROUTE);
        assertThat(intent.plan().partySize()).isEqualTo(4);
        // 1000 each for four
        assertThat(intent.plan().budget()).isEqualTo(4000);
        assertThat(intent.plan().walkingTolerance()).isEqualTo(com.nomi.wayfinder.entity.WalkingTolerance.LOW);
        assertThat(intent.plan().wantsPopular()).isTrue();
        assertThat(intent.plan().interests()).contains("history");
    }

    @Test
    void aTotalBudgetStaysTheTotal() {
        AssistantIntent intent = parser.parse("3 kişiyiz 1500 tl bütçemiz var bir gün planla", NO_ROUTE);

        assertThat(intent.plan().partySize()).isEqualTo(3);
        assertThat(intent.plan().budget()).isEqualTo(1500);
        assertThat(intent.plan().wantsPopular()).isFalse();
        assertThat(RuleBasedIntentParser.budget("kişi başı 500 tl")).isEqualTo(500);
        assertThat(RuleBasedIntentParser.perPerson("kişi başı 500 tl")).isTrue();
    }
}
