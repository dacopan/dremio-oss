/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.reflection;

import static com.dremio.service.reflection.ExternalReflectionStatus.STATUS.OUT_OF_SYNC;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.TableScan;

import com.dremio.exec.calcite.logical.ScanCrel;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.exec.planner.acceleration.CachedMaterializationDescriptor;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.MaterializationDescriptor;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.CatalogService;
import com.dremio.service.Pointer;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.reflection.proto.ExternalReflection;
import com.dremio.service.reflection.proto.Materialization;
import com.dremio.service.reflection.proto.MaterializationId;
import com.dremio.service.reflection.proto.ReflectionId;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.protostuff.ByteString;

/**
 * Cache for {@link MaterializationDescriptor} to avoid having to expand all the descriptor's plans for every planned
 * query.
 */
class MaterializationCache {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MaterializationCache.class);

  private static final Map<String, CachedMaterializationDescriptor> EMPTY_MAP = ImmutableMap.of();

  private final AtomicReference<Map<String, CachedMaterializationDescriptor>> cached = new AtomicReference<>(EMPTY_MAP);

  /**
   * CacheHelper helps with keeping MaterializationCache up to date and also handles materialization expansion.
   * This interface is a bit confusing in that when materialization cache is disabled, this interface still
   * provides the materialization expansion implementation.
   */
  interface CacheHelper {
    Iterable<Materialization> getValidMaterializations();
    Iterable<ExternalReflection> getExternalReflections();
    MaterializationDescriptor getDescriptor(ExternalReflection externalReflection) throws CacheException;

    /**
     * tries to expand the descriptor's plan
     * @return expanded descriptor, or null if failed to deserialize the plan
     */
    DremioMaterialization expand(MaterializationDescriptor descriptor);

    /**
     * tries to expand the external materialization's plan.
     * @return expanded descriptor, or null if failed to deserialize the plan
     */
    CachedMaterializationDescriptor expand(Materialization materialization) throws CacheException;
  }

  public interface CacheViewer {
    boolean isCached(MaterializationId id);
  }

  private final CacheHelper provider;
  private final ReflectionStatusService reflectionStatusService;
  private final CatalogService catalogService;

  MaterializationCache(CacheHelper provider, ReflectionStatusService reflectionStatusService, CatalogService catalogService) {
    this.provider = Preconditions.checkNotNull(provider, "materialization provider required");
    this.reflectionStatusService = Preconditions.checkNotNull(reflectionStatusService, "reflection status service required");
    this.catalogService = Preconditions.checkNotNull(catalogService, "catalog service required");
  }

  static final class CacheException extends Exception {
    CacheException(String message) {
      super(message);
    }
  }

  @WithSpan
  void refreshMaterializationCache() {
    compareAndSetCache();
  }

  private void compareAndSetCache() {
    boolean exchanged;
    do {
      Map<String, CachedMaterializationDescriptor> old = cached.get();
      Map<String, CachedMaterializationDescriptor> updated = updateMaterializationCache(old);
      exchanged = cached.compareAndSet(old, updated);
    } while(!exchanged);
  }

  void resetCache() {
    boolean exchanged;
    do {
      Map<String, CachedMaterializationDescriptor> old = cached.get();
      exchanged = cached.compareAndSet(old, EMPTY_MAP);
    } while(!exchanged);
  }

  /**
   * Updates the cache map taking into account the existing cache.<br>
   * Will only "expand" descriptors that are new in the cache.<br>
   * Because, in debug mode, this can be called from multiple threads, it must be thread-safe
   *
   * @param old existing cache
   * @return updated cache
   */
  @WithSpan
  private Map<String, CachedMaterializationDescriptor> updateMaterializationCache(Map<String, CachedMaterializationDescriptor> old) {
    // new list of descriptors
    final Iterable<Materialization> provided = provider.getValidMaterializations();
    // this will hold the updated cache
    final Map<String, CachedMaterializationDescriptor> updated = Maps.newHashMap();
    EntityExplorer catalog = CatalogUtil.getSystemCatalogForReflections(catalogService);


    int materializationExpandCount = 0;
    int materializationReuseCount = 0;
    int materializationErrorCount = 0;
    // cache is enabled so we want to reuse as much of the existing cache as possible. Make sure to:
    // remove all cached descriptors that no longer exist
    // reuse all descriptors that are already in the cache
    // add any descriptor that are not already cached
    for (Materialization materialization : provided) {
      final CachedMaterializationDescriptor cachedDescriptor = old.get(materialization.getId().getId());
      if (cachedDescriptor == null ||
          !materialization.getTag().equals(cachedDescriptor.getVersion()) ||
          schemaChanged(cachedDescriptor, materialization, catalog)) {
        if (safeUpdateMaterializationEntry(updated, materialization)) {
          materializationExpandCount++;
        } else {
          materializationErrorCount++;
        }
      } else {
        // descriptor already in the cache, we can just reuse it
        updated.put(materialization.getId().getId(), cachedDescriptor);
        materializationReuseCount++;
      }
    }

    int externalExpandCount = 0;
    int externalReuseCount = 0;
    int externalErrorCount = 0;
    for (ExternalReflection externalReflection : provider.getExternalReflections()) {
      final CachedMaterializationDescriptor cachedDescriptor = old.get(externalReflection.getId());
      if (cachedDescriptor == null
          || isExternalReflectionOutOfSync(externalReflection.getId())
          || isExternalReflectionMetadataUpdated(cachedDescriptor, catalog)) {
        if (updateExternalReflectionEntry(updated, externalReflection)) {
          externalExpandCount++;
        } else {
          externalErrorCount++;
        }
      } else {
        // descriptor already in the cache, we can just reuse it
        updated.put(externalReflection.getId(), cachedDescriptor);
        externalReuseCount++;
      }
    }
    logger.info("Materialization cache updated.  Count stats: " +
        "materializationReuse={} materializationExpand={} materializationError={} " +
        "externalReuse={} externalExpand={} externalError={}",
      materializationReuseCount, materializationExpandCount, materializationErrorCount,
      externalReuseCount, externalExpandCount, externalErrorCount);
    Span.current().setAttribute("dremio.materialization_cache.materializationReuseCount", materializationReuseCount);
    Span.current().setAttribute("dremio.materialization_cache.materializationExpandCount", materializationExpandCount);
    Span.current().setAttribute("dremio.materialization_cache.materializationErrorCount", materializationErrorCount);
    Span.current().setAttribute("dremio.materialization_cache.externalReuseCount", externalReuseCount);
    Span.current().setAttribute("dremio.materialization_cache.externalExpandCount", externalExpandCount);
    Span.current().setAttribute("dremio.materialization_cache.externalErrorCount", externalErrorCount);

    return updated;
  }

  private boolean isExternalReflectionMetadataUpdated(CachedMaterializationDescriptor descriptor, EntityExplorer catalog) {
    DremioMaterialization materialization = descriptor.getMaterialization();
    Pointer<Boolean> updated = new Pointer<>(false);
    materialization.getTableRel().accept(new RelShuttleImpl() {
      @Override
      public RelNode visit(TableScan tableScan) {
        if (tableScan instanceof ScanCrel) {
          String version = ((ScanCrel) tableScan).getTableMetadata().getVersion();
          DatasetConfig datasetConfig = CatalogUtil.getDatasetConfig(catalog, new NamespaceKey(tableScan.getTable().getQualifiedName()));
          if (datasetConfig == null) {
            updated.value = true;
          } else {
            if (!datasetConfig.getTag().equals(version)) {
              logger.debug("Dataset {} has new data. Invalidating cache for external reflection", tableScan.getTable().getQualifiedName());
              updated.value = true;
            }
          }
        } else {
          updated.value = true;
        }
        return tableScan;
      }
    });
    return updated.value;
  }

  private boolean isExternalReflectionOutOfSync(String id) {
    return reflectionStatusService.getExternalReflectionStatus(new ReflectionId(id)).getConfigStatus() == OUT_OF_SYNC;
  }

  @WithSpan
  private boolean updateExternalReflectionEntry(Map<String, CachedMaterializationDescriptor> cache, ExternalReflection entry) {
    Span.current().setAttribute("dremio.materialization_cache.reflection_id", entry.getId());
    Span.current().setAttribute("dremio.materialization_cache.name", entry.getName());
    Span.current().setAttribute("dremio.materialization_cache.query_dataset_id", entry.getQueryDatasetId());
    Span.current().setAttribute("dremio.materialization_cache.target_dataset_id", entry.getTargetDatasetId());
    try {
      final MaterializationDescriptor descriptor = provider.getDescriptor(entry);
      if (descriptor != null) {
        final DremioMaterialization expanded = provider.expand(descriptor);
        if (expanded != null) {
          cache.put(entry.getId(), new CachedMaterializationDescriptor(descriptor, expanded, catalogService));
          return true;
        }
      }
    } catch (Exception e) {
      logger.debug("couldn't expand materialization {}", entry.getId(), e);
    }
    return false;
  }

  private boolean safeUpdateMaterializationEntry(Map<String, CachedMaterializationDescriptor> cache, Materialization entry) {
    try {
      return updateMaterializationEntry(cache, entry);
    } catch (AssertionError e) {
      // Calcite can throw assertion errors even when assertions are disabled :( that's why we need to make sure we catch them here
      logger.debug("couldn't expand materialization {}", entry.getId(), e);
    } catch (Exception ignored) {
      // Other exceptions are already logged through updateEntry function.
    }
    return false;
  }

  @WithSpan
  private boolean updateMaterializationEntry(Map<String, CachedMaterializationDescriptor> cache, Materialization entry) throws CacheException {
    Span.current().setAttribute("dremio.materialization_cache.reflection_id", entry.getReflectionId().getId());
    Span.current().setAttribute("dremio.materialization_cache.materialization_id", entry.getId().getId());
    final CachedMaterializationDescriptor descriptor = provider.expand(entry);
    if (descriptor != null) {
      cache.put(entry.getId().getId(), descriptor);
      return true;
    }
    return false;
  }

  private boolean schemaChanged(MaterializationDescriptor old, Materialization materialization, EntityExplorer catalog) {
    //TODO is this enough ? shouldn't we use the dataset hash instead ??
    final NamespaceKey matKey = new NamespaceKey(ReflectionUtils.getMaterializationPath(materialization));

    DatasetConfig datasetConfig = CatalogUtil.getDatasetConfig(catalog, matKey);
    if (datasetConfig == null ) {
      return true;
    }

    ByteString schemaString = datasetConfig.getRecordSchema();
    BatchSchema newSchema = BatchSchema.deserialize(schemaString);
    BatchSchema oldSchema = ((CachedMaterializationDescriptor) old).getMaterialization().getSchema();
    return !oldSchema.equals(newSchema);
  }

  /**
   * remove entry from the cache
   * @param mId entry to be removed
   */
  void invalidate(MaterializationId mId) {
    boolean exchanged;
    do {
      Map<String, CachedMaterializationDescriptor> old = cached.get();
      if (!old.containsKey(mId.getId())) {
        break; // entry not present in the cache, nothing more to do
      }
      //copy over everything
      Map<String, CachedMaterializationDescriptor> updated =  Maps.newHashMap(old);
      //remove the specific materialization.
      updated.remove(mId.getId());
      //update the cache.
      exchanged = cached.compareAndSet(old, updated);
    } while(!exchanged);
  }

  void update(Materialization m) throws CacheException {
    // Do expansion (including deserialization) out of the do-while loop, so that in case it takes long time
    // the update loop does not race with MaterializationCache.refresh() and falls into infinite loop.
    final CachedMaterializationDescriptor descriptor = provider.expand(m);
    if (descriptor != null) {
      boolean exchanged;
      do {
        Map<String, CachedMaterializationDescriptor> old = cached.get();
        Map<String, CachedMaterializationDescriptor> updated = Maps.newHashMap(old); //copy over everything
        updated.put(m.getId().getId(), descriptor);
        exchanged = cached.compareAndSet(old, updated); //update the cache.
      } while (!exchanged);
    }
  }

  Iterable<MaterializationDescriptor> getAll() {
    return Iterables.unmodifiableIterable(cached.get().values());
  }

  MaterializationDescriptor get(MaterializationId mId) {
    return cached.get().get(mId.getId());
  }

  boolean contains(MaterializationId mId) {
    return cached.get().containsKey(mId.getId());
  }
}
