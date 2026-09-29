package com.nomi.wayfinder.overture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OverturePlaceImporterTest {

    @Test
    void theBoxIsExactNotAGeodesicBox() {
        // geography && alone selected twice the places of the Kocaeli box (down to lon 29.12 for a 29.32 west edge)
        // and hid / unconfirmed places Overture had not been asked about
        assertThat(OverturePlaceImporter.IN_BOX)
                .contains("location && CAST(ST_MakeEnvelope(?, ?, ?, ?, 4326) AS geography)")
                .contains("ST_Intersects(CAST(location AS geometry), ST_MakeEnvelope(?, ?, ?, ?, 4326))");
    }
}
