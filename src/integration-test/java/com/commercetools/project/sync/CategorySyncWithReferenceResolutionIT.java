package com.commercetools.project.sync;

import static com.commercetools.project.sync.util.ClientConfigurationUtils.createClient;
import static com.commercetools.project.sync.util.IntegrationTestUtils.assertCategoryExists;
import static com.commercetools.project.sync.util.IntegrationTestUtils.cleanUpProjects;
import static com.commercetools.project.sync.util.SphereClientUtils.CTP_SOURCE_CLIENT_CONFIG;
import static com.commercetools.project.sync.util.SphereClientUtils.CTP_TARGET_CLIENT_CONFIG;
import static io.sphere.sdk.models.LocalizedString.ofEnglish;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

import io.sphere.sdk.categories.CategoryDraft;
import io.sphere.sdk.categories.CategoryDraftBuilder;
import io.sphere.sdk.categories.commands.CategoryCreateCommand;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.models.AssetDraft;
import io.sphere.sdk.models.AssetDraftBuilder;
import io.sphere.sdk.models.AssetSourceBuilder;
import io.sphere.sdk.models.LocalizedString;
import io.sphere.sdk.models.TextInputHint;
import io.sphere.sdk.types.CustomFieldsDraft;
import io.sphere.sdk.types.FieldDefinition;
import io.sphere.sdk.types.ResourceTypeIdsSetBuilder;
import io.sphere.sdk.types.StringFieldType;
import io.sphere.sdk.types.Type;
import io.sphere.sdk.types.TypeDraft;
import io.sphere.sdk.types.TypeDraftBuilder;
import io.sphere.sdk.types.commands.TypeCreateCommand;
import java.time.Clock;
import java.util.Arrays;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.org.lidalia.slf4jext.Level;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

public class CategorySyncWithReferenceResolutionIT {

  private static final TestLogger syncerTestLogger = TestLoggerFactory.getTestLogger(Syncer.class);
  private static final TestLogger cliRunnerTestLogger =
      TestLoggerFactory.getTestLogger(CliRunner.class);
  private static final String RESOURCE_KEY = "foo";
  private static final String TYPE_KEY = "typeKey";

  @BeforeEach
  void setup() {
    syncerTestLogger.clearAll();
    cliRunnerTestLogger.clearAll();
    cleanUpProjects(createClient(CTP_SOURCE_CLIENT_CONFIG), createClient(CTP_TARGET_CLIENT_CONFIG));
    setupSourceProjectData(createClient(CTP_SOURCE_CLIENT_CONFIG));
  }

  private void setupSourceProjectData(SphereClient sourceProjectClient) {

    final FieldDefinition FIELD_DEFINITION =
        FieldDefinition.of(
            StringFieldType.of(),
            "field_name",
            LocalizedString.ofEnglish("label_1"),
            false,
            TextInputHint.SINGLE_LINE);

    final TypeDraft typeDraft =
        TypeDraftBuilder.of(
                TYPE_KEY,
                LocalizedString.ofEnglish("name_1"),
                ResourceTypeIdsSetBuilder.of().addCategories().addPrices().addAssets().build())
            .description(LocalizedString.ofEnglish("description_1"))
            .fieldDefinitions(Arrays.asList(FIELD_DEFINITION))
            .build();

    final Type type =
        sourceProjectClient.execute(TypeCreateCommand.of(typeDraft)).toCompletableFuture().join();

    final SphereClient targetClient = createClient(CTP_TARGET_CLIENT_CONFIG);
    targetClient.execute(TypeCreateCommand.of(typeDraft)).toCompletableFuture().join();

    CustomFieldsDraft customFieldsDraft =
        CustomFieldsDraft.ofTypeKeyAndJson(type.getKey(), emptyMap());

    final AssetDraft assetDraft =
        AssetDraftBuilder.of(emptyList(), LocalizedString.ofEnglish("assetName"))
            .key("assetKey")
            .sources(singletonList(AssetSourceBuilder.ofUri("sourceUri").build()))
            .custom(customFieldsDraft)
            .build();

    final CategoryDraft categoryDraft =
        CategoryDraftBuilder.of(ofEnglish("t-shirts"), ofEnglish("t-shirts"))
            .key(RESOURCE_KEY)
            .assets(asList(assetDraft))
            .custom(customFieldsDraft)
            .build();
    sourceProjectClient
        .execute(CategoryCreateCommand.of(categoryDraft))
        .toCompletableFuture()
        .join();
  }

  @AfterAll
  static void tearDownSuite() {
    cleanUpProjects(createClient(CTP_SOURCE_CLIENT_CONFIG), createClient(CTP_TARGET_CLIENT_CONFIG));
  }

  @Test
  void
      run_WithSyncAsArgumentWithTypesAndCategories_ShouldResolveReferencesAndExecuteCategorySyncer() {
    // preparation
    try (final SphereClient targetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
      try (final SphereClient sourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {

        final SyncerFactory syncerFactory =
            SyncerFactory.of(() -> sourceClient, () -> targetClient, Clock.systemDefaultZone());

        // test
        CliRunner.of().run(new String[] {"-s", "categories"}, syncerFactory);
      }
    }

    // create clients again (for assertions and cleanup), since the run method closes the clients
    // after execution is done.
    try (final SphereClient postSourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {
      try (final SphereClient postTargetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {

        // assertions
        assertThat(cliRunnerTestLogger.getAllLoggingEvents())
            .allMatch(loggingEvent -> !Level.ERROR.equals(loggingEvent.getLevel()));

        assertThat(syncerTestLogger.getAllLoggingEvents())
            .allMatch(loggingEvent -> !Level.ERROR.equals(loggingEvent.getLevel()));

        // Every sync module is expected to have 2 logs (start and stats summary)
        assertThat(syncerTestLogger.getAllLoggingEvents()).hasSize(2);
        assertCategoryExists(postTargetClient, RESOURCE_KEY);
      }
    }
  }
}
