/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.repositories.blobstore;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Numbers;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.env.Environment;
import org.elasticsearch.indices.recovery.RecoverySettings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.RepositoryPlugin;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.repositories.RepositoriesService;
import org.elasticsearch.repositories.Repository;
import org.elasticsearch.repositories.RepositoryData;
import org.elasticsearch.repositories.RepositoryException;
import org.elasticsearch.repositories.ShardGeneration;
import org.elasticsearch.repositories.ShardGenerations;
import org.elasticsearch.repositories.fs.FsRepository;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.snapshots.SnapshotState;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.repositories.RepositoryDataTests.generateRandomRepoData;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for the {@link BlobStoreRepository} and its subclasses.
 */
public class BlobStoreRepositoryTests extends ESSingleNodeTestCase {

    static final String REPO_TYPE = "fsLike";

    protected Collection<Class<? extends Plugin>> getPlugins() {
        return Arrays.asList(FsLikeRepoPlugin.class);
    }

    // the reason for this plug-in is to drop any assertSnapshotOrGenericThread as mostly all access in this test goes from test threads
    public static class FsLikeRepoPlugin extends Plugin implements RepositoryPlugin {

        @Override
        public Map<String, Repository.Factory> getRepositories(
            Environment env,
            NamedXContentRegistry namedXContentRegistry,
            ClusterService clusterService,
            BigArrays bigArrays,
            RecoverySettings recoverySettings
        ) {
            return Collections.singletonMap(
                REPO_TYPE,
                (metadata) -> new FsRepository(metadata, env, namedXContentRegistry, clusterService, bigArrays, recoverySettings) {
                    @Override
                    protected void assertSnapshotOrGenericThread() {
                        // eliminate thread name check as we access blobStore on test/main threads
                    }
                }
            );
        }
    }

    public void testRetrieveSnapshots() throws Exception {
        final Client client = client();
        final Path location = ESIntegTestCase.randomRepoPath(node().settings());
        final String repositoryName = "test-repo";

        logger.info("-->  creating repository");
        AcknowledgedResponse putRepositoryResponse = client.admin()
            .cluster()
            .preparePutRepository(repositoryName)
            .setType(REPO_TYPE)
            .setSettings(Settings.builder().put(node().settings()).put("location", location))
            .get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        logger.info("--> creating an index and indexing documents");
        final String indexName = "test-idx";
        createIndex(indexName);
        ensureGreen();
        int numDocs = randomIntBetween(10, 20);
        for (int i = 0; i < numDocs; i++) {
            String id = Integer.toString(i);
            client().prepareIndex(indexName).setId(id).setSource("text", "sometext").get();
        }
        client().admin().indices().prepareFlush(indexName).get();

        logger.info("--> create first snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(repositoryName, "test-snap-1")
            .setWaitForCompletion(true)
            .setIndices(indexName)
            .get();
        final SnapshotId snapshotId1 = createSnapshotResponse.getSnapshotInfo().snapshotId();

        logger.info("--> create second snapshot");
        createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(repositoryName, "test-snap-2")
            .setWaitForCompletion(true)
            .setIndices(indexName)
            .get();
        final SnapshotId snapshotId2 = createSnapshotResponse.getSnapshotInfo().snapshotId();

        logger.info("--> make sure the node's repository can resolve the snapshots");
        final RepositoriesService repositoriesService = getInstanceFromNode(RepositoriesService.class);
        final BlobStoreRepository repository = (BlobStoreRepository) repositoriesService.repository(repositoryName);
        final List<SnapshotId> originalSnapshots = Arrays.asList(snapshotId1, snapshotId2);

        List<SnapshotId> snapshotIds = ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository)
            .getSnapshotIds()
            .stream()
            .sorted((s1, s2) -> s1.getName().compareTo(s2.getName()))
            .collect(Collectors.toList());
        assertThat(snapshotIds, equalTo(originalSnapshots));
    }

