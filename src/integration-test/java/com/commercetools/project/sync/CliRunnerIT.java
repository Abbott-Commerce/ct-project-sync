package com.commercetools.project.sync;

import static com.commercetools.project.sync.service.impl.CustomObjectServiceImpl.TIMESTAMP_GENERATOR_CONTAINER_POSTFIX;
import static com.commercetools.project.sync.service.impl.CustomObjectServiceImpl.TIMESTAMP_GENERATOR_KEY;
import static com.commercetools.project.sync.service.impl.CustomObjectServiceImpl.TIMESTAMP_GENERATOR_VALUE;
import static com.commercetools.project.sync.util.ClientConfigurationUtils.createClient;
import static com.commercetools.project.sync.util.IntegrationTestUtils.deleteLastSyncCustomObjects;
import static com.commercetools.project.sync.util.QueryUtils.queryAndExecute;
import static com.commercetools.project.sync.util.SphereClientUtils.CTP_SOURCE_CLIENT_CONFIG;
import static com.commercetools.project.sync.util.SphereClientUtils.CTP_TARGET_CLIENT_CONFIG;
import static com.commercetools.project.sync.util.TestUtils.assertAllSyncersLoggingEvents;
import static io.sphere.sdk.models.LocalizedString.ofEnglish;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercetools.project.sync.model.LastSyncCustomObject;
import com.commercetools.sync.categories.helpers.CategorySyncStatistics;
import com.commercetools.sync.commons.helpers.BaseSyncStatistics;
import com.commercetools.sync.inventories.helpers.InventorySyncStatistics;
import com.commercetools.sync.products.helpers.ProductSyncStatistics;
import com.commercetools.sync.producttypes.helpers.ProductTypeSyncStatistics;
import com.commercetools.sync.types.helpers.TypeSyncStatistics;
import io.sphere.sdk.categories.Category;
import io.sphere.sdk.categories.CategoryDraft;
import io.sphere.sdk.categories.CategoryDraftBuilder;
import io.sphere.sdk.categories.commands.CategoryCreateCommand;
import io.sphere.sdk.categories.commands.CategoryDeleteCommand;
import io.sphere.sdk.categories.queries.CategoryQuery;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.customobjects.CustomObject;
import io.sphere.sdk.customobjects.queries.CustomObjectQuery;
import io.sphere.sdk.inventory.InventoryEntry;
import io.sphere.sdk.inventory.InventoryEntryDraft;
import io.sphere.sdk.inventory.InventoryEntryDraftBuilder;
import io.sphere.sdk.inventory.commands.InventoryEntryCreateCommand;
import io.sphere.sdk.inventory.commands.InventoryEntryDeleteCommand;
import io.sphere.sdk.inventory.queries.InventoryEntryQuery;
import io.sphere.sdk.products.Product;
import io.sphere.sdk.products.ProductDraft;
import io.sphere.sdk.products.ProductDraftBuilder;
import io.sphere.sdk.products.ProductVariant;
import io.sphere.sdk.products.ProductVariantDraftBuilder;
import io.sphere.sdk.products.commands.ProductCreateCommand;
import io.sphere.sdk.products.commands.ProductDeleteCommand;
import io.sphere.sdk.products.queries.ProductQuery;
import io.sphere.sdk.producttypes.ProductType;
import io.sphere.sdk.producttypes.ProductTypeDraft;
import io.sphere.sdk.producttypes.ProductTypeDraftBuilder;
import io.sphere.sdk.producttypes.commands.ProductTypeCreateCommand;
import io.sphere.sdk.producttypes.commands.ProductTypeDeleteCommand;
import io.sphere.sdk.producttypes.queries.ProductTypeQuery;
import io.sphere.sdk.queries.PagedQueryResult;
import io.sphere.sdk.queries.QueryPredicate;
import io.sphere.sdk.types.ResourceTypeIdsSetBuilder;
import io.sphere.sdk.types.Type;
import io.sphere.sdk.types.TypeDraft;
import io.sphere.sdk.types.TypeDraftBuilder;
import io.sphere.sdk.types.commands.TypeCreateCommand;
import io.sphere.sdk.types.commands.TypeDeleteCommand;
import io.sphere.sdk.types.queries.TypeQuery;
import java.time.Clock;
import java.time.ZonedDateTime;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.org.lidalia.slf4jtest.TestLogger;
import uk.org.lidalia.slf4jtest.TestLoggerFactory;

