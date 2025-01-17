/*
 * Copyright © 2024 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.cdap.internal.app.store;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.cdap.cdap.proto.id.ApplicationReference;
import io.cdap.cdap.proto.sourcecontrol.SourceControlMeta;
import io.cdap.cdap.spi.data.StructuredRow;
import io.cdap.cdap.spi.data.StructuredTable;
import io.cdap.cdap.spi.data.StructuredTableContext;
import io.cdap.cdap.spi.data.TableNotFoundException;
import io.cdap.cdap.spi.data.table.field.Field;
import io.cdap.cdap.spi.data.table.field.Fields;
import io.cdap.cdap.spi.data.table.field.Range;
import io.cdap.cdap.store.StoreDefinition;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Store for namespace source control metadata.
 */
public class NamespaceSourceControlMetadataStore {

  private StructuredTable namespaceSourceControlMetadataTable;
  private final StructuredTableContext context;

  public static NamespaceSourceControlMetadataStore create(StructuredTableContext context) {
    return new NamespaceSourceControlMetadataStore(context);
  }

  private NamespaceSourceControlMetadataStore(StructuredTableContext context) {
    this.context = context;
  }

  private StructuredTable getNamespaceSourceControlMetadataTable() {
    try {
      if (namespaceSourceControlMetadataTable == null) {
        namespaceSourceControlMetadataTable = context.getTable(
            StoreDefinition.NamespaceSourceControlMetadataStore.NAMESPACE_SOURCE_CONTROL_METADATA);
      }
    } catch (TableNotFoundException e) {
      throw new TableNotFoundException(
          StoreDefinition.NamespaceSourceControlMetadataStore.NAMESPACE_SOURCE_CONTROL_METADATA);
    }
    return namespaceSourceControlMetadataTable;
  }

  /**
   * Retrieves the source control metadata for the specified application ID in the namespace from
   * {@code NamespaceSourceControlMetadata} table.
   *
   * @param appRef {@link ApplicationReference} for which the source control metadata is being
   *               retrieved.
   * @return The {@link SourceControlMeta} associated with the application ID, or {@code null} if no
   *         metadata is found.
   * @throws IOException If it fails to read the metadata.
   */
  @Nullable
  public SourceControlMeta get(ApplicationReference appRef) throws IOException {
    List<Field<?>> primaryKey = getPrimaryKey(appRef);
    StructuredTable table = getNamespaceSourceControlMetadataTable();
    Optional<StructuredRow> row = table.read(primaryKey);

    return row.map(nonNullRow -> {
      String specificationHash = nonNullRow.getString(
          StoreDefinition.NamespaceSourceControlMetadataStore.SPECIFICATION_HASH_FIELD);
      String commitId = nonNullRow.getString(
          StoreDefinition.NamespaceSourceControlMetadataStore.COMMIT_ID_FIELD);
      Long lastSynced = nonNullRow.getLong(
          StoreDefinition.NamespaceSourceControlMetadataStore.LAST_MODIFIED_FIELD);
      Instant lastSyncedInstant = lastSynced == 0L ? null : Instant.ofEpochMilli(lastSynced);
      Boolean isSynced = nonNullRow.getBoolean(
          StoreDefinition.NamespaceSourceControlMetadataStore.IS_SYNCED_FIELD);
      if (specificationHash == null && commitId == null && lastSynced == 0L) {
        return null;
      }
      return new SourceControlMeta(specificationHash, commitId, lastSyncedInstant,
          isSynced);
    }).orElse(null);
  }

  /**
   * Sets the source control metadata for the specified application ID in the namespace.  Source
   * control metadata will be null when the application is deployed in the namespace. It will be
   * non-null when the application is pulled from the remote repository and deployed in the
   * namespace.
   *
   * @param appRef            {@link ApplicationReference} for which the source control metadata is
   *                          being set.
   * @param sourceControlMeta The {@link SourceControlMeta} to be set. Can be {@code null} if
   *                          application is just deployed.
   * @throws IOException If failed to write the data.
   */
  public void write(ApplicationReference appRef,
      SourceControlMeta sourceControlMeta)
      throws IOException, IllegalArgumentException {
    // In the Namespace Pipelines page, the sync status (SYNCED or UNSYNCED)
    // and last modified of all the applications deployed in the namespace needs to be shown.
    // If source control information is not added when the app is deployed, the data will
    // split into two tables.
    // JOIN operation is not currently supported yet. The filtering (eg,
    // filter on UNSYNCED sync status) , sorting, searching , pagination becomes difficult.
    // Instead of doing filtering, searching, sorting in memory, it will happen at
    // database level.
    StructuredTable scmTable = getNamespaceSourceControlMetadataTable();
    SourceControlMeta existingSourceControlMeta = get(appRef);
    if (sourceControlMeta.getLastSyncedAt() != null && existingSourceControlMeta != null
        && sourceControlMeta.getLastSyncedAt()
        .isBefore(existingSourceControlMeta.getLastSyncedAt())) {

      throw new IllegalArgumentException(String.format(
          "Trying to write lastSynced as %d for the app name %s in namespace %s but an "
              + "updated row having a greater last modified is already present",
          sourceControlMeta.getLastSyncedAt().toEpochMilli(),
          appRef.getEntityName(),
          appRef.getNamespaceId()));
    }
    scmTable.upsert(getAllFields(appRef, sourceControlMeta, existingSourceControlMeta));
  }