    public void testReadAndWriteSnapshotsThroughIndexFile() throws Exception {
        final BlobStoreRepository repository = setupRepo();
        final long pendingGeneration = repository.metadata.pendingGeneration();
        // write to and read from a index file with no entries
        assertThat(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository).getSnapshotIds().size(), equalTo(0));
        final RepositoryData emptyData = RepositoryData.EMPTY;
        writeIndexGen(repository, emptyData, emptyData.getGenId());
        RepositoryData repoData = ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository);
        assertEquals(repoData, emptyData);
        assertEquals(repoData.getIndices().size(), 0);
        assertEquals(repoData.getSnapshotIds().size(), 0);
        assertEquals(pendingGeneration + 1L, repoData.getGenId());

        // write to and read from an index file with snapshots but no indices
        repoData = addRandomSnapshotsToRepoData(repoData, false);
        writeIndexGen(repository, repoData, repoData.getGenId());
        assertEquals(repoData, ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository));

        // write to and read from a index file with random repository data
        repoData = addRandomSnapshotsToRepoData(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository), true);
        writeIndexGen(repository, repoData, repoData.getGenId());
        assertEquals(repoData, ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository));
    }

    public void testIndexGenerationalFiles() throws Exception {
        final BlobStoreRepository repository = setupRepo();
        assertEquals(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository), RepositoryData.EMPTY);

        final long pendingGeneration = repository.metadata.pendingGeneration();

        // write to index generational file
        RepositoryData repositoryData = generateRandomRepoData();
        writeIndexGen(repository, repositoryData, RepositoryData.EMPTY_REPO_GEN);
        assertThat(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository), equalTo(repositoryData));
        final long expectedGeneration = pendingGeneration + 1L;
        assertThat(repository.latestIndexBlobId(), equalTo(expectedGeneration));
        assertThat(repository.readSnapshotIndexLatestBlob(), equalTo(expectedGeneration));

        // adding more and writing to a new index generational file
        repositoryData = addRandomSnapshotsToRepoData(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository), true);
        writeIndexGen(repository, repositoryData, repositoryData.getGenId());
        assertEquals(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository), repositoryData);
        assertThat(repository.latestIndexBlobId(), equalTo(expectedGeneration + 1L));
        assertThat(repository.readSnapshotIndexLatestBlob(), equalTo(expectedGeneration + 1L));

        // removing a snapshot and writing to a new index generational file
        repositoryData = ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository)
            .removeSnapshots(Collections.singleton(repositoryData.getSnapshotIds().iterator().next()), ShardGenerations.EMPTY);
        writeIndexGen(repository, repositoryData, repositoryData.getGenId());
        assertEquals(ESBlobStoreRepositoryIntegTestCase.getRepositoryData(repository), repositoryData);
        assertThat(repository.latestIndexBlobId(), equalTo(expectedGeneration + 2L));
        assertThat(repository.readSnapshotIndexLatestBlob(), equalTo(expectedGeneration + 2L));
    }

    public void testCorruptIndexLatestFile() throws Exception {
        final BlobStoreRepository repository = setupRepo();

        final long generation = randomLong();
        final byte[] generationBytes = Numbers.longToBytes(generation);

        final byte[] buffer = new byte[16];
        System.arraycopy(generationBytes, 0, buffer, 0, 8);

        for (int i = 0; i < 16; i++) {
            repository.blobContainer().writeBlob(BlobStoreRepository.INDEX_LATEST_BLOB, new BytesArray(buffer, 0, i), false);
            if (i == 8) {
                assertThat(repository.readSnapshotIndexLatestBlob(), equalTo(generation));
            } else {
                assertThat(
                    expectThrows(RepositoryException.class, repository::readSnapshotIndexLatestBlob).getMessage(),
                    allOf(
                        containsString("exception reading blob [index.latest]: expected 8 bytes"),
                        i < 8 ? containsString("blob was " + i + " bytes") : containsString("blob was longer")
                    )
                );
            }
        }
    }

    public void testRepositoryDataConcurrentModificationNotAllowed() throws Exception {
        final BlobStoreRepository repository = setupRepo();

        // write to index generational file
        RepositoryData repositoryData = generateRandomRepoData();
        final long startingGeneration = repositoryData.getGenId();
        writeIndexGen(repository, repositoryData, startingGeneration);

        // write repo data again to index generational file, errors because we already wrote to the
        // N+1 generation from which this repository data instance was created
        final RepositoryData fresherRepositoryData = repositoryData.withGenId(startingGeneration + 1);
        expectThrows(RepositoryException.class, () -> writeIndexGen(repository, fresherRepositoryData, repositoryData.getGenId()));
    }

    public void testBadChunksize() throws Exception {
        final Client client = client();
        final Path location = ESIntegTestCase.randomRepoPath(node().settings());
        final String repositoryName = "test-repo";

        expectThrows(
            RepositoryException.class,
            () -> client.admin()
                .cluster()
                .preparePutRepository(repositoryName)
                .setType(REPO_TYPE)
                .setSettings(
                    Settings.builder()
                        .put(node().settings())
                        .put("location", location)
                        .put("chunk_size", randomLongBetween(-10, 0), ByteSizeUnit.BYTES)
                )
                .get()
        );
    }

    public void testRepositoryDataDetails() throws Exception {
        final BlobStoreRepository repository = setupRepo();
        final String repositoryName = repository.getMetadata().name();
        final Settings repositorySettings = repository.getMetadata().settings();

        createIndex("green-index");
        ensureGreen("green-index");

        assertAcked(
            client().admin()
                .indices()
                .prepareCreate("red-index")
                .setSettings(
                    Settings.builder()
                        .put(IndexMetadata.INDEX_ROUTING_EXCLUDE_GROUP_SETTING.getConcreteSettingForNamespace("_name").getKey(), "*")
                        .build()
                )
                .setWaitForActiveShards(0)
        );

        final long beforeStartTime = getInstanceFromNode(ThreadPool.class).absoluteTimeInMillis();
        final CreateSnapshotResponse createSnapshotResponse = client().admin()
            .cluster()
            .prepareCreateSnapshot(repositoryName, "test-snap-1")
            .setWaitForCompletion(true)
            .setPartial(true)
            .get();
        final long afterEndTime = System.currentTimeMillis();

        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.PARTIAL));
        final SnapshotId snapshotId = createSnapshotResponse.getSnapshotInfo().snapshotId();

        final Consumer<RepositoryData.SnapshotDetails> snapshotDetailsAsserter = snapshotDetails -> {
            assertThat(snapshotDetails.getSnapshotState(), equalTo(SnapshotState.PARTIAL));
            assertThat(snapshotDetails.getVersion(), equalTo(Version.CURRENT));
            assertThat(snapshotDetails.getStartTimeMillis(), allOf(greaterThanOrEqualTo(beforeStartTime), lessThanOrEqualTo(afterEndTime)));
            assertThat(
                snapshotDetails.getEndTimeMillis(),
                allOf(
                    greaterThanOrEqualTo(beforeStartTime),
                    lessThanOrEqualTo(afterEndTime),
                    greaterThanOrEqualTo(snapshotDetails.getStartTimeMillis())
                )
            );
        };

        final RepositoryData repositoryData = PlainActionFuture.get(repository::getRepositoryData);
        final RepositoryData.SnapshotDetails snapshotDetails = repositoryData.getSnapshotDetails(snapshotId);
        snapshotDetailsAsserter.accept(snapshotDetails);

        // now check the handling of the case where details are missing, by removing the details from the repo data as if from an
        // older repo format and verifing that they are refreshed from the SnapshotInfo when writing the repo data out

        writeIndexGen(
            repository,
            repositoryData.withExtraDetails(
                Collections.singletonMap(
                    snapshotId,
                    new RepositoryData.SnapshotDetails(SnapshotState.PARTIAL, Version.CURRENT, -1, -1, null)
                )
            ),
            repositoryData.getGenId()
        );

        snapshotDetailsAsserter.accept(PlainActionFuture.get(repository::getRepositoryData).getSnapshotDetails(snapshotId));
    }

    private static void writeIndexGen(BlobStoreRepository repository, RepositoryData repositoryData, long generation) throws Exception {
        PlainActionFuture.<RepositoryData, Exception>get(
            f -> repository.writeIndexGen(repositoryData, generation, Version.CURRENT, Function.identity(), f)
        );
    }

    private BlobStoreRepository setupRepo() {
        final Client client = client();
        final Path location = ESIntegTestCase.randomRepoPath(node().settings());
        final String repositoryName = "test-repo";

        Settings.Builder repoSettings = Settings.builder().put(node().settings()).put("location", location);
        boolean compress = randomBoolean();
        if (compress == false) {
            repoSettings.put(BlobStoreRepository.COMPRESS_SETTING.getKey(), false);
        }
        AcknowledgedResponse putRepositoryResponse = client.admin()
            .cluster()
            .preparePutRepository(repositoryName)
            .setType(REPO_TYPE)
            .setSettings(repoSettings)
            .setVerify(false) // prevent eager reading of repo data
            .get();
        assertThat(putRepositoryResponse.isAcknowledged(), equalTo(true));

        final RepositoriesService repositoriesService = getInstanceFromNode(RepositoriesService.class);
        final BlobStoreRepository repository = (BlobStoreRepository) repositoriesService.repository(repositoryName);
        assertThat("getBlobContainer has to be lazy initialized", repository.getBlobContainer(), nullValue());
        assertEquals("Compress must be set to", compress, repository.isCompress());
        return repository;
    }

    private RepositoryData addRandomSnapshotsToRepoData(RepositoryData repoData, boolean inclIndices) {
        int numSnapshots = randomIntBetween(1, 20);
        for (int i = 0; i < numSnapshots; i++) {
            SnapshotId snapshotId = new SnapshotId(randomAlphaOfLength(8), UUIDs.randomBase64UUID());
            int numIndices = inclIndices ? randomIntBetween(0, 20) : 0;
            final ShardGenerations.Builder builder = ShardGenerations.builder();
            for (int j = 0; j < numIndices; j++) {
                builder.put(new IndexId(randomAlphaOfLength(8), UUIDs.randomBase64UUID()), 0, new ShardGeneration(1L));
            }
            final ShardGenerations shardGenerations = builder.build();
            final Map<IndexId, String> indexLookup = shardGenerations.indices()
                .stream()
                .collect(Collectors.toMap(Function.identity(), ind -> randomAlphaOfLength(256)));
            final RepositoryData.SnapshotDetails details = new RepositoryData.SnapshotDetails(
                randomFrom(SnapshotState.SUCCESS, SnapshotState.PARTIAL, SnapshotState.FAILED),
                Version.CURRENT,
                randomNonNegativeLong(),
                randomNonNegativeLong(),
                randomAlphaOfLength(10)
            );
            repoData = repoData.addSnapshot(
                snapshotId,
                details,
                shardGenerations,
                indexLookup,
                indexLookup.values().stream().collect(Collectors.toMap(Function.identity(), ignored -> UUIDs.randomBase64UUID(random())))
            );
        }
        return repoData;
    }

}