class CliRunnerIT {
  private static final TestLogger testLogger = TestLoggerFactory.getTestLogger(Syncer.class);

  @BeforeEach
  void setupSuite() {
    cleanUpProjects(createClient(CTP_SOURCE_CLIENT_CONFIG), createClient(CTP_TARGET_CLIENT_CONFIG));
  }

  private static void cleanUpProjects(
      @Nonnull final SphereClient sourceClient, @Nonnull final SphereClient targetClient) {

    deleteProjectData(sourceClient);
    deleteProjectData(targetClient);
    deleteLastSyncCustomObjects(targetClient, sourceClient.getConfig().getProjectKey());
  }

  private static void deleteProjectData(@Nonnull final SphereClient client) {
    queryAndExecute(client, CategoryQuery.of(), CategoryDeleteCommand::of);
    queryAndExecute(client, ProductQuery.of(), ProductDeleteCommand::of);
    queryAndExecute(client, ProductTypeQuery.of(), ProductTypeDeleteCommand::of);
    queryAndExecute(client, TypeQuery.of(), TypeDeleteCommand::of);
    queryAndExecute(client, InventoryEntryQuery.of(), InventoryEntryDeleteCommand::of);
  }

  @AfterEach
  void tearDownTest() {
    testLogger.clearAll();
    cleanUpProjects(createClient(CTP_SOURCE_CLIENT_CONFIG), createClient(CTP_TARGET_CLIENT_CONFIG));
  }