  /**
   * Deletes the source control metadata associated with the specified application ID from the
   * namespace.
   *
   * @param appRef {@link ApplicationReference} whose source control metadata is to be deleted.
   * @throws IOException if it failed to read or delete the metadata
   */
  public void delete(ApplicationReference appRef) throws IOException {
    getNamespaceSourceControlMetadataTable().delete(getPrimaryKey(appRef));
  }

  /**
   * Deletes all rows of source control metadata within the specified namespace.
   *
   * @param namespace The namespace for which all source control metadata rows are to be deleted.
   * @throws IOException if it failed to read or delete the metadata.
   */
  public void deleteAll(String namespace) throws IOException {
    getNamespaceSourceControlMetadataTable().deleteAll(getNamespaceRange(namespace));
  }

  private Collection<Field<?>> getAllFields(ApplicationReference appRef,
      SourceControlMeta newSourceControlMeta, SourceControlMeta existingSourceControlMeta) {
    List<Field<?>> fields = getPrimaryKey(appRef);
    fields.add(Fields.stringField(
        StoreDefinition.NamespaceSourceControlMetadataStore.SPECIFICATION_HASH_FIELD,
        newSourceControlMeta.getFileHash()));
    fields.add(
        Fields.stringField(StoreDefinition.NamespaceSourceControlMetadataStore.COMMIT_ID_FIELD,
            newSourceControlMeta.getCommitId()));
    // Whenever an app is deployed, the expected behavior is that the last modified field will be
    // retained and not reset.
    fields.add(
        Fields.longField(StoreDefinition.NamespaceSourceControlMetadataStore.LAST_MODIFIED_FIELD,
            getLastModifiedValue(newSourceControlMeta, existingSourceControlMeta)));
    fields.add(
        Fields.booleanField(StoreDefinition.NamespaceSourceControlMetadataStore.IS_SYNCED_FIELD,
            newSourceControlMeta.getSyncStatus()));
    return fields;
  }

  private long getLastModifiedValue(SourceControlMeta newSourceControlMeta,
      SourceControlMeta existingSourceControlMeta) {
    if (newSourceControlMeta.getLastSyncedAt() == null && existingSourceControlMeta == null) {
      return 0L;
    }
    SourceControlMeta metaToUse =
        newSourceControlMeta.getLastSyncedAt() != null ? newSourceControlMeta : existingSourceControlMeta;
    return metaToUse.getLastSyncedAt().toEpochMilli();
  }

  private List<Field<?>> getPrimaryKey(ApplicationReference appRef) {
    List<Field<?>> primaryKey = new ArrayList<>();
    primaryKey.add(
        Fields.stringField(StoreDefinition.NamespaceSourceControlMetadataStore.NAMESPACE_FIELD,
            appRef.getNamespace()));
    primaryKey.add(
        Fields.stringField(StoreDefinition.NamespaceSourceControlMetadataStore.TYPE_FIELD,
            appRef.getEntityType().toString()));
    primaryKey.add(
        Fields.stringField(StoreDefinition.NamespaceSourceControlMetadataStore.NAME_FIELD,
            appRef.getEntityName()));
    return primaryKey;
  }

  private Range getNamespaceRange(String namespaceId) {
    return Range.singleton(
        ImmutableList.of(
            Fields.stringField(StoreDefinition.AppMetadataStore.NAMESPACE_FIELD, namespaceId)));
  }

  /**
   * Deletes all rows from the namespace source control metadata table. Only to be used in testing.
   *
   * @throws IOException If an I/O error occurs while deleting the metadata.
   */
  @VisibleForTesting
  void deleteNamespaceSourceControlMetadataTable() throws IOException {
    getNamespaceSourceControlMetadataTable().deleteAll(
        Range.from(ImmutableList.of(
                Fields.stringField(
                    StoreDefinition.NamespaceSourceControlMetadataStore.NAMESPACE_FIELD, "")),
            Range.Bound.INCLUSIVE));
  }

}
