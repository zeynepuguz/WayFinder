package com.nomi.wayfinder.assistant;

import com.nomi.wayfinder.assistant.AssistantIntent.IntentType;
import com.nomi.wayfinder.assistant.IntentParser.IntentContext;
import com.nomi.wayfinder.i18n.TurkishFold;
import com.nomi.wayfinder.i18n.TurkishSuffix;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentDatesTest {

    // Sunday
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);

    @Test
    void relativeDays() {
        assertThat(date("bugün kahvaltı")).isEqualTo(TODAY);
        assertThat(date("yarın gezeceğiz")).isEqualTo(TODAY.plusDays(1));
        assertThat(date("yarin gezecegiz")).isEqualTo(TODAY.plusDays(1));
        assertThat(date("yarından sonra müzeye gidelim")).isEqualTo(TODAY.plusDays(2));
        assertThat(date("öbür gün boşuz")).isEqualTo(TODAY.plusDays(2));
        assertThat(date("Tomorrow in Kadıköy")).isEqualTo(TODAY.plusDays(1));
        assertThat(date("the day after tomorrow")).isEqualTo(TODAY.plusDays(2));
        assertThat(date("2 kişi 700 tl kahvaltı")).isNull();
    }

    @Test
    void weekdaysMeanTheirNextOccurrence() {
        assertThat(date("cumartesi gezelim")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(date("cuma günü")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(date("cumaya plan yap")).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(date("pazartesi")).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(date("salı günü")).isEqualTo(LocalDate.of(2026, 9, 29));
        assertThat(date("çarşamba")).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(date("persembe")).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(date("on Saturday")).isEqualTo(LocalDate.of(2026, 10, 3));
        // Today is Sunday: "pazar" alone is next Sunday, "bu pazar" / "bugün pazar" is today
        assertThat(date("pazar günü gezelim")).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(date("bu pazar gezelim")).isEqualTo(TODAY);
        assertThat(date("this sunday")).isEqualTo(TODAY);
    }

    @Test
    void pazarWithACaseEndingIsTheMarket() {
        assertThat(date("pazara gidelim")).isNull();
        assertThat(date("balık pazarında yemek")).isNull();
        assertThat(date("salıncak")).isNull();
    }

    @Test
    void explicitDates() {
        assertThat(date("28 Eylül'de gezeceğiz")).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(date("3 ekim")).isEqualTo(LocalDate.of(2026, 10, 3));
        assertThat(date("28.09")).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(date("5/10 için plan")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(date("12.05.2027")).isEqualTo(LocalDate.of(2027, 5, 12));
        assertThat(date("September 28")).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(date("on the 3rd of October")).isEqualTo(LocalDate.of(2026, 10, 3));
        // Already passed this year -> next year
        assertThat(date("1 Mayıs")).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(date("30 Şubat")).isNull();
    }

    @Test
    void clockTimesPricesAndDecimalsAreNotDates() {
        assertThat(date("saat 13.00'da")).isNull();
        assertThat(date("12.05'te buluşalım")).isNull();
        assertThat(date("2.5 saat yürüyüş")).isNull();
        assertThat(date("1.500 tl bütçe")).isNull();
    }

    @Test
    void theMessageFromProductionIsTomorrowAt13ForTwoWith700() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-27T17:00:00Z"), ZoneId.of("Europe/Istanbul"));
        AssistantIntent intent = new RuleBasedIntentParser(clock)
                .parse(AssistantAreaDateRouteTest.MESSAGE, new IntentContext(false, List.of()));

        assertThat(intent.type()).isEqualTo(IntentType.PLAN_ROUTE);
        assertThat(intent.date()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(intent.plan().startTime()).isEqualTo(LocalTime.of(13, 0));
        assertThat(intent.plan().partySize()).isEqualTo(2);
        assertThat(intent.plan().budget()).isEqualTo(700);
    }

    @Test
    void startTimes() {
        assertThat(RuleBasedIntentParser.startTime("saat 13.00'da buluşalım")).isEqualTo(LocalTime.of(13, 0));
        assertThat(RuleBasedIntentParser.startTime("13:00'te başlayalım")).isEqualTo(LocalTime.of(13, 0));
        assertThat(RuleBasedIntentParser.startTime("13.30'da")).isEqualTo(LocalTime.of(13, 30));
        assertThat(RuleBasedIntentParser.startTime("saat 13 gibi")).isEqualTo(LocalTime.of(13, 0));
        assertThat(RuleBasedIntentParser.startTime("saat 10'da")).isEqualTo(LocalTime.of(10, 0));
        // 01:00 or 13:00? Neither is guessed
        assertThat(RuleBasedIntentParser.startTime("saat 1'de")).isNull();
        assertThat(RuleBasedIntentParser.startTime("2.50 tl")).isNull();
        assertThat(RuleBasedIntentParser.startTime("28.09.2026")).isNull();
    }

    @Test
    void turkishFoldAndAblative() {
        assertThat(TurkishFold.slug("Büyükçekmece")).isEqualTo("buyukcekmece");
        assertThat(TurkishFold.slug("İstanbul Şişli")).isEqualTo("istanbul-sisli");
        assertThat(TurkishFold.ascii("ÜSKÜDAR'DA")).isEqualTo("uskudar'da");
        assertThat(TurkishSuffix.ablative("Üsküdar")).isEqualTo("Üsküdar'dan");
        assertThat(TurkishSuffix.ablative("Kadıköy")).isEqualTo("Kadıköy'den");
        assertThat(TurkishSuffix.ablative("Beşiktaş")).isEqualTo("Beşiktaş'tan");
        assertThat(TurkishSuffix.ablative("Bebek")).isEqualTo("Bebek'ten");
        assertThat(TurkishSuffix.ablative("Moda")).isEqualTo("Moda'dan");
        assertThat(TurkishSuffix.ablative("Şile")).isEqualTo("Şile'den");
    }

    private static LocalDate date(String message) {
        return IntentDates.parse(message, TODAY);
    }
}