  @Test
  void run_WithSyncAsArgumentWithAllArg_ShouldExecuteAllSyncers() {
    // preparation
    final String resourceKey = "foo";
    try (final SphereClient targetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
      try (final SphereClient sourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {
        setupTestData(sourceClient, resourceKey);

        final SyncerFactory syncerFactory =
            SyncerFactory.of(() -> sourceClient, () -> targetClient, Clock.systemDefaultZone());

        // test
        CliRunner.of().run(new String[] {"-s", "all"}, syncerFactory);
      }
    }

    // create clients again (for assertions and cleanup), since the run method closes the clients
    // after execution
    // is done.
    try (final SphereClient postSourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {
      try (final SphereClient postTargetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
        // assertions
        assertAllSyncersLoggingEvents(testLogger, 1);

        assertAllResourcesAreSyncedToTarget(resourceKey, postTargetClient);

        cleanUpProjects(postSourceClient, postTargetClient);
      }
    }
  }

  @Test
  void
      run_WithSyncAsArgumentWithAllArg_ShouldExecuteAllSyncersAndStoreLastSyncTimestampsAsCustomObjects() {
    // preparation
    final String resourceKey = "foo";
    try (final SphereClient targetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
      try (final SphereClient sourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {
        setupTestData(sourceClient, resourceKey);

        final SyncerFactory syncerFactory =
            SyncerFactory.of(() -> sourceClient, () -> targetClient, Clock.systemDefaultZone());

        // test
        CliRunner.of().run(new String[] {"-s", "all"}, syncerFactory);
      }
    }

    // create clients again (for assertions and cleanup), since the run method closes the clients
    // after execution
    // is done.
    try (final SphereClient postSourceClient = createClient(CTP_SOURCE_CLIENT_CONFIG)) {
      try (final SphereClient postTargetClient = createClient(CTP_TARGET_CLIENT_CONFIG)) {
        // assertions
        assertAllSyncersLoggingEvents(testLogger, 1);

        assertAllResourcesAreSyncedToTarget(resourceKey, postTargetClient);

        final ZonedDateTime lastSyncTimestamp =
            assertCurrentCtpTimestampGeneratorAndGetLastModifiedAt(postTargetClient);

        final String sourceProjectKey = postSourceClient.getConfig().getProjectKey();

        assertLastSyncCustomObject(
            postTargetClient,
            sourceProjectKey,
            "productSync",
            ProductSyncStatistics.class,
            lastSyncTimestamp);

        assertLastSyncCustomObject(
            postTargetClient,
            sourceProjectKey,
            "categorySync",
            CategorySyncStatistics.class,
            lastSyncTimestamp);

        assertLastSyncCustomObject(
            postTargetClient,
            sourceProjectKey,
            "productTypeSync",
            ProductTypeSyncStatistics.class,
            lastSyncTimestamp);

        assertLastSyncCustomObject(
            postTargetClient,
            sourceProjectKey,
            "typeSync",
            TypeSyncStatistics.class,
            lastSyncTimestamp);

        assertLastSyncCustomObject(
            postTargetClient,
            sourceProjectKey,
            "inventoryEntrySync",
            InventorySyncStatistics.class,
            lastSyncTimestamp);

        cleanUpProjects(postSourceClient, postTargetClient);
      }
    }
  }

  private void assertLastSyncCustomObject(
      @Nonnull final SphereClient targetClient,
      @Nonnull final String sourceProjectKey,
      @Nonnull final String syncModule,
      @Nonnull final Class<? extends BaseSyncStatistics> statisticsClass,
      @Nonnull final ZonedDateTime lastSyncTimestamp) {

    final CustomObjectQuery<LastSyncCustomObject> lastSyncQuery =
        CustomObjectQuery.of(LastSyncCustomObject.class)
            .byContainer(format("commercetools-project-sync.%s", syncModule));

    final PagedQueryResult<CustomObject<LastSyncCustomObject>> lastSyncResult =
        targetClient.execute(lastSyncQuery).toCompletableFuture().join();

    assertThat(lastSyncResult.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(
            lastSyncCustomObject -> {
              assertThat(lastSyncCustomObject.getKey()).isEqualTo(sourceProjectKey);
              assertThat(lastSyncCustomObject.getValue())
                  .satisfies(
                      value -> {
                        assertThat(value.getLastSyncStatistics()).isInstanceOf(statisticsClass);
                        assertThat(value.getLastSyncStatistics().getProcessed()).isEqualTo(1);
                        assertThat(value.getLastSyncTimestamp()).isEqualTo(lastSyncTimestamp);
                      });
            });
  }

  @Nonnull
  private ZonedDateTime assertCurrentCtpTimestampGeneratorAndGetLastModifiedAt(
      @Nonnull final SphereClient targetClient) {

    final CustomObjectQuery<String> timestampGeneratorQuery =
        CustomObjectQuery.of(String.class)
            .byContainer(
                format("commercetools-project-sync.%s", TIMESTAMP_GENERATOR_CONTAINER_POSTFIX));

    final PagedQueryResult<CustomObject<String>> currentCtpTimestampGeneratorResults =
        targetClient.execute(timestampGeneratorQuery).toCompletableFuture().join();

    assertThat(currentCtpTimestampGeneratorResults.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(
            currentCtpTimestamp -> {
              assertThat(currentCtpTimestamp.getKey()).isEqualTo(TIMESTAMP_GENERATOR_KEY);
              assertThat(currentCtpTimestamp.getValue()).isEqualTo(TIMESTAMP_GENERATOR_VALUE);
            });

    return currentCtpTimestampGeneratorResults.getResults().get(0).getLastModifiedAt();
  }

  private static void assertAllResourcesAreSyncedToTarget(
      @Nonnull final String resourceKey, @Nonnull final SphereClient targetClient) {

    final PagedQueryResult<ProductType> productTypeQueryResult =
        targetClient.execute(ProductTypeQuery.of().byKey(resourceKey)).toCompletableFuture().join();

    assertThat(productTypeQueryResult.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(
            productType -> assertThat(productType.getKey()).isEqualTo(resourceKey));

    final String queryPredicate = format("key=\"%s\"", resourceKey);

    final PagedQueryResult<Type> typeQueryResult =
        targetClient
            .execute(TypeQuery.of().withPredicates(QueryPredicate.of(queryPredicate)))
            .toCompletableFuture()
            .join();

    assertThat(typeQueryResult.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(type -> assertThat(type.getKey()).isEqualTo(resourceKey));

    final PagedQueryResult<Category> categoryQueryResult =
        targetClient
            .execute(CategoryQuery.of().withPredicates(QueryPredicate.of(queryPredicate)))
            .toCompletableFuture()
            .join();

    assertThat(categoryQueryResult.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(
            category -> assertThat(category.getKey()).isEqualTo(resourceKey));

    final PagedQueryResult<Product> productQueryResult =
        targetClient
            .execute(ProductQuery.of().withPredicates(QueryPredicate.of(queryPredicate)))
            .toCompletableFuture()
            .join();

    assertThat(productQueryResult.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(
            product -> {
              assertThat(product.getKey()).isEqualTo(resourceKey);
              final ProductVariant stagedMasterVariant =
                  product.getMasterData().getStaged().getMasterVariant();
              assertThat(stagedMasterVariant.getKey()).isEqualTo(resourceKey);
              assertThat(stagedMasterVariant.getSku()).isEqualTo(resourceKey);
            });

    final PagedQueryResult<InventoryEntry> inventoryEntryQueryResult =
        targetClient
            .execute(
                InventoryEntryQuery.of()
                    .withPredicates(QueryPredicate.of(format("sku=\"%s\"", resourceKey))))
            .toCompletableFuture()
            .join();

    assertThat(inventoryEntryQueryResult.getResults())
        .hasSize(1)
        .hasOnlyOneElementSatisfying(
            inventoryEntry -> assertThat(inventoryEntry.getSku()).isEqualTo(resourceKey));
  }

  static void setupTestData(@Nonnull final SphereClient client, @Nonnull final String resourceKey) {
    final ProductTypeDraft productTypeDraft =
        ProductTypeDraftBuilder.of(resourceKey, "bar", "desc", emptyList()).build();

    final ProductType productType =
        client.execute(ProductTypeCreateCommand.of(productTypeDraft)).toCompletableFuture().join();

    final TypeDraft typeDraft =
        TypeDraftBuilder.of(
                resourceKey, ofEnglish("bar"), ResourceTypeIdsSetBuilder.of().addCategories())
            .build();

    client.execute(TypeCreateCommand.of(typeDraft)).toCompletableFuture().join();

    final CategoryDraft categoryDraft =
        CategoryDraftBuilder.of(ofEnglish("foo"), ofEnglish("bar")).key(resourceKey).build();

    client.execute(CategoryCreateCommand.of(categoryDraft)).toCompletableFuture().join();

    final ProductDraft productDraft =
        ProductDraftBuilder.of(
                productType,
                ofEnglish("foo"),
                ofEnglish("bar"),
                ProductVariantDraftBuilder.of().key(resourceKey).sku(resourceKey).build())
            .key(resourceKey)
            .build();

    client.execute(ProductCreateCommand.of(productDraft)).toCompletableFuture().join();

    final InventoryEntryDraft inventoryEntryDraft =
        InventoryEntryDraftBuilder.of(resourceKey, 1L).build();

    client
        .execute(InventoryEntryCreateCommand.of(inventoryEntryDraft))
        .toCompletableFuture()
        .join();
  }
}
