package cl.codes.classifier;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassifierTest {

    @Test
    void recommendsInstitutionsForMultisectorEmergencies() {
        var result = Classifier.classifyCall(
                "Hay un incendio, una persona herida y dos sujetos con arma en avenida Providencia 1234"
        );

        assertTrue(result.priority().equals("URGENTE"));
        assertTrue(result.suggestedInstitutions().containsAll(
                List.of("carabineros", "samu", "bomberos")
        ));
        assertFalse(result.reasons().isEmpty());
        assertTrue(result.address().contains("Providencia"));
    }

    @Test
    void doesNotRecommendInstitutionForNegatedThreats() {
        var result = Classifier.classifyCall(
                "No hay ningún arma, fue solo una discusión con el vecino"
        );

        assertFalse(result.suggestedInstitutions().contains("carabineros"));
        assertTrue(result.priority().equals("MEDIA"));
    }

    @Test
    void recommendsPoliceAndMedicalSupportForSexualAssault() {
        var result = Classifier.classifyCall(
                "Mi vecina fue víctima de una violación hace unos minutos, está muy mal"
        );

        assertTrue(result.priority().equals("URGENTE"));
        assertTrue(result.suggestedInstitutions().contains("carabineros"),
                () -> result.suggestedInstitutions().toString());
        assertTrue(result.suggestedInstitutions().contains("samu"),
                () -> result.suggestedInstitutions().toString());
    }

    @Test
    void recommendsMedicalAndPoliceSupportForSuicideRisk() {
        var result = Classifier.classifyCall(
                "Mi hermano dice que se va a tirar del puente, está en la baranda ahora mismo"
        );

        assertTrue(result.priority().equals("URGENTE"));
        assertTrue(result.suggestedInstitutions().containsAll(
                List.of("samu", "carabineros")
        ));
    }

    @Test
    void recommendsPoliceForStabbingWithoutWeaponTerm() {
        var result = Classifier.classifyCall(
                "Un sujeto acaba de apuñalar a otro en la plaza, hay mucha sangre"
        );

        assertTrue(result.suggestedInstitutions().contains("carabineros"),
                () -> result.suggestedInstitutions().toString());
        assertTrue(result.suggestedInstitutions().contains("samu"),
                () -> result.suggestedInstitutions().toString());
    }

    @Test
    void identifiesTranscriptsWithoutOperationalInformation() {
        assertFalse(Classifier.hasOperationalInformation(Classifier.classifyCall("")));
        assertFalse(Classifier.hasOperationalInformation(Classifier.classifyCall("texto sin sentido")));
    }

    @Test
    void retainsLowPriorityRequestsWithOperationalInformation() {
        assertTrue(Classifier.hasOperationalInformation(
                Classifier.classifyCall("Quiero saber el horario de atención de la comisaría")
        ));
    }

        @Test
        void extractsLocationWithoutPersistingTheFullNarrative() {
                var result = Classifier.classifyCall(
                                "Hay un choque ubicado en Manuel Montt y hay dos personas heridas"
                );

                assertTrue(result.address().equals("Manuel Montt"));
        }

        @Test
        void classifiesCommonAccidentAndMedicalEmergencyVariants() {
            assertEquals("ROJA", Classifier.classifyCall("Dos autos chocaron.").priority());
            assertTrue(Classifier.classifyCall("Una persona no puede respirar.")
                    .suggestedInstitutions().contains("samu"));
            assertTrue(Classifier.classifyCall("Se está quemando una casa.")
                    .suggestedInstitutions().contains("bomberos"));
        }

        @Test
        void recommendsInstitutionsWithContextualSeverityAndInjurySignals() {
            var noInjuries = Classifier.classifyCall("Incendio en vivienda sin heridos.");
            assertEquals("PRIMARY", recommendation(noInjuries, "bomberos").role());
            assertEquals("URGENTE", recommendation(noInjuries, "bomberos").severity());
            assertFalse(noInjuries.suggestedInstitutions().contains("samu"));
            assertEquals("SECONDARY", recommendation(noInjuries, "carabineros").role());
            assertEquals("MEDIA", recommendation(noInjuries, "carabineros").severity());

            var injured = Classifier.classifyCall("Incendio en vivienda con una persona inconsciente.");
            assertEquals("SECONDARY", recommendation(injured, "samu").role());
            assertEquals("URGENTE", recommendation(injured, "samu").severity());
            assertEquals("SECONDARY", recommendation(injured, "carabineros").role());
        }

        @Test
        void institutionSeverityDependsOnInjuriesAndContext() {
            var fireWithInjury = Classifier.classifyCall("Hay un incendio en una vivienda y una persona herida.");
            assertEquals("URGENTE", recommendation(fireWithInjury, "bomberos").severity());
            assertEquals("URGENTE", recommendation(fireWithInjury, "samu").severity());
            assertEquals("ROJA", recommendation(fireWithInjury, "carabineros").severity());

            var fireWithoutInjury = Classifier.classifyCall("Hay un incendio en una vivienda, pero no hay heridos.");
            assertFalse(fireWithoutInjury.suggestedInstitutions().contains("samu"));
            assertEquals("MEDIA", recommendation(fireWithoutInjury, "carabineros").severity());

            var unconsciousCollision = Classifier.classifyCall("Dos vehículos chocaron y una persona está inconsciente.");
            assertEquals("URGENTE", recommendation(unconsciousCollision, "samu").severity());
            assertEquals("ROJA", recommendation(unconsciousCollision, "carabineros").severity());
            assertFalse(unconsciousCollision.suggestedInstitutions().contains("bomberos"));
        }

        @Test
        void uninjuredCollisionPrioritizesPoliceWithoutActivatingOtherServices() {
            var result = Classifier.classifyCall("Dos autos chocaron, nadie está herido.");

            assertEquals("PRIMARY", recommendation(result, "carabineros").role());
            assertEquals("ROJA", recommendation(result, "carabineros").severity());
            assertFalse(result.suggestedInstitutions().contains("samu"));
            assertFalse(result.suggestedInstitutions().contains("bomberos"));
        }

        @Test
        void rescueAndGasLeakRecommendMedicalSupportWithoutDuplicateInstitutions() {
            var rescue = Classifier.classifyCall("Choque con una persona atrapada.");
            assertEquals(List.of("bomberos", "samu", "carabineros"), rescue.suggestedInstitutions());
            assertEquals("URGENTE", recommendation(rescue, "bomberos").severity());

            var groupRescue = Classifier.classifyCall("Hay personas atrapadas en el segundo piso.");
            assertEquals("URGENTE", groupRescue.priority());
            assertTrue(groupRescue.suggestedInstitutions().contains("bomberos"));

            var gas = Classifier.classifyCall("Fuga de gas con personas afectadas.");
            assertTrue(gas.suggestedInstitutions().containsAll(List.of("bomberos", "samu")));
            assertEquals(gas.suggestedInstitutions().size(),
                    gas.suggestedInstitutions().stream().distinct().count());
        }

        @Test
        void isolatedSmokeIsMarkedAsUnconfirmedRisk() {
            var result = Classifier.classifyCall("Hay humo en una vivienda.");

            assertEquals("MEDIA", recommendation(result, "bomberos").severity());
            assertTrue(recommendation(result, "bomberos").reason().contains("no está confirmado"));
        }

        @Test
        void handlesEmptySpecialAndOversizedInputs() {
            assertFalse(Classifier.hasOperationalInformation(Classifier.classifyCall(null)));
            assertFalse(Classifier.hasOperationalInformation(Classifier.classifyCall(" \t\n")));
            assertTrue(Classifier.classifyCall("O'Higgins Ñuñoa 🚒 []{}; %").priority() != null);
            assertTrue(Classifier.classifyCall("a".repeat(10_000)).priority() != null);
        }

        @Test
        void classifiesOperationalPhrasesByCategoryPriorityAndInstitutions() {
            assertClassification("Hay un incendio.", "FIRE", "URGENTE", "bomberos");
            assertClassification("Se está quemando una casa.", "STRUCTURAL_FIRE", "URGENTE", "bomberos");
            assertClassification("Hay humo y llamas en una vivienda.", "STRUCTURAL_FIRE", "URGENTE", "bomberos");
            assertClassification("Hay una persona atrapada.", "TRAPPED_PERSON", "URGENTE", "bomberos");
            var fireRescue = Classifier.classifyCall("Hay un incendio y una persona atrapada.");
            assertTrue(fireRescue.incidentCategories().containsAll(List.of("FIRE", "TRAPPED_PERSON")));
            assertTrue(fireRescue.suggestedInstitutions().containsAll(List.of("bomberos", "samu")));
            assertEquals(fireRescue.suggestedInstitutions().size(),
                    fireRescue.suggestedInstitutions().stream().distinct().count());

            var unconscious = Classifier.classifyCall("Hay una persona inconsciente.");
            assertTrue(unconscious.incidentCategories().contains("UNCONSCIOUS_PERSON"));
            assertEquals("URGENTE", recommendation(unconscious, "samu").severity());
            var fireAndUnconscious = Classifier.classifyCall("Hay un incendio y una persona inconsciente.");
            assertEquals("URGENTE", recommendation(fireAndUnconscious, "bomberos").severity());
            assertEquals("URGENTE", recommendation(fireAndUnconscious, "samu").severity());
            assertEquals("ROJA", recommendation(fireAndUnconscious, "carabineros").severity());

            var collision = Classifier.classifyCall("Dos autos chocaron.");
            assertTrue(collision.incidentCategories().contains("COLLISION"));
            assertEquals("ROJA", collision.priority());
            assertEquals("ROJA", recommendation(collision, "carabineros").severity());
            assertFalse(collision.suggestedInstitutions().contains("samu"));
            assertFalse(collision.suggestedInstitutions().contains("bomberos"));

            var injuredCollision = Classifier.classifyCall("Dos autos chocaron y hay heridos.");
            assertEquals("ROJA", recommendation(injuredCollision, "samu").severity());
            assertEquals("ROJA", recommendation(injuredCollision, "carabineros").severity());
            assertClassification("Un vehículo volcó.", "ROLLOVER", "URGENTE", "carabineros");
            assertClassification("Una persona fue atropellada.", "PEDESTRIAN_STRIKE", "ROJA", "samu");
            assertClassification("Hay una fuga de gas.", "GAS_LEAK", "URGENTE", "bomberos");
            assertClassification("Hay una explosión.", "EXPLOSION", "URGENTE", "bomberos");
            assertClassification("Hay una persona que no puede respirar.",
                    "BREATHING_DIFFICULTY", "URGENTE", "samu");
        }

        @Test
        void preservesOriginalTextAndRecognizesTranscriptVariants() {
            String text = "  Se esta quemando una casa con O'Higgins 123 🚒  ";
            var result = Classifier.classifyCall(text);

            assertEquals(text, result.originalText());
            assertTrue(result.incidentCategories().contains("STRUCTURAL_FIRE"));
            assertTrue(Classifier.classifyCall("Hay un incendioo en la casa.")
                    .incidentCategories().contains("STRUCTURAL_FIRE"));
            assertEquals("MEDIA", recommendation(
                    Classifier.classifyCall("Hay humo en una vivienda."), "bomberos").severity());
            assertTrue(Classifier.classifyCall("Hay humo en una vivienda.")
                    .incidentCategories().contains("SMOKE"));
            assertEquals("Av. O'Higgins #123", Classifier.classifyCall("Incendio en Av. O'Higgins 123.")
                    .address());
        }

        private void assertClassification(String text, String category, String priority, String institution) {
            var result = Classifier.classifyCall(text);
            assertTrue(result.incidentCategories().contains(category),
                    () -> text + " categories=" + result.incidentCategories());
            assertEquals(priority, result.priority(), text);
            assertTrue(result.suggestedInstitutions().contains(institution),
                    () -> text + " institutions=" + result.suggestedInstitutions());
        }

        private Classifier.InstitutionRecommendation recommendation(
                Classifier.ClassificationResult result,
                String institution
        ) {
            return result.institutionRecommendations().stream()
                    .filter(item -> item.institution().equals(institution))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing recommendation for " + institution));
        }
}
